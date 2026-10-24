package org.kundakarlab.nimsresults

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

data class ReportCandidates(val verified: ReportRef?, val candidates: List<ReportRef>)

/**
 * A date / name can locate a candidate PDF, not prove source identity.
 * Only a unique accession (with CR checks) may become a verified direct link.
 */
object ReportLinkResolver {
    private fun normalize(s: String): String {
        val cleaned = s.lowercase(Locale.US).replace(Regex("""[^a-z0-9]"""), "")
        // NIMS uses Gram smear, Gram stain, Gram's stain and Grams staining
        // for the same investigation class (not necessarily same specimen).
        return if (Regex("""^grams?(?:stain(?:ing)?|smear)""").containsMatchIn(cleaned))
            "gramstain" + cleaned.substringAfter(
                Regex("""^grams?(?:stain(?:ing)?|smear)""").find(cleaned)!!.value
            )
        else cleaned.replace("staining", "stain")
    }
    private fun kind(s: String): String {
        val v = normalize(s)
        return when {
            v.startsWith("gramstain") || v.startsWith("gramsmear") -> "gramstain"
            v.contains("fluorescencestain") || v.contains("auramine") -> "afbstain"
            v.contains("aerobicculture") -> "aerobicculture"
            v.contains("bloodculture") -> "bloodculture"
            v.contains("fungalculture") -> "fungalculture"
            v.contains("urineculture") -> "urineculture"
            v.contains("genexpert") || v.contains("cbnaat") -> "genexpert"
            else -> v
        }
    }
    private fun accessionParts(s: String): Set<String> =
        s.split('/', '\\', '|').map(::normalize).filter { it.length >= 5 }.toSet()

    private fun uniqueLabMatch(lab: String, pdfLab: String): Boolean {
        val a = accessionParts(lab); val b = accessionParts(pdfLab)
        return a.isNotEmpty() && b.isNotEmpty() && a.any { it in b }
    }

    /** A study accession may identify a *collection episode*, not one PDF.
     * For verified source documents, reject a different kind of report even
     * when accession and specimen are shared (stain vs culture, PT vs LFT). */
    private fun sourceAgrees(test: String, report: ReportRef): Boolean {
        val sections = ReportTitleCache.sections(report.url)
        val target = PdfSourceEvidence.kind(test)
        if (sections.isEmpty()) {
            // A patient-checked NIMS index row already identifies this
            // document's investigation; use its original name where distinct.
            val listed = PdfSourceEvidence.kind(report.title)
            return target == PdfStudyKind.OTHER ||
                listed == PdfStudyKind.OTHER || target == listed
        }
        if (target == PdfStudyKind.OTHER) return true
        val known = sections.map { it.kind }.filter { it != PdfStudyKind.OTHER }.toSet()
        return known.isEmpty() || target in known
    }

    private fun inlineCultureAccession(rows: List<LabResult>): String =
        rows.mapNotNull { row ->
            // NIMS sometimes prefixes a culture narrative with its lab
            // number. Accept it only as a hint and require an identical lab
            // field from a PDF whose patient CR is explicitly verified.
            val text = row.valueText.trim()
            if (!text.contains("CULTURE REPORT", true)) return@mapNotNull null
            Regex("""^([A-Z]{1,3}\d{4,8})\s+(?:CULTURE\s+REPORT\b|FAN\s+BLOOD\s+CULTURE\b)""",
                RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
        }.distinct().singleOrNull().orEmpty()

    private fun candidateDocuments(inventory: List<ReportRef>): List<ReportRef> =
        inventory.filter { it.url.isNotBlank() }.distinctBy {
            ReportTitleCache.digest(it.url).takeIf(String::isNotBlank) ?: it.url
        }

    fun candidates(rows: List<LabResult>, inventory: List<ReportRef>,
                   expectedCr: String = ""): ReportCandidates {
        val relevant = candidateDocuments(inventory).filter { report ->
            val header = ReportTitleCache.header(report.url)
            header == null || expectedCr.isBlank() || header.crNo == expectedCr
        }
        val test = rows.firstOrNull()?.test.orEmpty()
        val explicitLab = ClinicalPresentation.accession(rows)
        val narrativeHint = if (explicitLab.isBlank()) inlineCultureAccession(rows) else ""
        val lab = explicitLab.ifBlank { narrativeHint }
        if (lab.isNotBlank()) {
            val labMatched = relevant.filter { report ->
                val header = ReportTitleCache.header(report.url)
                val labs = ReportTitleCache.accessions(report.url) +
                    listOfNotNull(header?.labNumber, report.labNumber)
                labs.any { uniqueLabMatch(lab, it) } &&
                    sourceAgrees(test, report) &&
                    (narrativeHint.isBlank() || header?.crNo == expectedCr &&
                        expectedCr.isNotBlank())
            }
            if (labMatched.size == 1) return ReportCandidates(labMatched.single(), labMatched)
            if (labMatched.isNotEmpty()) return ReportCandidates(null, labMatched)
        }

        if (test.isBlank()) return ReportCandidates(null, emptyList())
        val rowDate = RecentReportDates.dateFor(rows).first
            ?: return ReportCandidates(null, emptyList())
        val canonical = kind(test)
        val micro = ClinicalReportRenderer.isMicrobiologyInvestigation(test)
        val matching = relevant.mapNotNull { report ->
            val name = ReportTitleCache.get(report.url).orEmpty()
            val reportTest = listOf(name, report.title) +
                ReportTitleCache.sections(report.url).map { it.title }
                    .filter(String::isNotBlank)
            val sameTest = reportTest.any { candidate ->
                val n = kind(candidate)
                n == canonical || (canonical.length >= 8 && n.length >= 8 &&
                    (n.contains(canonical) || canonical.contains(n)))
            }
            val header = ReportTitleCache.header(report.url)
            val dates = listOf(report.reportedDate, report.date, report.orderDate,
                report.sentDate, header?.reportedDate.orEmpty(),
                header?.collectionDate.orEmpty()).mapNotNull(RecentReportDates::parse)
            val delta = dates.minOfOrNull { abs(ChronoUnit.DAYS.between(rowDate, it)) }
                ?: return@mapNotNull null
            if (delta > 7) return@mapNotNull null
            val genericIndexTitle = Regex(
                """(?i)^(?:microbiology|bacteriology|microscopy|investigation report|lab report|laboratory report|result|report)$"""
            ).matches(report.title.trim())
            val isMicroIndex = genericIndexTitle &&
                (report.department.contains("microbiology", true) ||
                    Regex("""(?i)^(?:microbiology|bacteriology|microscopy)$""")
                        .matches(report.title.trim()))
            val genericMicro = micro && isMicroIndex && delta <= 2
            if ((!sameTest && !genericMicro) || !sourceAgrees(test, report))
                return@mapNotNull null
            val rank = (if (sameTest) 100 else 0) + (7 - delta.toInt()) +
                (if (header?.crNo == expectedCr && expectedCr.isNotBlank()) 10 else 0)
            report to rank
        }.sortedWith(compareByDescending<Pair<ReportRef, Int>> { it.second }
            .thenByDescending { RecentReportDates.dateFor(it.first).first ?: LocalDate.MIN })
        // Date/name proximity only yields choices, never a proven link.
        return ReportCandidates(null, matching.map { it.first }.take(8))
    }
}
