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
                val id = if (header != null && header.crNo == expectedCr) header.labNumber
                         else report.labNumber
                uniqueLabMatch(lab, id)
            }
            // A verified CR-bearing PDF header is strongest evidence; an
            // inventory-only unique lab number also counts if the report
            // inventory was itself authenticated to the current CR.
            if (labMatched.size == 1) return ReportCandidates(labMatched.single(), labMatched)
            if (labMatched.isNotEmpty()) return ReportCandidates(null, labMatched)
        }
        val resultDate = rows.firstOrNull()?.date.orEmpty()
        val local = runCatching { LocalDate.parse(BulkParser.isoDate(resultDate)) }.getOrNull()
        val nearby = relevant.filter { r ->
            val options = listOf(r.date, r.orderDate, r.reportedDate, r.sentDate)
                .filter { it.isNotBlank() }
            local != null && options.any { raw ->
                val date = runCatching { LocalDate.parse(BulkParser.isoDate(raw)) }.getOrNull()
                date != null && kotlin.math.abs(ChronoUnit.DAYS.between(local,date)) <= 14
            }
        }
        val shortlist = if (nearby.isNotEmpty()) nearby else relevant
        val test = rows.firstOrNull()?.test.orEmpty()
        val scored = shortlist.sortedWith(
            compareByDescending<ReportRef> { normalize(it.title) == normalize(test) }
                .thenByDescending { r ->
                    r.date.isNotBlank() && BulkParser.isoDate(r.date) == BulkParser.isoDate(resultDate)
                }.thenByDescending { BulkParser.dateKey(BulkParser.isoDate(it.date)) }
        )
        // Return candidate list ONLY. Date/title matching is never automatic.
        return ReportCandidates(null, scored.take(35))
    }
}
