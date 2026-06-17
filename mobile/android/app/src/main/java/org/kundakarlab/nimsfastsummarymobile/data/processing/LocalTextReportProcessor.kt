package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.kundakarlab.nimsfastsummarymobile.domain.model.*
import org.kundakarlab.nimsfastsummarymobile.domain.processing.*

class LocalTextReportProcessor(
    private val summaryBuilder: LocalSummaryBuilder = LocalSummaryBuilder(),
    private val maxBytes: Int = 1024 * 1024
) : ReportProcessor {
    override val name = "On-device"
    override val capabilities = setOf(ProcessingCapability.HTML, ProcessingCapability.PLAIN_TEXT, ProcessingCapability.LABS, ProcessingCapability.CULTURES, ProcessingCapability.SUMMARY)

    override suspend fun parseReport(input: ReportInput): ProcessingResult<ParsedReport> {
        if (input.contentType.contains("pdf", true)) return ProcessingResult.Unsupported("This report format is not yet supported for on-device processing.")
        if (input.bytes.isEmpty()) return ProcessingResult.Unsupported("Empty report response.")
        if (input.bytes.size > maxBytes) return ProcessingResult.Unsupported("Report text is too large for on-device processing.")
        val text = normalize(decode(input.bytes, input.contentType))
        if (looksLikeLogin(text)) return ProcessingResult.Failure("NIMS session appears expired. Login again in the WebView.", "LOCAL_LOGIN_HTML", false)
        if (!looksLikeReport(text)) return ProcessingResult.Unsupported("The report format was not recognized for on-device processing.")
        val labs = LabTextParser.parse(text, input.dateSent)
        val cultures = CultureTextParser.parse(text, input.dateSent)
        if (labs.isEmpty() && cultures.isEmpty()) return ProcessingResult.Unsupported("No high-confidence lab or culture rows were found.")
        val warnings = buildList {
            if (labs.any { it.confidence == ParseConfidence.LOW }) add("Low-confidence lab rows were ignored by summaries.")
            if (input.contentType.contains("html", true)) add("HTML report text was auto-extracted on-device.")
        }
        return ProcessingResult.Success(
            ParsedReport(input.reportId, input.reportName, input.dateSent, input.reportType, labs, cultures, warnings, name),
            name,
            warnings
        )
    }

    override suspend fun summarize(reports: List<ParsedReport>, mode: SummaryMode): ProcessingResult<ProcessingSummary> =
        ProcessingResult.Success(summaryBuilder.build(reports, mode), name)

    private fun decode(bytes: ByteArray, contentType: String): String {
        val raw = bytes.toString(Charsets.UTF_8)
        return if (contentType.contains("html", true) || raw.contains('<')) stripHtml(raw) else raw
    }

    private fun stripHtml(raw: String): String = raw
        .replace(Regex("(?is)<(script|style).*?>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(tr|p|div|li|table)>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&#160;", " ")

    private fun normalize(value: String): String = value.replace('\u00A0', ' ').replace("\r\n", "\n").replace('\r', '\n')
        .lines().joinToString("\n") { it.trim().replace(Regex("[ \\t]{2,}"), " ") }
        .replace(Regex("\n{3,}"), "\n\n").trim()

    private fun looksLikeLogin(text: String): Boolean = listOf("password", "captcha", "login", "session expired", "otp").count { text.contains(it, true) } >= 2
    private fun looksLikeReport(text: String): Boolean = listOf("hemoglobin", "platelet", "creatinine", "sodium", "bilirubin", "culture", "specimen", "organism", "crp", "procalcitonin", "potassium").any { text.contains(it, true) }
}

object LabTextParser {
    private data class Def(val code: String, val display: String, val labels: List<String>)
    private val defs = listOf(
        Def("HB", "Hemoglobin", listOf("Hemoglobin", "Hb")), Def("PLT", "Platelets", listOf("Platelet Count", "Platelets")),
        Def("CREAT", "Creatinine", listOf("Creatinine")), Def("NA", "Sodium", listOf("Sodium")), Def("K", "Potassium", listOf("Potassium")),
        Def("TBIL", "Total Bilirubin", listOf("Total Bilirubin")), Def("DBIL", "Direct Bilirubin", listOf("Direct Bilirubin")),
        Def("SGOT", "SGOT", listOf("SGOT", "AST")), Def("SGPT", "SGPT", listOf("SGPT", "ALT")), Def("CRP", "CRP", listOf("CRP")),
        Def("PCT", "Procalcitonin", listOf("Procalcitonin")), Def("INR", "INR", listOf("INR"))
    )
    fun parse(text: String, date: String?): List<ParsedLabValue> = text.lines().mapNotNull { line -> parseLine(line, date) }
    private fun parseLine(line: String, date: String?): ParsedLabValue? {
        val def = defs.firstOrNull { d -> d.labels.any { line.contains(Regex("(^|\\b)" + Regex.escape(it) + "(\\b|\\s*:)", RegexOption.IGNORE_CASE)) } } ?: return null
        val match = Regex("(?:[:=]?\\s*)([-+]?\\d+(?:\\.\\d+)?)\\s*([a-zA-Z/%µ]+(?:/[a-zA-Z]+)?|lakh/cumm|mmol/L)?", RegexOption.IGNORE_CASE).find(line.substringAfter(def.labels.firstOrNull { line.contains(it, true) } ?: def.display)) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues.getOrNull(2)?.ifBlank { null }
        return ParsedLabValue(def.code, def.display, line.substringBefore(match.value).trim(' ', ':', '=').ifBlank { def.display }, value, null, unit, null, null, Abnormality.UNKNOWN, date, ParseConfidence.HIGH)
    }
}

object CultureTextParser {
    fun parse(text: String, date: String?): List<ParsedCultureValue> {
        if (!text.contains("culture", true) && !text.contains("specimen", true)) return emptyList()
        val noGrowth = Regex("no\\s+growth", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val organism = Regex("(?:organism|isolate)\\s*[:=]\\s*([^\\n]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
        val specimen = Regex("specimen\\s*[:=]\\s*([^\\n]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
        val susceptibility = Regex("([A-Za-z][A-Za-z /-]{2,30})\\s+((?:Sensitive|Susceptible|Intermediate|Resistant))", RegexOption.IGNORE_CASE)
            .findAll(text).map { AntibioticResult(it.groupValues[1].trim(), it.groupValues[2].trim(), ParseConfidence.MEDIUM) }.toList()
        val markers = listOf("ESBL", "MRSA", "VRE", "CRE", "CRAB", "Carbapenem resistant", "Colistin resistant").filter { text.contains(it, true) }.toSet()
        if (!noGrowth && organism.isNullOrBlank() && susceptibility.isEmpty() && markers.isEmpty()) return emptyList()
        return listOf(ParsedCultureValue(specimen, null, date, organism, if (noGrowth) GrowthStatus.NO_GROWTH else GrowthStatus.GROWTH_DETECTED, susceptibility, markers, emptyList(), ParseConfidence.HIGH))
    }
}
