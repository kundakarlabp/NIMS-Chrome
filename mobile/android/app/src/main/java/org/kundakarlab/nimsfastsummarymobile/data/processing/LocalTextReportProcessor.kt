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
        if (input.contentType.contains("pdf", true) || input.bytes.take(4).toByteArray().contentEquals("%PDF".toByteArray())) return ProcessingResult.Unsupported("PDF local parsing is not yet supported. Open the source report manually.")
        if (input.bytes.isEmpty()) return ProcessingResult.Failure("Empty report response.", "LOCAL_EMPTY_RESPONSE", false)
        if (input.bytes.size > maxBytes) return ProcessingResult.Failure("Report text is too large for on-device processing.", "LOCAL_OVERSIZED_TEXT", false)
        val text = normalize(decode(input.bytes, input.contentType))
        val securityCode = securityPageCode(text)
        if (securityCode != null) return ProcessingResult.Failure("NIMS session appears expired. Login again in the WebView.", securityCode, false)
        if (!looksLikeReport(text)) return ProcessingResult.Unsupported("The report format was not recognized for on-device processing.")
        val labs = LabTextParser.parse(text, input.dateSent)
        val cultures = CultureTextParser.parse(text, input.dateSent)
        if (labs.isEmpty() && cultures.isEmpty()) return ProcessingResult.Failure("No high-confidence lab or culture rows were found.", "LOCAL_PARSE_INCOMPLETE", true)
        val warnings = buildList { if (input.contentType.contains("html", true)) add("HTML report text was auto-extracted on-device.") }
        return ProcessingResult.Success(ParsedReport(input.reportId, input.reportName, input.dateSent, input.reportType, labs, cultures, warnings, name), name, warnings)
    }

    override suspend fun summarize(reports: List<ParsedReport>, mode: SummaryMode): ProcessingResult<ProcessingSummary> = ProcessingResult.Success(summaryBuilder.build(reports, mode), name)

    private fun decode(bytes: ByteArray, contentType: String): String {
        val raw = bytes.toString(Charsets.UTF_8)
        return if (contentType.contains("html", true) || raw.trimStart().startsWith("<html", true) || raw.trimStart().startsWith("<!doctype", true)) stripHtml(raw) else raw
    }
    private fun stripHtml(raw: String): String = raw.replace(Regex("(?is)<(script|style).*?>.*?</\\1>"), " ").replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("(?i)</(tr|p|div|li|table)>"), "\n").replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#160;", " ")
    private fun normalize(value: String): String = value.replace('\u00A0', ' ').replace("\r\n", "\n").replace('\r', '\n').lines().joinToString("\n") { it.trim().replace(Regex("[ \\t]{2,}"), " ") }.replace(Regex("\n{3,}"), "\n\n").trim()
    private fun securityPageCode(text: String): String? = when {
        text.contains("captcha", true) -> "LOCAL_CAPTCHA_PAGE"
        text.contains("otp", true) -> "LOCAL_OTP_PAGE"
        text.contains("session expired", true) -> "LOCAL_SESSION_EXPIRED"
        listOf("password", "login").all { text.contains(it, true) } -> "LOCAL_LOGIN_HTML"
        else -> null
    }
    private fun looksLikeReport(text: String): Boolean = listOf("hemoglobin", "platelet", "creatinine", "sodium", "bilirubin", "culture", "specimen", "organism", "crp", "procalcitonin", "potassium", "wbc", "urea").any { text.contains(it, true) }
}

data class LabDefinition(val canonicalCode: String, val displayName: String, val labelPatterns: List<Regex>, val compatibleUnits: Set<String>, val numericRangeGuard: ClosedFloatingPointRange<Double>? = null)

object LabTextParser {
    private val defs = listOf(
        LabDefinition("HB", "Hemoglobin", labels("Hemoglobin", "Hb"), setOf("g/dL", "gm%"), 0.0..30.0),
        LabDefinition("WBC", "Total WBC", labels("Total WBC", "TLC", "WBC"), setOf("/cumm", "cells/cumm", "/µL"), 0.0..500000.0),
        LabDefinition("NEUT", "Neutrophils", labels("Neutrophils"), setOf("%"), 0.0..100.0),
        LabDefinition("LYMPH", "Lymphocytes", labels("Lymphocytes"), setOf("%"), 0.0..100.0),
        LabDefinition("RBC", "RBC Count", labels("RBC Count", "RBC"), setOf("million/cumm", "10^6/µL", "x10^6/uL"), 0.0..10.0),
        LabDefinition("HCT", "Hematocrit", labels("Hematocrit", "PCV"), setOf("%"), 0.0..80.0),
        LabDefinition("MCV", "MCV", labels("MCV"), setOf("fL"), 40.0..140.0),
        LabDefinition("MCH", "MCH", labels("MCH"), setOf("pg"), 5.0..50.0),
        LabDefinition("MCHC", "MCHC", labels("MCHC"), setOf("g/dL"), 10.0..50.0),
        LabDefinition("PLT", "Platelets", labels("Platelet Count", "Platelets"), setOf("lakh/cumm", "/cumm", "cells/cumm", "/µL"), 0.0..2000000.0),
        LabDefinition("UREA", "Urea", labels("Urea"), setOf("mg/dL"), 0.0..400.0),
        LabDefinition("CREAT", "Creatinine", labels("Creatinine"), setOf("mg/dL"), 0.0..50.0),
        LabDefinition("EGFR", "eGFR", labels("eGFR", "GFR"), setOf("mL/min", "ml/min/1.73m2"), 0.0..200.0),
        LabDefinition("NA", "Sodium", labels("Sodium"), setOf("mmol/L", "mEq/L"), 80.0..200.0),
        LabDefinition("K", "Potassium", labels("Potassium"), setOf("mmol/L", "mEq/L"), 1.0..10.0),
        LabDefinition("CL", "Chloride", labels("Chloride"), setOf("mmol/L", "mEq/L"), 50.0..150.0),
        LabDefinition("HCO3", "Bicarbonate", labels("Bicarbonate"), setOf("mmol/L", "mEq/L"), 1.0..60.0),
        LabDefinition("TBIL", "Total Bilirubin", labels("Total Bilirubin"), setOf("mg/dL"), 0.0..80.0),
        LabDefinition("DBIL", "Direct Bilirubin", labels("Direct Bilirubin"), setOf("mg/dL"), 0.0..50.0),
        LabDefinition("AST", "AST/SGOT", labels("AST", "SGOT"), setOf("U/L"), 0.0..20000.0),
        LabDefinition("ALT", "ALT/SGPT", labels("ALT", "SGPT"), setOf("U/L"), 0.0..20000.0),
        LabDefinition("ALP", "ALP", labels("ALP"), setOf("U/L"), 0.0..5000.0),
        LabDefinition("ALB", "Albumin", labels("Albumin"), setOf("g/dL"), 0.0..10.0),
        LabDefinition("GGT", "GGT", labels("GGT", "Gamma GT"), setOf("U/L"), 0.0..5000.0),
        LabDefinition("TP", "Total Protein", labels("Total Protein"), setOf("g/dL"), 0.0..15.0),
        LabDefinition("CRP", "CRP", labels("CRP"), setOf("mg/L", "mg/dL"), 0.0..1000.0),
        LabDefinition("PCT", "Procalcitonin", labels("Procalcitonin", "PCT"), setOf("ng/mL"), 0.0..1000.0),
        LabDefinition("PT", "PT", labels("PT"), setOf("sec", "seconds"), 0.0..200.0),
        LabDefinition("INR", "INR", labels("INR"), emptySet(), 0.0..20.0),
        LabDefinition("APTT", "aPTT", labels("aPTT", "APTT"), setOf("sec", "seconds"), 0.0..300.0)
    )
    private fun labels(vararg values: String) = values.map { Regex("(^|[^A-Za-z0-9])${Regex.escape(it)}([^A-Za-z0-9]|\\s*:|$)", RegexOption.IGNORE_CASE) }
    fun parse(text: String, date: String?): List<ParsedLabValue> = text.lines().mapNotNull { parseLine(it, date) }
    private fun parseLine(line: String, date: String?): ParsedLabValue? {
        val def = defs.firstOrNull { d -> d.labelPatterns.any { it.find(line) != null } } ?: return null
        val labelMatch = def.labelPatterns.firstNotNullOfOrNull { it.find(line) } ?: return null
        val before = line.substring(0, labelMatch.range.first)
        val remaining = line.substring(labelMatch.range.last + 1)
        val result = Regex("[:=]?\\s*([<>])?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([a-zA-Z0-9/%µ.^]+(?:/[a-zA-Z0-9.µ]+)?|lakh/cumm|cells/cumm|million/cumm|mmol/L|mEq/L|ng/mL|mg/L|mg/dL|g/dL|gm%|U/L|mL/min|sec|seconds)?", RegexOption.IGNORE_CASE).find(remaining) ?: return null
        val value = result.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
        val unit = result.groupValues.getOrNull(3)?.ifBlank { null }
        val comparator = when (result.groupValues[1]) { "<" -> NumericComparator.LESS_THAN; ">" -> NumericComparator.GREATER_THAN; else -> NumericComparator.EQUAL }
        val numbersBefore = Regex("\\d").containsMatchIn(before)
        val compatible = unit == null || def.compatibleUnits.isEmpty() || def.compatibleUnits.any { it.equals(unit, true) }
        val inRange = def.numericRangeGuard?.contains(value) ?: true
        val confidence = when {
            !compatible || !inRange || numbersBefore -> ParseConfidence.LOW
            unit == null -> ParseConfidence.MEDIUM
            else -> ParseConfidence.HIGH
        }
        return ParsedLabValue(CanonicalLabCodes.normalize(def.canonicalCode), def.displayName, line.substring(labelMatch.range.first, labelMatch.range.last + 1).trim(' ', ':'), value, null, unit, null, null, null, Abnormality.UNKNOWN, date, confidence, comparator)
    }
}

object CultureTextParser {
    private val resistanceMarkerPatterns = linkedMapOf(
        "ESBL" to Regex("""\bESBL\b""", RegexOption.IGNORE_CASE), "MRSA" to Regex("""\bMRSA\b""", RegexOption.IGNORE_CASE),
        "VRE" to Regex("""\bVRE\b""", RegexOption.IGNORE_CASE), "CRE" to Regex("""\bCRE\b""", RegexOption.IGNORE_CASE),
        "CRAB" to Regex("""\bCRAB\b""", RegexOption.IGNORE_CASE),
        "Carbapenem resistant" to Regex("""\bcarbapenem(?:[-\s]+)resistant\b""", RegexOption.IGNORE_CASE),
        "Colistin resistant" to Regex("""\bcolistin(?:[-\s]+)resistant\b""", RegexOption.IGNORE_CASE)
    )
    fun parse(text: String, date: String?): List<ParsedCultureValue> {
        if (!text.contains("culture", true) && !text.contains("specimen", true) && !text.contains("organism", true) && resistanceMarkerPatterns.values.none { it.containsMatchIn(text) }) return emptyList()
        return splitBlocks(text).mapNotNull { parseBlock(it, date) }.distinctBy { listOf(it.specimen, it.organism, it.growthStatus, it.collectionDate).joinToString("|") }
    }
    private fun splitBlocks(text: String): List<String> {
        val blocks = mutableListOf<String>(); val current = StringBuilder()
        text.lines().forEach { line ->
            val starts = Regex("^(specimen|sample|(?:blood|urine)?\\s*culture|organism|isolate|result)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line.trim())
            if (starts && current.isNotBlank()) { blocks += current.toString(); current.clear() }
            current.appendLine(line)
        }
        if (current.isNotBlank()) blocks += current.toString()
        return if (blocks.isEmpty()) listOf(text) else blocks
    }
    private fun parseSusceptibility(block: String): List<AntibioticResult> {
        val wordRows = Regex("([A-Za-z][A-Za-z /-]{2,30})\\s+((?:Sensitive|Susceptible|Intermediate|Resistant))", RegexOption.IGNORE_CASE)
            .findAll(block).map { AntibioticResult(it.groupValues[1].trim(), it.groupValues[2].trim(), ParseConfidence.MEDIUM) }
        val sirRows = Regex("([A-Za-z][A-Za-z /-]{2,30})\\s+(S|I|R)\\b", RegexOption.IGNORE_CASE)
            .findAll(block).map { match ->
                val interpretation = when (match.groupValues[2].uppercase()) { "S" -> "Susceptible"; "I" -> "Intermediate"; else -> "Resistant" }
                AntibioticResult(match.groupValues[1].trim(), interpretation, ParseConfidence.MEDIUM)
            }
        return (wordRows + sirRows).distinctBy { it.antibiotic.lowercase() to it.interpretation.lowercase() }.toList()
    }

    private fun parseBlock(block: String, date: String?): ParsedCultureValue? {
        val noGrowth = Regex("\\bno\\s+growth\\b", RegexOption.IGNORE_CASE).containsMatchIn(block)
        val organism = Regex("(?:organism|isolate)\\s*[:=]\\s*([^\\n]+)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.trim()
            ?: Regex("growth\\s+of\\s+([^\\n]+)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.trim()
        val specimen = Regex("(?:specimen|sample)\\s*[:=]\\s*([^\\n]+)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.trim()
        val susceptibility = parseSusceptibility(block)
        val markers = resistanceMarkerPatterns.filter { (marker, pattern) -> pattern.containsMatchIn(block) && !isNegatedMarker(block, marker) }.keys.toSet()
        val explicitGrowth = Regex("\\b(growth\\s+of|positive|isolated)\\b", RegexOption.IGNORE_CASE).containsMatchIn(block)
        if (!noGrowth && organism.isNullOrBlank() && !explicitGrowth && markers.isEmpty()) return null
        if (!noGrowth && organism.isNullOrBlank() && !explicitGrowth) {
            return ParsedCultureValue(specimen, null, date, null, GrowthStatus.UNKNOWN, susceptibility, markers, emptyList(), ParseConfidence.LOW)
        }
        return ParsedCultureValue(specimen, null, date, organism, if (noGrowth) GrowthStatus.NO_GROWTH else GrowthStatus.GROWTH_DETECTED, susceptibility, markers, emptyList(), ParseConfidence.HIGH)
    }

    private fun isNegatedMarker(block: String, marker: String): Boolean {
        val token = Regex.escape(marker)
        val negation = Regex("\\b(?:no|not)\\s+$token\\b|\\b$token\\s+(?:negative|not\\s+detected)\\b", RegexOption.IGNORE_CASE)
        return negation.containsMatchIn(block)
    }
}
