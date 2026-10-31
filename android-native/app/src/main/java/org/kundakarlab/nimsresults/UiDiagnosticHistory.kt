package org.kundakarlab.nimsresults

import android.content.Context
import java.time.Instant

/**
 * On-device, privacy-minimized diagnostic history. No CR, patient data,
 * accession, result values, PDFs, URLs or cookies are ever persisted here.
 * Counters capture which area was opened and the most recent data-quality
 * coverage in that area, without capturing clinical observations.
 */
object UiDiagnosticHistory {
    private const val STORE = "nims_ui_diagnostic_history_v1"
    private val areas = listOf("OVERVIEW", "ALL", "LABS", "ID", "MICROBIOLOGY",
        "RECENT", "TRENDS", "SOURCE_PDFS", "ENQUIRY_ORDERS")

    fun visit(context: Context, area: String, bundle: ClinicalBundle?) {
        if (area !in areas) return
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        val rows = bundle?.results.orEmpty()
        val studyCount = rows.groupBy {
            listOf(it.test, it.date, it.labNumber, it.sampleNumber,
                it.sourceOrderId, it.reportStage)
        }.size
        val readable = rows.count {
            it.valueText.isNotBlank() &&
                !ClinicalReportRenderer.isPlaceholder(it) &&
                !ClinicalReportRenderer.isUninterpretedCode(it) &&
                !ClinicalPresentation.isTechnical(it)
        }
        val unverified = rows.count { ClinicalReportRenderer.isUninterpretedCode(it) }
        val absentReportDates = rows.count { it.reportedDate.isBlank() }
        val sourceWithoutPdf = bundle?.reports.orEmpty().count {
            !it.sourceTag.startsWith("nims_rest_reportList") && it.url.isBlank()
        }
        prefs.edit()
            .putInt("visits.$area", prefs.getInt("visits.$area", 0) + 1)
            .putLong("lastEpochMs.$area", System.currentTimeMillis())
            .putInt("studies.$area", studyCount)
            .putInt("readable.$area", readable)
            .putInt("unverified.$area", unverified)
            .putInt("reportDatesMissing.$area", absentReportDates)
            .putInt("sourcePdfMissing.$area", sourceWithoutPdf)
            .apply()
    }

    fun snapshot(context: Context): String {
        val prefs = context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
        return buildString {
            appendLine("ui.diagnostics.history=persisted_counts_only")
            for (area in areas) {
                val visits = prefs.getInt("visits.$area", 0)
                if (visits == 0) continue
                val prefix = "ui.area.${area.lowercase()}"
                appendLine("$prefix.visits=$visits")
                val at = prefs.getLong("lastEpochMs.$area", 0)
                if (at > 0) appendLine("$prefix.lastVisited=${Instant.ofEpochMilli(at)}")
                appendLine("$prefix.lastInvestigationGroups=${prefs.getInt("studies.$area", 0)}")
                appendLine("$prefix.lastReadableFields=${prefs.getInt("readable.$area", 0)}")
                appendLine("$prefix.lastUnverifiedFields=${prefs.getInt("unverified.$area", 0)}")
                appendLine("$prefix.lastFieldsWithoutReportTimestamp=${prefs.getInt("reportDatesMissing.$area", 0)}")
                appendLine("$prefix.lastSourceWithoutPdf=${prefs.getInt("sourcePdfMissing.$area", 0)}")
            }
            appendLine("ui.diagnostics.privacy=no_patient_identifiers_or_values")
        }
    }
}
