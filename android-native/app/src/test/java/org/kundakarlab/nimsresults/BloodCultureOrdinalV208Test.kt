package org.kundakarlab.nimsresults

import java.time.LocalDate
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic four-bottle source page: only proven bottle headings can link. */
class BloodCultureOrdinalV208Test {
    private val cr="111111111111111"
    private val url="https://nimsts.edu.in/source/fictional-multibottle.pdf"
    @After fun clear() { ReportTitleCache.clear() }

    @Test fun ordinalWordHeadingsMapOnlyTheMatchingBottleAndSet() {
        val first="CULTURE SHOWS NO GROWTH AEROBICALLY AFTER INCUBATION FOR ABOUT 36 TO 48 HOURS."
        val source="""
            Department of Microbiology
            CR No: $cr
            Lab/Study No: B12345
            Coll/Study Date: 10-Oct-2026 09:15
            Reporting Date: 12-Oct-2026 12:31
            Sample Processed: Blood
            Fan Blood Culture - First Bottle of first Set (48 Hrs Report)
            CULTURE REPORT
            $first
            FINAL REPORT AFTER 5 DAYS OF INCUBATION.
            Fan Blood Culture - Second Bottle of first Set (48 Hrs Report)
            CULTURE REPORT
            $first
        """.trimIndent()
        assertTrue(ReportTitleCache.learnPages(url,listOf(source),cr))
        val index=ReportRef(id="index",title="Bacteriology",date="12-Oct-2026",
            url=url,labNumber="B12345",reportedDate="12-Oct-2026 12:31")
        fun matches(set:Int,bottle:Int):MicrobiologyPdfIdentity.Matched? {
            val row=LabResult(id="synthetic",test="Fan Blood Culture($set set $bottle bottle) 48 hrs",
                parameter="Result",date="2026-10-12",valueText=first,labNumber="B12345")
            return MicrobiologyPdfIdentity.unique(listOf(row),listOf(index),
                cr,LocalDate.of(2026,10,12))
        }
        assertNotNull(matches(1,1))
        // The same PDF mentions Bottle 2 further down, but that does not
        // authenticate the earlier first-bottle header for other bottles.
        assertNull(matches(1,2))
        assertNull(matches(2,1))
        assertNull(matches(2,2))
    }
}
