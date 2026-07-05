package org.kundakarlab.nimsfastsummarymobile.ui.mappers

import org.json.JSONArray
import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.ui.models.Abnormality
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiCultureRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiLabTrendRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSourceReport
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary

object SummaryJsonMapper {
    fun parseSummaryJsonToUiSummary(summary: JSONObject, editableNote: String = ""): UiSummary {
        return UiSummary(
            sourceReports = sourceReports(summary.optJSONArray("source_reports")),
            labTrends = labTrends(summary.optJSONObject("lab_trend_table")),
            cultures = cultures(summary.optJSONArray("culture_table")),
            interpretation = strings(summary.optJSONArray("interpretation")),
            editableNote = editableNote,
            rawJson = summary
        )
    }

    private fun sourceReports(rows: JSONArray?): List<UiSourceReport> {
        if (rows == null) return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val status = row.optString("status", "unknown")
                val notes = row.optString("notes")
                add(UiSourceReport(row.optString("date_sent"), row.optString("report_name", "Unnamed report"), row.optString("type", row.optString("report_type", "other")), status, notes, status.equals("error", true) || status.equals("unsupported", true), row.optString("raw_text")))
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
                add(UiLabTrendRow(row.optString("parameter", "Parameter"), latest?.second.orEmpty(), latest?.first.orEmpty(), previous?.second, previous?.first, row.optString("trend", "insufficient data"), abnormality(latest?.second.orEmpty()), history))
            }
        }
    }

    private fun cultures(rows: JSONArray?): List<UiCultureRow> {
        if (rows == null) return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                add(
                    UiCultureRow(
                        collectionDate = firstNonBlank(row, "collection_date", "date_sent", "reporting_date"),
                        cultureNo = firstNonBlank(row, "culture_no", "culture_number", "specimen_no", "isolate_number"),
                        specimen = firstNonBlank(row, "specimen", "sample_type", "site_specimen", "specimen_no"),
                        site = firstNonBlank(row, "site", "bottle_name", "site_specimen"),
                        organism = firstNonBlank(row, "organism", "organism_name", "growth"),
                        growth = firstNonBlank(row, "growth", "growth_quantity", "result"),
                        status = firstNonBlank(row, "result", "status", "report_status", "reporting_status", fallback = "unknown"),
                        sensitivitySummary = sirSummary(row).ifBlank { row.optString("sensitivity_summary") },
                        comment = cultureComment(row),
                        sourceReportName = row.optString("report_name")
                    )
                )
            }
        }
    }

    private fun sirSummary(row: JSONObject): String {
        val summaryObject = row.optJSONObject("sensitivity_summary")
        val susceptible = strings(row.optJSONArray("sensitive")) + strings(row.optJSONArray("susceptible")) + strings(row.optJSONArray("sensitive_drugs")) + strings(row.optJSONArray("susceptible_antibiotics")) + strings(summaryObject?.optJSONArray("sensitive")) + strings(summaryObject?.optJSONArray("susceptible"))
        val intermediate = strings(row.optJSONArray("intermediate")) + strings(row.optJSONArray("intermediate_drugs")) + strings(row.optJSONArray("intermediate_antibiotics")) + strings(summaryObject?.optJSONArray("intermediate"))
        val resistant = strings(row.optJSONArray("resistant")) + strings(row.optJSONArray("resistant_drugs")) + strings(row.optJSONArray("resistant_antibiotics")) + strings(summaryObject?.optJSONArray("resistant"))
        return listOf("S: " + susceptible.distinct().joinToString(", "), "I: " + intermediate.distinct().joinToString(", "), "R: " + resistant.distinct().joinToString(", ")).filterNot { it.endsWith(": ") }.joinToString("; ")
    }

    private fun cultureComment(row: JSONObject): String {
        val notes = buildList {
            firstNonBlank(row, "comment", "microbiology_note", "note").takeIf { it.isNotBlank() }?.let(::add)
            firstNonBlank(row, "bottle_name").takeIf { it.isNotBlank() }?.let { add("Bottle: $it") }
            firstNonBlank(row, "reporting_status", "report_status").takeIf { it.isNotBlank() }?.let { add("Report status: $it") }
            firstNonBlank(row, "isolate_number").takeIf { it.isNotBlank() }?.let { add("Isolate: $it") }
            if (row.optString("clinical_review_flag").equals("true", ignoreCase = true)) add("Clinical-significance review required; verify with source report and bedside context.")
        }
        return notes.distinct().joinToString(" | ")
    }

    private fun strings(values: JSONArray?): List<String> {
        if (values == null) return emptyList()
        return buildList { for (index in 0 until values.length()) values.optString(index).takeIf { it.isNotBlank() }?.let(::add) }
    }

    private fun stringsPreserveBlanks(values: JSONArray?): List<String> {
        if (values == null) return emptyList()
        return buildList { for (index in 0 until values.length()) add(values.optString(index)) }
    }

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
            val value = row.optString(key)
            if (value.isNotBlank()) return value
        }
        return fallback
    }
}
