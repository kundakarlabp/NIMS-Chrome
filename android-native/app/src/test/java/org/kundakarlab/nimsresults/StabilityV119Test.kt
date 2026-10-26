package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

/** Synthetic test fixtures only: no patient PHI. */
class StabilityV119Test {
    private val cr = "111111111111111"
    private fun row(id: Int, test: String, date: String, text: String) =
        LabResult("lab$id", test, "Result", date, text)

    @Test fun filteringAnExistingFeedNeverDropsOrMutatesSourceValues() {
        val values = (1..2400).map { index ->
            row(index, "TEST " + index % 375, "2025-09-29",
                "Clinical result " + index)
        }
        val bundle = ClinicalBundle(PatientInfo(crNo=cr), values, emptyList())
        val indexed = UnifiedResultsFeed.baseEntries(bundle)
        assertEquals(375, indexed.size)
        assertEquals(2400, indexed.sumOf { it.rows.size })
        val subset = UnifiedResultsFeed.filterEntries(indexed, "TEST 42")
        assertTrue(subset.isNotEmpty())
        assertTrue(subset.all { it.title.contains("TEST 42") ||
            it.rows.any { r -> r.valueText.contains("TEST 42") } })
        assertEquals(2400, indexed.sumOf { it.rows.size })
        assertEquals(indexed.size, UnifiedResultsFeed.filterEntries(indexed).size)
    }

    @Test fun pdfTextCacheRetainsOnlyBoundedVerifiedRecentSources() {
        ReportTitleCache.clear()
        (1..12).forEach { number ->
            val url = "https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/" +
                "invDuplicateResultReportPrinting.cnt?hmode=PRINTREPORT&fileName=$number"
            val success = ReportTitleCache.learnPages(url,
                listOf("CR No: $cr\nLab/Study No.: A123456\n" +
                    "Reported Date: 26-Oct-2026 10:30\nCBC report $number"),cr)
            assertTrue(success)
        }
        val first = "https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/" +
            "invDuplicateResultReportPrinting.cnt?hmode=PRINTREPORT&fileName=1"
        val latest = first.replace("fileName=1", "fileName=12")
        assertNull(ReportTitleCache.header(first))
        assertNotNull(ReportTitleCache.header(latest))
        ReportTitleCache.clear()
    }

    @Test fun wrongCrStillCannotEnterEvidenceCache() {
        ReportTitleCache.clear()
        val url = "https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/" +
            "invDuplicateResultReportPrinting.cnt?hmode=PRINTREPORT&fileName=wrong"
        assertFalse(ReportTitleCache.learnPages(url,
            listOf("CR No: 222222222222222\nLab/Study No.: B12345"), cr))
        assertNull(ReportTitleCache.header(url))
        ReportTitleCache.clear()
    }
}
