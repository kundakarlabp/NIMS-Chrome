package org.kundakarlab.nimsresults

/**
 * Source evidence for a single *physical* PDF. One PDF can include many lab
 * studies and many pages; never equate a PDF identifier with a specimen ID.
 * Section text remains on the device and is never treated as parsed clinical
 * parameters without an explicitly validated mapping.
 */
data class PdfSourcePart(
    val page: Int,
    val header: PdfDocumentHeader,
    val text: String,
    val title: String = ""
)

object PdfSourceEvidence {
    private val crPattern = Regex("""(?i)\bCR\s*(?:No|Number)?\.?\s*[:#\-]?\s*(\d{15})\b""")
    // Anchored to a *labelled* accession field. Ordinary values inside the
    // report (including antibiotic MICs) never start a new study.
    private val labPattern = Regex(
        """(?im)^\s*(?:Lab\s*/\s*Study\s*(?:No\.?|Number)?|Lab\s*(?:Number|No\.?|Study\s*No\.?)|Accession\s*(?:Number|No\.?)?|Study\s*No\.?)\s*[:#\-]\s*[A-Za-z0-9/_-]+"""
    )
    fun patientCrs(pages: List<String>): Set<String> =
        pages.flatMap { page -> crPattern.findAll(page).map { it.groupValues[1] }.toList() }.toSet()

    fun parts(pages: List<String>, provenCr: String): List<PdfSourcePart> {
        return pages.flatMapIndexed { index, page ->
            if (page.isBlank()) return@flatMapIndexed emptyList<PdfSourcePart>()
            val starts = labPattern.findAll(page).map { it.range.first }.toList()
            // With multiple lab headers on a page, preserve their *separate*
            // collection/reporting metadata. Do not attribute a shared heading
            // or first accession to all subsequent studies.
            val slices = if (starts.size <= 1) listOf(page)
                else starts.mapIndexed { i, start ->
                    page.substring(start, starts.getOrNull(i + 1) ?: page.length)
                }
            slices.map { chunk ->
                val original = PdfDocumentHeaders.parse(chunk)
                PdfSourcePart(
                    page = index + 1,
                    header = original.copy(crNo = provenCr),
                    text = chunk.take(40000),
                    title = OpaqueReportTitles.titleFromExtractedText(chunk).orEmpty()
                )
            }
        }
    }
}
