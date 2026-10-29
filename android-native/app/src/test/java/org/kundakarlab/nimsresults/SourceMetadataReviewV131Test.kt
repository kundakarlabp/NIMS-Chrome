package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class SourceMetadataReviewV131Test {
    private val cbc = LabResult(
        id = "synthetic", test = "CBC+DIFF (HB, PCV,RBC INDICES)",
        parameter = "Hemoglobin", date = "2026-09-29",
        valueText = "14.7", numericValue = 14.7
    )

    @Test fun restReportListTitleAndDateAreNotPdfOrSpecimenEvidence() {
        val rest = ReportRef(
            id = "rest", title = "CBC+DIFF (HB, PCV,RBC INDICES)",
            date = "2026-09-29", department = "", url = "",
            sourceTag = "nims_rest_reportList_context"
        )
        val candidate = ReportRef(
            id = "original", title = "CBC+DIFF (HB, PCV,RBC INDICES)",
            date = "2026-09-29", department = "Clinical Pathology",
            url = "https://www.nimsts.edu.in/verified-original.pdf",
            labNumber = "26CP438696"
        )
        assertEquals(listOf(candidate),
            SourceMetadataReview.candidates(listOf(cbc),listOf(rest,candidate)))
    }

    @Test fun emptyTitleOnlyIndexRowDoesNotPretendToSupplySpecimenData() {
        val noUrl = ReportRef(
            id="empty", title="CBC+DIFF (HB, PCV,RBC INDICES)",
            date="2026-09-29", department="", url=""
        )
        assertTrue(SourceMetadataReview.candidates(listOf(cbc),listOf(noUrl)).isEmpty())
    }

    @Test fun dateTimeDisplayPreservesTimeWithoutAmbiguousYear() {
        assertEquals("29-09-2026 11:17:29",
            LabPanelMatrix.displayDateTime("2026-09-29 11:17:29.0"))
        assertEquals("29-09-2026 09:34",
            LabPanelMatrix.displayDateTime("29-Sep-2026 09:34"))
        assertEquals("Unverified",LabPanelMatrix.displayDateTime("Unverified"))
    }
}
