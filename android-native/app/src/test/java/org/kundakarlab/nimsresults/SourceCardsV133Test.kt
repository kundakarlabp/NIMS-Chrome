package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class SourceCardsV133Test {
    private val test = LabResult(
        id = "synthetic", test = "Liver Function Tests",
        parameter = "Serum Albumin", date = "2026-04-24",
        valueText = "3.1", numericValue = 3.1
    )

    @Test fun duplicateInventoryRowsWithSamePdfAreOneReviewChoice() {
        val pdf = "https://www.nimsts.edu.in/HISInvestigationG5/source.pdf"
        val duplicates = listOf(
            ReportRef(id="first",title="Liver Function Tests",
                date="24-Apr-2026",url=pdf),
            ReportRef(id="second",title="LFT",
                date="24-Apr-2026",url=pdf),
            ReportRef(id="third",title="Liver Function Tests",
                date="24-Apr-2026",url=pdf)
        )
        val candidates=SourceMetadataReview.candidates(listOf(test),duplicates)
        assertEquals(1,candidates.size)
        assertEquals(pdf,candidates.single().url)
    }

    @Test fun independentSameDatePdfDocumentsAreNotSilentlyCollapsed() {
        val a=ReportRef(id="one",title="LFT",date="24-Apr-2026",
            url="https://www.nimsts.edu.in/HISInvestigationG5/a.pdf")
        val b=ReportRef(id="two",title="LFT",date="24-Apr-2026",
            url="https://www.nimsts.edu.in/HISInvestigationG5/b.pdf")
        val candidates=SourceMetadataReview.candidates(listOf(test),listOf(a,b))
        assertEquals(2,candidates.size)
        assertEquals(2,candidates.map { it.url }.distinct().size)
    }

    @Test fun restMetadataDoesNotPretendToBePhysicalSourcePdf() {
        val rest=ReportRef(id="rest",title="LFT",date="24-Apr-2026",
            sourceTag="nims_rest_reportList_context")
        assertTrue(SourceMetadataReview.candidates(listOf(test),listOf(rest)).isEmpty())
    }

    @Test fun displayedNimsReportDatesUseFullDayMonthYearAndClock() {
        assertEquals("24-04-2026",LabPanelMatrix.displayDate("2026-04-24"))
        assertEquals("29-09-2026 16:36:50",
            LabPanelMatrix.displayDateTime("2026-09-29 16:36:50.0"))
    }
}
