package org.kundakarlab.nimsresults

import java.time.LocalDate
import java.util.Locale

/**
 * Display-only source reconciliation. A same-day index candidate is NOT a
 * patient result. Never attach a PDF unless its CR is verified and the
 * report event is distinguished from other specimens/stages.
 */
object MicrobiologyPdfIdentity {
    data class Matched(val report: ReportRef, val header: PdfDocumentHeader,
                       val specimen: String, val discordant: Boolean)

    private fun norm(input: String): String = input.lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9]+"""), " ").trim()
        .replace(Regex("""\s+"""), " ")

    fun displayTitle(test: String, rows: List<LabResult>): String {
        val narrative = rows.joinToString(" ") { it.valueText }.take(500)
        if (Regex("""(?i)gram(?:s)?\s+stain(?:ing)?""").containsMatchIn(test) &&
            Regex("""(?i)\bgiemsa\s+smear\b""").containsMatchIn(narrative))
            return "GIEMSA SMEAR"
        return test
    }

    /**
     * A PDF can contain irreconcilable results (as in the NIMS urine
     * culture with both "no significant bacteriuria" and E. coli growth).
     * Flag the original contradiction; do not silently classify it.
     */
    fun contradictoryCulture(text: String): Boolean {
        if (!Regex("""(?i)\bculture\b""").containsMatchIn(text)) return false
        val absent = Regex("""(?i)\b(?:no\s+significant\s+bacteriuria|no\s+growth|sterile\s+culture)\b""")
            .containsMatchIn(text)
        val growth = Regex("""(?i)\bculture\s+shows\s+growth\b|\bgrowth\s+of\s+(?:escherichia|klebsiella|pseudomonas|enterococcus|staphylococcus|acinetobacter)\b""")
            .containsMatchIn(text)
        return absent && growth
    }

    private fun compatible(test: String, sourceTitle: String,
                           sourceText: String): Boolean {
        val t=norm(test)
        val title=norm(sourceTitle)
        val body=norm(sourceText)
        if("culture" in t) {
            if("culture" !in title && "culture" !in body)return false
            val urine="urine" in t
            val sourceUrine="urine" in title ||
                Regex("""(?i)\burine\s+aerobic\s+culture\b""")
                    .containsMatchIn(sourceText)
            if(urine!=sourceUrine)return false
            if("blood" in t && "urine" in title)return false
            if("fungal" in t && "urine" in title)return false
            if("blood" !in t && "urine" !in t && "blood" in title)return false
            if("fungal" !in t && "fungal" in title)return false
            return true
        }
        // Distinguish fungal KOH, fungal fluorescent staining, AFB
        // fluorescence and Gram/Giemsa. The generic old "staining" rule
        // accidentally rejected EVERY fungal source title.
        val isKoh=Regex("""(?i)\bkoh\b""").containsMatchIn(test)
        val isCalco=Regex("""(?i)calcofluor|calcoflour|fungal\s+fluorescen""")
            .containsMatchIn(test)
        val isFungal=isKoh || isCalco || "fungal" in t
        val isAfb=Regex("""(?i)\bafb\b|acid[\s-]*fast|tb\s+fluorescen|auramine""")
            .containsMatchIn(test)
        val candidateCulture="culture" in title
        if(candidateCulture)return false
        if(isKoh) return "koh" in title || ("stain" in title && "koh" in body)
        if(isCalco)return "calcofluor" in title || "calcoflour" in title ||
            "fungal fluorescence" in title ||
            ("stain" in title && "calcofluor" in body)
        if(isFungal)return "fungal" in title && "koh" !in title &&
            "culture" !in title
        if(isAfb)return "afb" in title || "tb fluorescence" in title ||
            "acid fast" in title || "auramine" in title ||
            ("stain" in title && ("acid fast" in body || "auramine" in body))
        if(Regex("""(?i)gram|giemsa|stain|smear""").containsMatchIn(test)) {
            if(Regex("""(?i)\b(?:fungal|calcofluor|afb|koh|acid\s+fast)\b""")
                    .containsMatchIn(sourceTitle))return false
            return "stain" in title || "smear" in title ||
                "giemsa" in title || "giemsa smear" in body ||
                "gram smear" in body || "grams smear" in body
        }
        return t==title
    }

    /**
     * Return a uniquely verified PDF. When no LIS accession is present,
     * EVERY plausible candidate must be inspected; an unopened competing
     * document prevents guessing a direct link.
     */
    fun unique(rows: List<LabResult>, candidates: List<ReportRef>,
               patientCr: String, day: LocalDate): Matched? {
        if(rows.isEmpty() || patientCr.isBlank() || candidates.isEmpty() ||
            candidates.size>8) return null
        val labs=(rows.map { it.labNumber.trim() } +
            rows.mapNotNull { MicrobiologyNarrativeMetadata.extract(it)?.labNumber })
            .filter(String::isNotBlank).distinct()
        if(labs.size>1)return null
        val lab=labs.singleOrNull().orEmpty()
        val expectedDate=rows.mapNotNull { RecentReportDates.parse(it.reportedDate) }
            .distinct().singleOrNull() ?: day
        val matching=mutableListOf<Matched>()
        for(report in candidates.distinctBy{it.url}) {
            val header=ReportTitleCache.header(report.url)
            // CR verification is mandatory; do not use a different PDF's
            // lab number or dates to complete this row's metadata.
            if(header==null || header.crNo!=patientCr) {
                if(lab.isBlank())return null
                continue
            }
            if(report.labNumber.isNotBlank() &&
                !report.labNumber.equals(header.labNumber,true)) continue
            if(lab.isNotBlank() && !lab.equals(header.labNumber,true)) continue
            val pdf=ReportTitleCache.extracted(report.url).orEmpty()
            if(pdf.isBlank() || !compatible(rows.first().test,report.title,pdf)) continue
            val observed=rows.map { norm(it.valueText) }
                .filter { it.length>=34 && it.split(" ").size>=5 }
            val normalizedPdf=norm(pdf)
            if(observed.isNotEmpty() &&
                observed.none { normalizedPdf.contains(it) }) continue
            // A dated result must match the actual reported day, not just
            // the date when the specimen was sent to the laboratory.
            val reportDay=RecentReportDates.parse(header.reportedDate)
                ?:RecentReportDates.parse(report.reportedDate)
            if(reportDay!=null && expectedDate!=LocalDate.MIN &&
                reportDay!=expectedDate) continue
            if(reportDay==null && lab.isBlank()) continue
            val rowSample=rows.map { norm(it.specimenType) }
                .filter { it.isNotBlank() && it !in setOf("other","any other specimen") }
                .distinct().singleOrNull()
            val specimen=PdfSourceEvidence.specimen(pdf).trim()
            if(rowSample!=null && specimen.isNotBlank() &&
                rowSample!=norm(specimen)) continue
            matching+=Matched(report,header,specimen,
                contradictoryCulture(pdf))
        }
        return matching.singleOrNull()
    }

    /** Evidence-only accession list for direct PDF controls. */
    fun accession(rows: List<LabResult>): String =
        rows.map { it.labNumber.trim() }.filter(String::isNotBlank)
            .distinct().singleOrNull().orEmpty()
}
