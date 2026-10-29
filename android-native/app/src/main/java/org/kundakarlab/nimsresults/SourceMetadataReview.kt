package org.kundakarlab.nimsresults

import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Candidate source-index metadata for MANUAL review. These are not linked
 * laboratory events and must never be copied into LabResult provenance.
 * A Custom Sheet column date is NOT proof of collection/reporting time.
 */
object SourceMetadataReview {
    private fun key(name: String): String = name.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "")
    private fun titleKey(name: String): String {
        val value = key(name)
        return when {
            value == "cbc" || value.startsWith("cbc") ||
                value == "completebloodcount" || value == "completehemogram" -> "cbc"
            else -> value
        }
    }

    fun candidates(rows: List<LabResult>, sourceIndex: List<ReportRef>): List<ReportRef> {
        val test = rows.firstOrNull()?.test.orEmpty()
        val key = titleKey(test)
        val date = rows.firstNotNullOfOrNull { RecentReportDates.parse(it.date) }
        if (key.isBlank() || date == null) return emptyList()
        return sourceIndex.filter { report ->
            // REST reportList contains only testname/reportdate on this HIS
            // deployment. Such rows must not be presented as specimen-linked
            // source records or as candidate PDFs with unknown identifiers.
            if (report.sourceTag.startsWith("nims_rest_reportList")) return@filter false
            if (report.url.isBlank() && report.labNumber.isBlank() &&
                report.sampleNumber.isBlank() && report.reportNumber.isBlank())
                return@filter false
            if (titleKey(report.title) != key) return@filter false
            val collection = RecentReportDates.parse(
                report.collectionDateTime.ifBlank { report.sentDate }.ifBlank { report.date }
            ) ?: return@filter false
            ChronoUnit.DAYS.between(collection, date) in 0L..14L
        }.sortedWith(compareByDescending<ReportRef> {
            RecentReportDates.parse(it.collectionDateTime.ifBlank { it.date })
        }.thenBy { it.labNumber }.thenBy { it.reportNumber })
    }
}
