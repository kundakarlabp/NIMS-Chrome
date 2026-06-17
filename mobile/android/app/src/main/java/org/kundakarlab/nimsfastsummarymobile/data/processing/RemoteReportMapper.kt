package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.json.JSONArray
import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.domain.model.*

object RemoteReportMapper {
    fun toParsedReport(response: JSONObject, fallbackInput: ReportInput, processorName: String): Pair<ParsedReport, List<String>> {
        val warnings = mutableListOf<String>()
        warnings += strings(response.optJSONArray("errors"))
        warnings += strings(response.optJSONArray("warnings"))
        val labs = parseLabs(response.optJSONArray("parameters"), warnings)
        val cultures = parseCultures(response.optJSONArray("culture_results"), warnings)
        response.optJSONObject("culture")?.let { cultureObject ->
            parseCultures(JSONArray().put(cultureObject), warnings).let { if (it.isNotEmpty()) return@let }
        }
        val report = ParsedReport(
            reportId = response.optString("report_id", fallbackInput.reportId),
            reportName = response.optString("report_name", fallbackInput.reportName),
            dateSent = response.optString("date_sent", fallbackInput.dateSent),
            reportType = response.optString("report_type", fallbackInput.reportType),
            labs = labs,
            cultures = cultures,
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
                val name = firstNonBlank(row, "canonical_name", "name", "parameter")
                if (name.isBlank()) { warnings += "Skipped remote parameter without a name."; continue }
                val numeric = row.optDoubleOrNull("value") ?: row.optDoubleOrNull("numeric_value")
                val text = if (numeric == null) firstNonBlank(row, "value", "text_value") else null
                add(ParsedLabValue(
                    canonicalCode = firstNonBlank(row, "canonical_code", "canonical_name", fallback = name).uppercase(),
                    displayName = firstNonBlank(row, "name", "display_name", fallback = name),
                    sourceName = firstNonBlank(row, "source_name", "name", fallback = name),
                    numericValue = numeric,
                    textValue = text,
                    unit = row.optString("unit").ifBlank { null },
                    referenceLow = row.optDoubleOrNull("reference_low"),
                    referenceHigh = row.optDoubleOrNull("reference_high"),
                    abnormality = abnormality(row.optString("abnormality")),
                    resultDate = firstNonBlank(row, "date", "result_date"),
                    confidence = ParseConfidence.MEDIUM
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
                val status = firstNonBlank(row, "status", "result", "growth").lowercase()
                val organism = firstNonBlank(row, "organism", "growth").ifBlank { null }
                if (status.isBlank() && organism == null) { warnings += "Skipped remote culture without status or organism."; continue }
                add(ParsedCultureValue(
                    specimen = firstNonBlank(row, "specimen", "site_specimen").ifBlank { null },
                    site = firstNonBlank(row, "site", "site_specimen").ifBlank { null },
                    collectionDate = firstNonBlank(row, "collection_date", "date_sent", "reporting_date").ifBlank { null },
                    organism = organism,
                    growthStatus = when {
                        status.contains("no growth") -> GrowthStatus.NO_GROWTH
                        organism != null || status.contains("positive") || status.contains("growth") -> GrowthStatus.GROWTH_DETECTED
                        else -> GrowthStatus.UNKNOWN
                    },
                    susceptibility = emptyList(),
                    explicitResistanceMarkers = emptySet(),
                    comments = listOf(row.optString("comment")).filter { it.isNotBlank() },
                    confidence = ParseConfidence.MEDIUM
                ))
            }
        }
    }

    private fun strings(values: JSONArray?): List<String> = buildList { if (values != null) for (i in 0 until values.length()) values.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key)) optDouble(key).takeIf { !it.isNaN() } else null
    private fun firstNonBlank(row: JSONObject, vararg keys: String, fallback: String = ""): String = keys.firstNotNullOfOrNull { row.optString(it).takeIf(String::isNotBlank) } ?: fallback
    private fun abnormality(value: String): Abnormality = when (value.lowercase()) { "high" -> Abnormality.HIGH; "low" -> Abnormality.LOW; "critical" -> Abnormality.CRITICAL; "normal" -> Abnormality.NORMAL; else -> Abnormality.UNKNOWN }
}
