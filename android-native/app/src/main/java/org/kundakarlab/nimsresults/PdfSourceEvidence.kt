package org.kundakarlab.nimsresults

import java.util.Locale

/** Meaning is derived only from explicit report headings, not a phrase in a narrative. */
enum class PdfStudyKind { GRAM_STAIN, FUNGAL_STAIN, AFB_STAIN, CULTURE, CBC, CHEMISTRY, COAGULATION, RADIOLOGY,
    BLOOD_BANK, MOLECULAR, OTHER }

data class PdfSourcePart(
    val page: Int,
    val header: PdfDocumentHeader,
    val text: String,
    val title: String = "",
    val endPage: Int = page,
    val kind: PdfStudyKind = PdfStudyKind.OTHER,
    val specimen: String = "",
    val sampleNumber: String = "",
    val reportStage: String = ""
)

object PdfSourceEvidence {
    private val crPattern = Regex("""(?i)\bCR\s*(?:No|Number)?\.?\s*[:#\-]?\s*(\d{15})\b""")
    private val labPattern = Regex(
        """(?i)\b(?:Lab\s*/\s*Study\s*(?:No\.?|Number)?|Lab\s*(?:Number|No\.?|Study\s*No\.?)|Accession\s*(?:Number|No\.?)?|Study\s*No\.?)\s*[:#\-]\s*[A-Za-z0-9/_-]+"""
    )
    private val samplePattern = Regex("""(?i)\b(?:Specimen|(?:Serum|Blood|Urine|Plasma|CSF))\s*/\s*([A-Za-z0-9_-]{6,})""")
    private val specimenPattern = Regex("""(?i)\bSample\s*Processed\s*:\s*([^\r\n]+)""")
    private val reportFooter = Regex("""(?im)^\s*(?:Test results relate only to item received|This is computer generated report|Page\s+\d+\s+of\s+\d+|Validated By:).*$""")

    fun patientCrs(pages: List<String>): Set<String> =
        pages.flatMap { p -> crPattern.findAll(p).map { it.groupValues[1] }.toList() }.toSet()

    /** Only explicit report-stage headings count. Narrative mentions of
     * "final diagnosis" or a previous preliminary culture do not count. */
    fun stage(text: String): String {
        val head = text.lines().take(35).joinToString("\n")
        val labelled = Regex("""(?im)^\s*(?:Report\s*(?:Stage|Status)|Culture\s*Status)\s*[:#\-]\s*(Preliminary|Interim|Final|Amended)\b""")
            .find(head)?.groupValues?.getOrNull(1)
        val title = Regex("""(?im)^\s*(Preliminary|Interim|Final|Amended)\s+(?:Culture\s+)?Report\b|^\s*Culture\s+(Preliminary|Interim|Final|Amended)\s+Report\b""")
            .find(head)
        return (labelled ?: title?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() })
            ?.uppercase(Locale.US).orEmpty()
    }

    fun sampleNumber(text: String): String =
        PdfDocumentHeaders.parse(text).sampleNumber.ifBlank {
            samplePattern.find(text)?.groupValues?.get(1).orEmpty()
        }
    fun specimen(text: String): String = specimenPattern.find(text)?.groupValues?.get(1)?.trim().orEmpty()

    fun kind(text: String): PdfStudyKind {
        val body = text.lowercase(Locale.ROOT)
        return when {
            Regex("""(?im)^\s*(?:afb|acid[\s-]*fast(?:\s+bacilli)?|tb\s+fluorescen(?:ce|t)|auramine[\s/-]*rhodamine)\s+(?:stain(?:ing)?|smear|microscopy)\b""")
                .containsMatchIn(body) -> PdfStudyKind.AFB_STAIN
            Regex("""(?im)^\s*(?:(?:fungal|calcofluor|koh)\s+(?:stain(?:ing)?|smear|mount|preparation|microscopy))\b""")
                .containsMatchIn(body) -> PdfStudyKind.FUNGAL_STAIN
            Regex("""(?im)^\s*(?:staining|grams?(?:['’]s)?\s+(?:stain(?:ing)?|smear))\b""")
                .containsMatchIn(body) &&
                !Regex("""(?im)^\s*(?:urine\s+)?aerobic\s+culture\b|^\s*culture\s+final\s+report\b""")
                    .containsMatchIn(body) -> PdfStudyKind.GRAM_STAIN
            Regex("""(?m)^\s*(?:(?:fan\s+)?aerobic\s+blood\s+culture|(?:urine\s+)?aerobic\s+culture|culture\s+final\s+report)\b""")
                .containsMatchIn(body) || body.contains("culture report") ->
                PdfStudyKind.CULTURE
            Regex("""(?m)^\s*cbc\s+with\s+diff|^\s*cbc\s*\(|^\s*complete\s+blood\s+count""")
                .containsMatchIn(body) -> PdfStudyKind.CBC
            body.contains("coagulation chemistry") ||
                body.contains("prothrombin time") || body.contains("aptt") ||
                Regex("""\b(?:pt[\s./-]*inr|coagulation|pt\s*\+\s*aptt)\b""")
                    .containsMatchIn(body) -> PdfStudyKind.COAGULATION
            // Classification must prioritize a report's own department and
            // headings. CT/MRI narratives may mention serum creatinine,
            // calcium, or laboratory results without becoming chemistry PDFs.
            body.contains("department of radio diagnosis") ||
                body.contains("department of radiodiagnosis") ||
                body.contains("department of radiology") ||
                body.contains("department of nuclear medicine") ||
                Regex("""(?m)^\s*(?:examination performed|pet[- ]?ct|bone scintigraphy|nuclear medicine study)\s*:""")
                    .containsMatchIn(body) -> PdfStudyKind.RADIOLOGY
            body.contains("blood grouping") || body.contains("antibody screening") ||
                body.contains("cross matching") -> PdfStudyKind.BLOOD_BANK
            body.contains("biofire") || body.contains("filmarray") -> PdfStudyKind.MOLECULAR
            body.contains("routine chemistry") || body.contains("liver function") ||
                Regex("""\b(?:creatinine|electrolytes|urea|sgot|sgpt|albumin|bilirubin|calcium|lipid\s+profile|cholesterol|renal\s+function|lft|rft)\b""")
                    .containsMatchIn(body) -> PdfStudyKind.CHEMISTRY
            else -> PdfStudyKind.OTHER
        }
    }

    /** Strip repeated NIMS page headers/footers for *presentation only*.
     * Original untouched source remains available through Open PDF. */
    fun clinicalBody(page: String): String {
        val lines = page.replace("\r\n", "\n").lines()
        val ward = lines.indexOfFirst { Regex("""(?i)^\s*(?:ward(?:/opd)?|dept/unit)\b""").containsMatchIn(it) }
        val begin = if (ward >= 0) ward + 1 else 0
        val end = lines.indexOfFirst { reportFooter.containsMatchIn(it) }
            .takeIf { it >= begin } ?: lines.size
        return lines.subList(begin, end).joinToString("\n").trim()
    }

    /** Preserve all pages of a single radiology/long-form study as one
     * logical section; do not mistake a repeated page header for a new test. */
    fun parts(pages: List<String>, provenCr: String): List<PdfSourcePart> {
        val fragments = pages.flatMapIndexed { index, page ->
            if (page.isBlank()) return@flatMapIndexed emptyList<PdfSourcePart>()
            val matches = labPattern.findAll(page).toList()
            val distinct = matches.map { PdfDocumentHeaders.parse(it.value).labNumber }
                .filter(String::isNotBlank).distinct()
            val chunks = if (distinct.size < 2) listOf(page)
                else matches.mapIndexed { i, m ->
                    page.substring(m.range.first,
                        matches.getOrNull(i + 1)?.range?.first ?: page.length)
                }
            chunks.map { chunk ->
                val headingText = if (chunks.size == 1) page else chunk
                val header = PdfDocumentHeaders.parse(headingText).copy(crNo = provenCr)
                val sourceText = if (chunks.size == 1) clinicalBody(page) else chunk.trim()
                PdfSourcePart(
                    page = index + 1, header = header, text = sourceText.take(40000),
                    title = OpaqueReportTitles.titleFromExtractedText(headingText).orEmpty(),
                    kind = kind(headingText),
                    specimen = specimen(headingText),
                    sampleNumber = sampleNumber(headingText).ifBlank { header.sampleNumber },
                    reportStage = stage(headingText)
                )
            }
        }
        val merged = mutableListOf<PdfSourcePart>()
        fragments.forEach { item ->
            val last = merged.lastOrNull()
            // Only fold contiguous physical pages with the same accession and
            // investigation kind. Different specimens and reports remain distinct.
            if (last != null && item.page == last.endPage + 1 &&
                item.header.labNumber.isNotBlank() &&
                item.header.labNumber == last.header.labNumber &&
                // A shared accession can have distinct bottles, specimen
                // identifiers, stains, and final/preliminary report sections.
                // Never fold different known study types or specimen numbers.
                item.sampleNumber == last.sampleNumber &&
                (item.reportStage.isBlank() || last.reportStage.isBlank() ||
                    item.reportStage == last.reportStage) &&
                (item.kind == last.kind ||
                    item.kind == PdfStudyKind.OTHER ||
                    last.kind == PdfStudyKind.OTHER)) {
                merged[merged.lastIndex] = last.copy(
                    endPage = item.page,
                    text = (last.text + "\n" + item.text).take(160000),
                    kind = if (last.kind == PdfStudyKind.OTHER) item.kind else last.kind,
                    specimen = last.specimen.ifBlank { item.specimen },
                    sampleNumber = last.sampleNumber.ifBlank { item.sampleNumber },
                    reportStage = last.reportStage.ifBlank { item.reportStage }
                )
            } else merged += item
        }
        return merged
    }
}
