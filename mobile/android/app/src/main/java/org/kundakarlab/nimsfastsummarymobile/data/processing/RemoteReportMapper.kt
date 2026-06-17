package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.json.JSONArray
import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.domain.model.*

object RemoteReportMapper {
    private val resistanceMarkerPatterns = linkedMapOf(
        "ESBL" to Regex("""\bESBL\b""", RegexOption.IGNORE_CASE), "MRSA" to Regex("""\bMRSA\b""", RegexOption.IGNORE_CASE),
        "VRE" to Regex("""\bVRE\b""", RegexOption.IGNORE_CASE), "CRE" to Regex("""\bCRE\b""", RegexOption.IGNORE_CASE), "CRAB" to Regex("""\bCRAB\b""", RegexOption.IGNORE_CASE)
    )

    fun toParsedReport(response: JSONObject, fallbackInput: ReportInput, processorName: String): Pair<ParsedReport, List<String>> {
        val warnings = mutableListOf<String>()
        warnings += strings(response.optJSONArray("errors"))
        warnings += strings(response.optJSONArray("warnings"))
        val cultures = mutableListOf<ParsedCultureValue>()
        cultures += parseCultures(response.optJSONArray("culture_results"), warnings)
        response.optJSONObject("culture")?.let { cultures += parseCultures(JSONArray().put(it), warnings) }
        val uniqueCultures = cultures.distinctBy { listOf(it.collectionDate.orEmpty(), it.specimen.orEmpty(), it.site.orEmpty(), it.organism.orEmpty(), it.growthStatus.name).joinToString("|") }
        val report = ParsedReport(
            reportId = response.optString("report_id", fallbackInput.reportId),
            reportName = response.optString("report_name", fallbackInput.reportName),
            dateSent = response.optString("date_sent", fallbackInput.dateSent),
            reportType = response.optString("report_type", fallbackInput.reportType),
            labs = parseLabs(response.optJSONArray("parameters"), warnings),
            cultures = uniqueCultures,
            warnings = warnings.distinct(),
            processorName = processorName
        )
        return report to warnings.distinct()
    }

    private fun parseLabs(rows: JSONArray?, warnings: MutableList<String>): List<ParsedLabValue> {
        if (rows == null) return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index)
                if (row == null) { warnings += "Skipped malformed remote parameter row."; continue }
                val sourceName = firstNonBlank(row, "name", "source_name", "parameter")
                if (sourceName.isBlank()) { warnings += "Skipped remote parameter without a name."; continue }
                val canonicalName = firstNonBlank(row, "canonical_name", "canonical_code", fallback = sourceName)
                val parsedValue = parseRemoteValue(row.opt("value"))
                val referenceRange = firstNonBlank(row, "reference_range")
                val (low, high) = parseReferenceRange(referenceRange)
                add(ParsedLabValue(
                    canonicalCode = CanonicalLabCodes.normalize(canonicalName),
                    displayName = sourceName,
                    sourceName = sourceName,
                    numericValue = parsedValue.numericValue,
                    textValue = parsedValue.textValue,
                    unit = row.optString("unit").ifBlank { null },
                    referenceLow = low,
                    referenceHigh = high,
                    referenceRangeText = referenceRange.ifBlank { null },
                    abnormality = abnormality(firstNonBlank(row, "abnormal_flag", "abnormality")),
                    resultDate = firstNonBlank(row, "date_sent", "result_date", "date"),
                    confidence = ParseConfidence.MEDIUM,
                    comparator = parsedValue.comparator
                ))
            }
        }
    }

    private fun parseCultures(rows: JSONArray?, warnings: MutableList<String>): List<ParsedCultureValue> {
        if (rows == null) return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index)
                if (row == null) { warnings += "Skipped malformed remote culture row."; continue }
                parseCulture(row, warnings)?.let(::add)
            }
        }
    }

    private fun parseCulture(row: JSONObject, warnings: MutableList<String>): ParsedCultureValue? {
        val organisms = strings(row.optJSONArray("organisms"))
        val organism = firstNonBlank(row, "organism", "growth").ifBlank { organisms.joinToString("; ") }.ifBlank { null }
        if (organisms.size > 1) warnings += "Multiple organisms were combined in a remote culture row."
        val statusText = firstNonBlank(row, "result_status", "result", "status", "report_status", "growth").lowercase()
        val comments = listOf(firstNonBlank(row, "comment", "sample_processed")).filter { it.isNotBlank() }
        val growthStatus = when {
            statusText.contains("pending") -> GrowthStatus.PENDING
            statusText.contains("contaminant") -> { warnings += "Remote culture marked possible contaminant."; GrowthStatus.UNKNOWN }
            statusText.contains("no_growth") || statusText.contains("no growth") || statusText == "negative" -> GrowthStatus.NO_GROWTH
            statusText.contains("positive") || organism != null -> GrowthStatus.GROWTH_DETECTED
            statusText.isBlank() -> GrowthStatus.UNKNOWN
            else -> GrowthStatus.UNKNOWN
        }
        if (organism == null && growthStatus == GrowthStatus.UNKNOWN && comments.isEmpty()) { warnings += "Skipped remote culture without status or organism."; return null }
        val explicitText = listOf(statusText, organism.orEmpty(), comments.joinToString(" ")).joinToString(" ")
        return ParsedCultureValue(
            specimen = firstNonBlank(row, "specimen", "site_specimen", "sample_processed").ifBlank { null },
            site = firstNonBlank(row, "site", "site_specimen").ifBlank { null },
            collectionDate = firstNonBlank(row, "collection_date", "date_sent", "reporting_date").ifBlank { null },
            organism = organism,
            growthStatus = growthStatus,
            susceptibility = antibioticResults(row),
            explicitResistanceMarkers = resistanceMarkerPatterns.filterValues { it.containsMatchIn(explicitText) }.keys.toSet(),
            comments = comments,
            confidence = ParseConfidence.MEDIUM
        )
    }

    private fun antibioticResults(row: JSONObject): List<AntibioticResult> {
        val values = mutableListOf<AntibioticResult>()
        values += strings(row.optJSONArray("susceptible_antibiotics")).map { AntibioticResult(it, "Susceptible", ParseConfidence.HIGH) }
        values += strings(row.optJSONArray("resistant_antibiotics")).map { AntibioticResult(it, "Resistant", ParseConfidence.HIGH) }
        values += strings(row.optJSONArray("intermediate_antibiotics")).map { AntibioticResult(it, "Intermediate", ParseConfidence.HIGH) }
        row.optJSONObject("sensitivity_summary")?.let { summary ->
            values += strings(summary.optJSONArray("sensitive")).map { AntibioticResult(it, "Susceptible", ParseConfidence.HIGH) }
            values += strings(summary.optJSONArray("susceptible")).map { AntibioticResult(it, "Susceptible", ParseConfidence.HIGH) }
            values += strings(summary.optJSONArray("resistant")).map { AntibioticResult(it, "Resistant", ParseConfidence.HIGH) }
            values += strings(summary.optJSONArray("intermediate")).map { AntibioticResult(it, "Intermediate", ParseConfidence.HIGH) }
        }
        return values.distinctBy { "${it.antibiotic.trim().lowercase()}|${it.interpretation.lowercase()}" }
    }

    data class ParsedRemoteValue(val numericValue: Double?, val textValue: String?, val comparator: NumericComparator)
    private fun parseRemoteValue(value: Any?): ParsedRemoteValue {
        val text = value?.toString().orEmpty().trim()
        val match = Regex("^([<>])?\\s*([-+]?[0-9]+(?:\\.[0-9]+)?)$").find(text)
        if (match != null) {
            return ParsedRemoteValue(match.groupValues[2].toDoubleOrNull(), null, when (match.groupValues[1]) { "<" -> NumericComparator.LESS_THAN; ">" -> NumericComparator.GREATER_THAN; else -> NumericComparator.EQUAL })
        }
        return ParsedRemoteValue(null, text.ifBlank { null }, NumericComparator.EQUAL)
    }
    private fun parseReferenceRange(value: String): Pair<Double?, Double?> {
        val match = Regex("([-+]?[0-9]+(?:\\.[0-9]+)?)\\s*[-–]\\s*([-+]?[0-9]+(?:\\.[0-9]+)?)").find(value)
        return match?.let { it.groupValues[1].toDoubleOrNull() to it.groupValues[2].toDoubleOrNull() } ?: (null to null)
    }
    private fun strings(values: JSONArray?): List<String> = buildList { if (values != null) for (i in 0 until values.length()) values.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
    private fun firstNonBlank(row: JSONObject, vararg keys: String, fallback: String = ""): String = keys.firstNotNullOfOrNull { row.optString(it).takeIf(String::isNotBlank) } ?: fallback
    private fun abnormality(value: String): Abnormality = when (value.lowercase()) { "high" -> Abnormality.HIGH; "low" -> Abnormality.LOW; "critical" -> Abnormality.CRITICAL; "normal" -> Abnormality.NORMAL; else -> Abnormality.UNKNOWN }
}
