package org.kundakarlab.nimsfastsummarymobile.ui.mappers

import org.json.JSONArray
import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.ui.models.Abnormality
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiCultureRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiLabTrendRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSourceReport
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary

object SummaryJsonMapper {
    fun parseSummaryJsonToUiSummary(summary: JSONObject, editableNote: String = ""): UiSummary = UiSummary(
        sourceReports = sourceReports(summary.optJSONArray("source_reports")),
        labTrends = labTrends(summary.optJSONObject("lab_trend_table")),
        cultures = cultures(summary.optJSONArray("culture_table")),
        interpretation = strings(summary.optJSONArray("interpretation")),
        editableNote = editableNote,
        rawJson = summary
    )

    private fun sourceReports(rows: JSONArray?): List<UiSourceReport> {
        if (rows == null) return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val status = row.optString("status", "unknown")
                add(UiSourceReport(
                    dateSent = row.optString("date_sent"),
                    reportName = row.optString("report_name", "Unnamed report"),
                    type = row.optString("type", row.optString("report_type", "other")),
                    status = status,
                    notes = row.optString("notes"),
                    hasError = status.equals("error", true) || status.equals("unsupported", true),
                    rawText = row.optString("raw_text")
                ))
            }
        }
    }

    private fun labTrends(table: JSONObject?): List<UiLabTrendRow> {
        if (table == null) return emptyList()
        val columns = strings(table.optJSONArray("columns"))
        val rows = table.optJSONArray("rows") ?: return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val values = stringsPreserveBlanks(row.optJSONArray("values"))
                if (values.size != columns.size) continue
                val history = columns.zip(values).filter { it.second.isNotBlank() }
                val latest = history.firstOrNull()
                val previous = history.drop(1).firstOrNull()
                add(UiLabTrendRow(
                    parameter = row.optString("parameter", "Parameter"),
                    latestValue = latest?.second.orEmpty(), latestDate = latest?.first.orEmpty(),
                    previousValue = previous?.second, previousDate = previous?.first,
                    trendText = row.optString("trend", "insufficient data"),
                    abnormality = abnormality(latest?.second.orEmpty()), history = history
                ))
            }
        }
    }

    private fun cultures(rows: JSONArray?): List<UiCultureRow> {
        if (rows == null) return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                add(UiCultureRow(
                    collectionDate = firstNonBlank(row, "collection_date", "date_sent", "reporting_date"),
                    cultureNo = firstNonBlank(row, "lab_study_number", "culture_no", "culture_number", "specimen_no"),
                    specimen = firstNonBlank(row, "specimen", "sample_type", "site_specimen", "specimen_no"),
                    site = firstNonBlank(row, "site", "bottle_name", "site_specimen"),
                    organism = firstNonBlank(row, "organism", "organism_name", "growth"),
                    growth = firstNonBlank(row, "growth", "growth_quantity", "result"),
                    status = firstNonBlank(row, "result", "status", "report_status", "reporting_status", fallback = "unknown"),
                    sensitivitySummary = susceptibilitySummary(row).ifBlank { sirSummary(row).ifBlank { stringField(row, "sensitivity_summary") } },
                    comment = cultureComment(row),
                    sourceReportName = row.optString("report_name")
                ))
            }
        }
    }

    private fun susceptibilitySummary(row: JSONObject): String {
        val values = row.optJSONArray("susceptibility") ?: return ""
        return buildList {
            for (index in 0 until values.length()) {
                val result = values.optJSONObject(index) ?: continue
                val drug = result.optString("antibiotic").trim()
                val interpretation = result.optString("interpretation").trim()
                if (drug.isBlank() || interpretation.isBlank()) continue
                val micValue = result.opt("mic_value").takeUnless { it == null || it == JSONObject.NULL }?.toString().orEmpty()
                val mic = if (micValue.isBlank()) "" else " (MIC ${result.optString("mic_comparator")}$micValue ${result.optString("mic_unit")})"
                add("$drug $interpretation$mic".trim())
            }
        }.joinToString("; ")
    }

    private fun sirSummary(row: JSONObject): String {
        val summaryObject = row.optJSONObject("sensitivity_summary")
        val susceptible = valuesFromKeys(row, "sensitive", "susceptible", "sensitive_drugs", "susceptible_antibiotics") + valuesFromKeys(summaryObject, "sensitive", "susceptible")
        val intermediate = valuesFromKeys(row, "intermediate", "intermediate_drugs", "intermediate_antibiotics") + valuesFromKeys(summaryObject, "intermediate")
        val resistant = valuesFromKeys(row, "resistant", "resistant_drugs", "resistant_antibiotics") + valuesFromKeys(summaryObject, "resistant")
        return listOf("S" to susceptible, "I" to intermediate, "R" to resistant).mapNotNull { (label, values) ->
            values.distinctBy { it.lowercase() }.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "$label: ")
        }.joinToString("; ")
    }

    private fun valuesFromKeys(row: JSONObject?, vararg keys: String): List<String> {
        if (row == null) return emptyList()
        return buildList { for (key in keys) addAll(stringValues(row.opt(key))) }
    }

    private fun stringValues(value: Any?): List<String> = when (value) {
        null, JSONObject.NULL -> emptyList()
        is JSONArray -> strings(value)
        is String -> value.split(',', ';', '\n', '\r', '|').map(String::trim).filter(String::isNotBlank)
        else -> value.toString().trim().takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
    }

    private fun cultureComment(row: JSONObject): String {
        val notes = buildList {
            firstNonBlank(row, "comment", "microbiology_note", "note").takeIf { it.isNotBlank() }?.let(::add)
            firstNonBlank(row, "report_stage", "reporting_status", "report_status").takeIf { it.isNotBlank() && it != "unspecified" }?.let { add("Stage: $it") }
            firstNonBlank(row, "bottle_name").takeIf { it.isNotBlank() }?.let { add("Bottle: $it") }
            intField(row, "set_number")?.let { add("Set: $it") }
            intField(row, "bottle_number")?.let { add("Bottle no: $it") }
            intField(row, "isolate_number")?.let { add("Isolate: $it") }
            firstNonBlank(row, "gram_stain").takeIf { it.isNotBlank() }?.let { add("Gram stain: $it") }
            firstNonBlank(row, "reporting_date").takeIf { it.isNotBlank() }?.let { add("Reported: $it") }
            if (booleanField(row, "clinical_review_flag")) add("Clinical-significance review required; verify with source report and bedside context.")
        }
        return notes.distinct().joinToString(" | ")
    }

    private fun intField(row: JSONObject, key: String): Int? = when (val value = row.opt(key)) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private fun booleanField(row: JSONObject, key: String): Boolean = when (val value = row.opt(key)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.trim().lowercase() in setOf("true", "1", "yes", "y")
        else -> false
    }

    private fun strings(values: JSONArray?): List<String> {
        if (values == null) return emptyList()
        return buildList { for (index in 0 until values.length()) if (!values.isNull(index)) values.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add) }
    }

    private fun stringsPreserveBlanks(values: JSONArray?): List<String> {
        if (values == null) return emptyList()
        return buildList { for (index in 0 until values.length()) add(if (values.isNull(index)) "" else values.optString(index)) }
    }

    private fun stringField(row: JSONObject, key: String): String = (row.opt(key) as? String)?.trim().orEmpty()

    private fun abnormality(value: String): Abnormality {
        val lower = value.lowercase()
        return when {
            "critical" in lower -> Abnormality.CRITICAL
            "[high]" in lower || " high" in lower -> Abnormality.HIGH
            "[low]" in lower || " low" in lower -> Abnormality.LOW
            value.isBlank() -> Abnormality.UNKNOWN
            "[normal]" in lower -> Abnormality.NORMAL
            else -> Abnormality.UNKNOWN
        }
    }

    private fun firstNonBlank(row: JSONObject, vararg keys: String, fallback: String = ""): String {
        for (key in keys) {
            val value = row.optString(key).trim()
            if (value.isNotBlank() && !value.equals("null", true)) return value
        }
        return fallback
    }
}
