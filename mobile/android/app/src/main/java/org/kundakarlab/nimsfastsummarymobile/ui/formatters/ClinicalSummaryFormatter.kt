package org.kundakarlab.nimsfastsummarymobile.ui.formatters

import org.kundakarlab.nimsfastsummarymobile.ui.models.Abnormality
import org.kundakarlab.nimsfastsummarymobile.ui.models.ClinicalPanel
import org.kundakarlab.nimsfastsummarymobile.ui.models.ClinicalReviewFilter
import org.kundakarlab.nimsfastsummarymobile.ui.models.ClinicalReviewProjector
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiCultureRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiLabTrendRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary

object ClinicalSummaryFormatter {
    private const val DISCLAIMER = "Auto-parsed summary. Verify with source NIMS reports before clinical decisions."

    fun cleanText(summary: UiSummary): String {
        val full = ClinicalReviewProjector.project(summary)
        val abnormal = ClinicalReviewProjector.project(summary, ClinicalReviewFilter(abnormalOnly = true))
        return buildString {
            appendLine("NIMS Fast Summary")
            val patient = summary.patientSnapshot
            if (patient.isAvailable) {
                appendLine("Patient: ${patient.identityLine}")
            } else {
                appendLine("Patient: demographic metadata unavailable")
            }
            appendLine("Date range: ${summary.reportDateRange}")
            appendLine("Reports parsed: ${summary.parsedReportCount}/${summary.sourceReports.size}")
            appendLine("Reports needing source review: ${summary.reportsNeedingReview.size}")
            appendLine("Cultures: positive ${summary.positiveCultureCount}, pending ${summary.pendingCultureCount}, no growth ${summary.noGrowthCultureCount}")

            appendLine()
            appendLine("Immediate review:")
            val immediate = buildList {
                addAll(summary.actionableCultures.take(6).map(::cultureLine))
                addAll(abnormal.labs.filter { it.abnormality == Abnormality.CRITICAL }.take(6).map(::labLine))
            }
            immediate.forEach { appendLine("- $it") }
            if (immediate.isEmpty()) appendLine("- No positive/pending culture or critical laboratory result was parsed")

            appendLine()
            appendLine("Longitudinal changes:")
            full.changeStatements.take(12).forEach { appendLine("- $it") }
            if (full.changeStatements.isEmpty()) appendLine("- Insufficient paired numeric results")

            appendLine()
            appendLine("Abnormal latest results:")
            abnormal.labs.take(16).forEach { appendLine("- ${labLine(it)}") }
            if (abnormal.labs.isEmpty()) appendLine("- No abnormal structured latest result")

            // Retain the established human-readable export contract while
            // expanding it into clinically grouped panels below.
            appendLine()
            appendLine("Key labs:")
            if (full.labs.isEmpty()) appendLine("- No selected lab trend data")
            appendPanel(this, "Hemogram", full.labs.filter { ClinicalReviewProjector.panelFor(it.parameter) == ClinicalPanel.HEMOGRAM })
            appendPanel(this, "Inflammatory / fungal markers", full.labs.filter { ClinicalReviewProjector.panelFor(it.parameter) == ClinicalPanel.INFLAMMATORY })
            appendPanel(this, "Renal / metabolic", full.labs.filter { ClinicalReviewProjector.panelFor(it.parameter) == ClinicalPanel.RENAL_METABOLIC })
            appendPanel(this, "Liver", full.labs.filter { ClinicalReviewProjector.panelFor(it.parameter) == ClinicalPanel.LIVER })
            appendPanel(this, "Molecular / PCR", full.labs.filter { ClinicalReviewProjector.panelFor(it.parameter) == ClinicalPanel.MOLECULAR_PCR })

            appendLine()
            appendLine("Culture episodes:")
            summary.cultures.take(16).forEach { appendLine("- ${cultureLine(it)}") }
            if (summary.cultures.isEmpty()) appendLine("- No culture data")

            if (summary.reportsNeedingReview.isNotEmpty()) {
                appendLine()
                appendLine("Needs source review:")
                summary.reportsNeedingReview.take(10).forEach {
                    appendLine("- ${it.dateSent.ifBlank { "unknown date" }} | ${it.reportName} | ${it.status}${it.notes.takeIf(String::isNotBlank)?.let { note -> "; $note" }.orEmpty()}")
                }
            }

            appendLine()
            appendLine("Interpretation:")
            summary.interpretation.filter(String::isNotBlank).distinct().take(8).forEach { appendLine("- $it") }
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
        val flag = when (row.abnormality) {
            Abnormality.CRITICAL -> " [CRITICAL]"
            Abnormality.HIGH -> " [HIGH]"
            Abnormality.LOW -> " [LOW]"
            else -> ""
        }
        return "${row.parameter}: ${row.latestValue.ifBlank { "not available" }} on ${row.latestDate.ifBlank { "unknown date" }}$flag; trend ${row.trendText}$previous"
    }

    fun cultureLine(row: UiCultureRow): String {
        val organism = row.organism.ifBlank { row.growth.ifBlank { row.status.replace('_', ' ') } }
        val specimen = row.site.ifBlank { row.specimen }.ifBlank { "specimen not parsed" }
        val sensitivity = if (row.sensitivitySummary.isBlank()) "" else "; ${row.sensitivitySummary}"
        val stage = if (row.reportStage.isBlank()) "" else "; ${row.reportStage}"
        val comment = if (row.comment.isBlank()) "" else "; ${row.comment}"
        return "${row.collectionDate.ifBlank { "unknown date" }} | $specimen | $organism$stage$sensitivity$comment"
    }

    private fun appendPanel(builder: StringBuilder, title: String, rows: List<UiLabTrendRow>) {
        builder.appendLine()
        builder.appendLine("$title:")
        rows.take(12).forEach { builder.appendLine("- ${labLine(it)}") }
        if (rows.isEmpty()) builder.appendLine("- No structured result")
    }
}
