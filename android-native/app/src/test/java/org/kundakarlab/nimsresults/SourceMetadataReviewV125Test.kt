package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class SourceMetadataReviewV125Test {
    private fun row(test: String, date: String, lab: String = "") =
        LabResult("synthetic",test,"Result",date,"No change",labNumber=lab)
    private fun report(title: String, collected: String, lab: String, sample: String) =
        ReportRef("synthetic",title,collected,"Bacteriology", "",
            labNumber=lab,sampleNumber=sample,collectionDateTime=collected)

    @Test fun candidateIdentifierIsVisibleButNeverAssignedToBulkResult() {
        val result=row("AEROBIC CULTURE","2026-10-28")
        val candidate=report("AEROBIC CULTURE","27-Oct-2026 09:05","LAB-1","S-2")
        val found=SourceMetadataReview.candidates(listOf(result),listOf(candidate))
        assertEquals(1,found.size)
        assertEquals("LAB-1",found.single().labNumber)
        assertTrue(result.labNumber.isBlank())
        assertTrue(result.collectionDateTime.isBlank())
    }

    @Test fun onlySameTypeAndReasonablePriorCollectionDateIncluded() {
        val result=row("AEROBIC CULTURE","2026-10-28")
        val valid=report("AEROBIC CULTURE","25-Oct-2026 10:30","L-1","S-1")
        val wrongType=report("FUNGAL CULTURE","25-Oct-2026 10:30","L-2","S-2")
        val tooOld=report("AEROBIC CULTURE","01-Sep-2026","L-3","S-3")
        val future=report("AEROBIC CULTURE","30-Oct-2026","L-4","S-4")
        assertEquals(listOf("L-1"), SourceMetadataReview.candidates(listOf(result),
            listOf(wrongType,tooOld,future,valid)).map { it.labNumber })
    }

    @Test fun noDataOrMalformedDateNeverProducesCandidate() {
        val c=report("CBC","27-Oct-2026","L","S")
        assertTrue(SourceMetadataReview.candidates(emptyList(),listOf(c)).isEmpty())
        assertTrue(SourceMetadataReview.candidates(listOf(row("CBC","not-a-date")),listOf(c)).isEmpty())
    }

    @Test fun cbcPresentationTitleFindsSourceCbcUnderExplicitManualReview() {
        val r=row("CBC (HB, PCV,TLC, PLT, PS)","2026-10-27")
        val c=report("CBC","27-Oct-2026 08:00","L-8","S-8")
        assertEquals(1,SourceMetadataReview.candidates(listOf(r),listOf(c)).size)
    }
}
