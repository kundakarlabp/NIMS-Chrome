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
                add(
                    UiSourceReport(
                        dateSent = row.optString("date_sent"),
                        reportName = row.optString("report_name", "Unnamed report"),
                        type = row.optString("type", row.optString("report_type", "other")),
                        status = status,
                        notes = notes,
                        // hasError must only be true for genuine failures (error/unsupported status),
                        // NOT for processing notes like "Processed from PDF on-device." which are
                        // normal and appear on every successfully-parsed PDF report. The old logic
                        // (|| notes.isNotBlank()) caused every parsed PDF to show as "failed",
                        // producing "Failed: 20" even when reports were successfully parsed.
                        hasError = status.equals("error", ignoreCase = true) || status.equals("unsupported", ignoreCase = true)
                    )
                )
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
                val values = strings(row.optJSONArray("values"))
                if (values.size != columns.size) continue
                val alignedValues = values
                val history = columns.zip(alignedValues).filter { it.second.isNotBlank() }
                val latest = history.firstOrNull()
                val previous = history.drop(1).firstOrNull()
                add(
                    UiLabTrendRow(
                        parameter = row.optString("parameter", "Parameter"),
                        latestValue = latest?.second.orEmpty(),
                        latestDate = latest?.first.orEmpty(),
                        previousValue = previous?.second,
                        previousDate = previous?.first,
                        trendText = row.optString("trend", "insufficient data"),
                        abnormality = abnormality(latest?.second.orEmpty()),
                        history = history
                    )
                )
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
                        cultureNo = firstNonBlank(row, "culture_no", "culture_number", "specimen_no"),
                        specimen = firstNonBlank(row, "specimen", "site_specimen", "specimen_no"),
                        site = firstNonBlank(row, "site", "site_specimen"),
                        organism = firstNonBlank(row, "organism", "growth"),
                        growth = firstNonBlank(row, "growth", "growth_quantity", "result"),
                        status = firstNonBlank(row, "result", "status", "report_status", fallback = "unknown"),
                        sensitivitySummary = row.optString("sensitivity_summary"),
                        comment = row.optString("comment"),
                        sourceReportName = row.optString("report_name")
                    )
                )
            }
        }
    }

    private fun strings(values: JSONArray?): List<String> {
        if (values == null) return emptyList()
        return buildList {
            for (index in 0 until values.length()) add(values.optString(index))
        }
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
