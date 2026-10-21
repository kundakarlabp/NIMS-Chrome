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

    fun candidates(rows: List<LabResult>, inventory: List<ReportRef>,
                   expectedCr: String = ""): ReportCandidates {
        val relevant = inventory.filter { it.url.isNotBlank() }.distinctBy { it.url }
            .filter { report ->
                val header = ReportTitleCache.header(report.url)
                header == null || expectedCr.isBlank() || header.crNo == expectedCr
            }
        val lab = ClinicalPresentation.accession(rows)
        if (lab.isNotBlank()) {
            val labMatched = relevant.filter { report ->
                val header = ReportTitleCache.header(report.url)
                uniqueLabMatch(lab,
                    header?.labNumber?.takeIf(String::isNotBlank) ?: report.labNumber)
            }
            if (labMatched.size == 1) return ReportCandidates(labMatched.single(), labMatched)
            if (labMatched.isNotEmpty()) return ReportCandidates(null, labMatched)
        }

        val test = rows.firstOrNull()?.test.orEmpty()
        if (test.isBlank()) return ReportCandidates(null, emptyList())
        val rowDate = RecentReportDates.dateFor(rows).first ?: return ReportCandidates(null, emptyList())
        val canonical = kind(test)
        val micro = ClinicalReportRenderer.isMicrobiologyInvestigation(test)
        val matching = relevant.mapNotNull { report ->
            val name = ReportTitleCache.get(report.url).orEmpty()
            val reportTest = listOf(name, report.title)
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

            // When NIMS lists only a generic Microbiology PDF title, surface
            // nearby dates as candidates; never make those automatic links.
            val isMicroIndex = report.department.contains("microbiology", true) ||
                Regex("""(?i)^(?:microbiology|bacteriology|microscopy)$""")
                    .matches(report.title.trim())
            val genericMicro = micro && isMicroIndex && delta <= 2
            if (!sameTest && !genericMicro) return@mapNotNull null
            val rank = (if (sameTest) 100 else 0) + (7 - delta.toInt()) +
                (if (header?.crNo == expectedCr && expectedCr.isNotBlank()) 10 else 0)
            report to rank
        }.sortedWith(compareByDescending<Pair<ReportRef, Int>> { it.second }
            .thenByDescending { RecentReportDates.dateFor(it.first).first ?: LocalDate.MIN })
        return ReportCandidates(null, matching.map { it.first }.take(8))
    }
}
