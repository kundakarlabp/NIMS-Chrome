package org.kundakarlab.nimsresults

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

data class ReportCandidates(val verified: ReportRef?, val candidates: List<ReportRef>)

/**
 * Inventory report IDs and source PDF headers are evidence. Identical dates,
 * departments or test descriptions are NOT sufficient specimen identity.
 */
object ReportLinkResolver {
    private fun normalize(s: String) = s.lowercase(Locale.US).replace(Regex("""[^a-z0-9]"""), "")
        .replace("grams", "gram").replace("staining", "stain")
    private fun accessionParts(s: String): Set<String> =
        s.split('/', '\\', '|').map(::normalize).filter { it.length >= 5 }.toSet()
    private fun uniqueLabMatch(lab: String, pdfLab: String): Boolean {
        val a = accessionParts(lab)
        val b = accessionParts(pdfLab)
        return a.isNotEmpty() && b.isNotEmpty() && a.any { it in b }
    }

    fun candidates(rows: List<LabResult>, inventory: List<ReportRef>,
                   expectedCr: String = ""): ReportCandidates {
        val relevant = inventory.filter { it.url.isNotBlank() }.distinctBy { it.url }
        val lab = ClinicalPresentation.accession(rows)
        if (lab.isNotBlank()) {
            val labMatched = relevant.filter { report ->
                val header = ReportTitleCache.header(report.url)
                // A previously extracted PDF with a conflicting CR can NEVER
                // be rescued by an accession match in the report inventory.
                if (header != null && expectedCr.isNotBlank() &&
                    header.crNo != expectedCr) return@filter false
                val id = if (header != null && header.labNumber.isNotBlank())
                    header.labNumber else report.labNumber
                uniqueLabMatch(lab, id)
            }
            // A verified CR-bearing PDF header is strongest evidence; an
            // inventory-only unique lab number also counts if the report
            // inventory was itself authenticated to the current CR.
            if (labMatched.size == 1) return ReportCandidates(labMatched.single(), labMatched)
            if (labMatched.isNotEmpty()) return ReportCandidates(null, labMatched)
        }
        // Unmatched reports are NOT a 35-PDF date search. A candidate must
        // resemble this exact investigation and be close to a source date.
        // Nothing in this list is promoted to a verified link.
        val test = normalize(rows.firstOrNull()?.test.orEmpty())
        if (test.isBlank()) return ReportCandidates(null, emptyList())
        val resultDate = rows.firstOrNull()?.date.orEmpty()
        val local = runCatching { LocalDate.parse(BulkParser.isoDate(resultDate)) }.getOrNull()
        val shortlisted = relevant.filter { report ->
            val indexTitle = normalize(report.title)
            val learnedTitle = normalize(ReportTitleCache.get(report.url).orEmpty())
            val titleMatches = listOf(indexTitle, learnedTitle).any {
                it.isNotBlank() && (it == test ||
                    (it.length >= 8 && test.length >= 8 &&
                     (it.contains(test) || test.contains(it))))
            }
            val dated = local != null && listOf(
                report.date, report.orderDate, report.reportedDate, report.sentDate
            ).filter(String::isNotBlank).any { raw ->
                val date = runCatching { LocalDate.parse(BulkParser.isoDate(raw)) }.getOrNull()
                date != null && kotlin.math.abs(ChronoUnit.DAYS.between(local, date)) <= 7
            }
            titleMatches && dated
        }.sortedByDescending { BulkParser.dateKey(BulkParser.isoDate(it.date)) }
        return ReportCandidates(null, shortlisted.take(8))
    }
}
