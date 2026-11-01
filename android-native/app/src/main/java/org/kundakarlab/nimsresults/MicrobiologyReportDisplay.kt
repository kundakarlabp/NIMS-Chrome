package org.kundakarlab.nimsresults

import java.time.LocalDate
import java.util.Locale

/** Minimal clinical presentation, separate from complete LIS/PDF audit records. */
object MicrobiologyReportDisplay {
    private val whitespace = Regex("""\s+""")
    private fun normalized(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]+"""), " ").trim()
            .replace(whitespace, " ")

    data class ResolvedReport(
        val report: ReportRef,
        val header: PdfDocumentHeader,
        val specimen: String
    )

    /**
     * For an aerobic culture with two PDF candidates, all candidates must
     * be inspected before one can be selected by exact clinical evidence.
     * An unopened alternative might contain the same no-growth narrative.
     */
    fun uniqueReport(rows: List<LabResult>, reports: List<ReportRef>,
                     patientCr: String, date: LocalDate): ResolvedReport? {
        if (patientCr.isBlank() || reports.isEmpty() || reports.size > 4) return null
        val inspected=reports.map { report ->
            val header=ReportTitleCache.header(report.url)
                ?.takeIf { it.crNo==patientCr } ?: return null
            val text=ReportTitleCache.extracted(report.url)
                ?.takeIf(String::isNotBlank) ?: return null
            val specimen=specimen(rows,report,header,text,date)
            if (specimen.isBlank()) null
            else ResolvedReport(report,header,specimen)
        }
        return inspected.filterNotNull().singleOrNull()
    }

    /**
     * Transfer "Sample Processed" only when a SINGLE patient-verified PDF
     * also matches the indexed accession, investigation date, and an actual
     * clinical narrative from the same bulk test. A same-day PDF alone is not
     * enough to attribute the sample to this Gram stain.
     */
    fun specimen(
        rows: List<LabResult>, candidate: ReportRef?,
        header: PdfDocumentHeader?, pdfText: String,
        displayedDate: LocalDate
    ): String {
        if (rows.isEmpty() || candidate == null || header == null ||
            header.crNo.isBlank() || header.labNumber.isBlank() ||
            !header.labNumber.equals(candidate.labNumber,true) ||
            pdfText.isBlank()) return ""
        val independentLabs = rows.map { it.labNumber.trim() }
            .filter(String::isNotBlank).distinct()
        if (independentLabs.any { !it.equals(header.labNumber,true) }) return ""
        val dateMatches = listOf(header.collectionDate,header.reportedDate)
            .mapNotNull(RecentReportDates::parse).any { it == displayedDate }
        if (!dateMatches) return ""

        val narrative = rows.map { normalized(it.valueText) }
            .filter { it.length >= 35 && it.split(' ').size >= 6 }
        val source = normalized(pdfText)
        if (narrative.none { source.contains(it) }) return ""

        // A specimen in "Sample Type/No: Any Other Specimen/..." is not an
        // identified specimen. The explicit "Sample Processed" row is.
        val choices = PdfSourceEvidence.specimen(pdfText)
            .trim().replace(whitespace, " ")
        if (choices.isBlank() || choices.length > 65 ||
            choices.contains("/") ||
            choices.contains("other specimen",true)) return ""
        return choices.lowercase(Locale.ROOT)
            .replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}
