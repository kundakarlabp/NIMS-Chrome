package org.kundakarlab.nimsresults

import java.time.LocalDate
import java.util.Locale

/** Minimal clinical presentation, separate from complete LIS/PDF audit records. */
object MicrobiologyReportDisplay {
    private val whitespace = Regex("""\s+""")
    private fun normalized(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]+"""), " ").trim()
            .replace(whitespace, " ")

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
