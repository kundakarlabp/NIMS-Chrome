package org.kundakarlab.nimsfastsummarymobile.ui.formatters

import org.kundakarlab.nimsfastsummarymobile.ui.models.UiCultureRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiLabTrendRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary

object ClinicalSummaryFormatter {
    private const val DISCLAIMER = "Auto-parsed summary. Verify with source NIMS reports before clinical decisions."

    fun cleanText(summary: UiSummary): String {
        return buildString {
            appendLine("NIMS Fast Summary")
            appendLine("Date range: ${summary.dateRange}")
            appendLine("Reports reviewed: ${summary.sourceReports.size}")
            appendLine("Failed reports: ${summary.failedReportCount}")
            appendLine()
            appendLine("Key labs:")
            summary.labTrends.take(12).forEach { appendLine("- ${labLine(it)}") }
            if (summary.labTrends.isEmpty()) appendLine("- No lab trend data")
            appendLine()
            appendLine("Cultures:")
            summary.cultures.sortedByDescending { it.status == "positive" }.take(12).forEach { appendLine("- ${cultureLine(it)}") }
            if (summary.cultures.isEmpty()) appendLine("- No culture data")
            appendLine()
            appendLine("Interpretation:")
            summary.interpretation.take(8).forEach { appendLine("- $it") }
            if (summary.interpretation.isEmpty()) appendLine("- No interpretation available")
            if (summary.editableNote.isNotBlank()) {
                appendLine()
                appendLine("Physician note:")
                appendLine(summary.editableNote.trim())
            }
            appendLine()
            appendLine(DISCLAIMER)
        }
    }

    fun labLine(row: UiLabTrendRow): String {
        val previous = if (row.previousValue.isNullOrBlank()) "" else "; previous ${row.previousValue} on ${row.previousDate}"
        return "${row.parameter}: latest ${row.latestValue.ifBlank { "not available" }} on ${row.latestDate.ifBlank { "unknown date" }}; trend ${row.trendText}$previous"
    }

    fun cultureLine(row: UiCultureRow): String {
        val organism = row.organism.ifBlank { row.growth.ifBlank { row.status } }
        val sensitivity = if (row.sensitivitySummary.isBlank()) "" else "; ${row.sensitivitySummary}"
        val comment = if (row.comment.isBlank()) "" else "; ${row.comment}"
        return "${row.collectionDate.ifBlank { "unknown date" }} | ${row.site.ifBlank { row.specimen }} | $organism$sensitivity$comment"
    }
}
