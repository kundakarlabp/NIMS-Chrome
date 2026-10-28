package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class SourceEventReconciliationV126Test {
    private fun row(test: String = "AEROBIC CULTURE",
                    date: String = "2026-10-28",
                    lab: String = "", sample: String = "") =
        LabResult("synthetic", test, "Result", date, "Synthetic", labNumber = lab,
            sampleNumber = sample)
    private fun source(test: String = "AEROBIC CULTURE",
                       lab: String = "", sample: String = "",
                       collected: String = "27-Oct-2026 10:20",
                       reportNumber: String = "R-1",
                       stage: String = "FINAL") =
        ReportRef(id="synthetic",title=test, date=collected,
            labNumber=lab,sampleNumber=sample, reportNumber=reportNumber,
            reportStage=stage,collectionDateTime=collected)

    @Test fun neverLinkWithoutExplicitResultIdentifier() {
        val result=row()
        val decision=SourceEventReconciliation.assess(listOf(result),
            listOf(source(lab="LAB-1",sample="S-1")))
        assertEquals(SourceEventReconciliation.State.NO_RESULT_ID,decision.state)
        assertNull(decision.source)
        assertEquals("",result.labNumber)
        assertEquals("",result.collectionDateTime)
    }

    @Test fun exactLabAndSampleAndTitleMayVerifySourceIndexOnly() {
        val result=row(lab="LAB-1",sample="S-1")
        val match=source(lab="LAB-1",sample="S-1")
        val decision=SourceEventReconciliation.assess(listOf(result),listOf(match))
        assertEquals(SourceEventReconciliation.State.VERIFIED_INDEX,decision.state)
        assertEquals("LAB-1",decision.source?.labNumber)
        assertEquals("",result.collectionDateTime)
        assertTrue(decision.verified)
    }

    @Test fun identifierPunctuationIsNotDiscarded() {
        val decision=SourceEventReconciliation.assess(
            listOf(row(lab="LAB-1")),listOf(source(lab="LAB1")))
        assertEquals(SourceEventReconciliation.State.NO_INDEX_MATCH,decision.state)
    }

    @Test fun cannotMatchDifferentInvestigationSharingLabNumber() {
        val decision=SourceEventReconciliation.assess(
            listOf(row(lab="LAB-1")),listOf(source(test="GRAM STAIN",lab="LAB-1")))
        assertEquals(SourceEventReconciliation.State.NO_INDEX_MATCH,decision.state)
    }

    @Test fun sameLabWithDifferentSamplesIsNotConflated() {
        val decision=SourceEventReconciliation.assess(
            listOf(row(lab="LAB-1",sample="S-1")),listOf(source(lab="LAB-1",sample="S-2")))
        assertEquals(SourceEventReconciliation.State.NO_INDEX_MATCH,decision.state)
    }

    @Test fun repeatedSourceStagesRemainAmbiguous() {
        val decision=SourceEventReconciliation.assess(
            listOf(row(lab="LAB-1")),listOf(
                source(lab="LAB-1",reportNumber="R-1",stage="PRELIM"),
                source(lab="LAB-1",reportNumber="R-2",stage="FINAL")
            ))
        assertEquals(SourceEventReconciliation.State.AMBIGUOUS_INDEX,decision.state)
    }

    @Test fun mixedResultIdentifiersNeverMerge() {
        val decision=SourceEventReconciliation.assess(
            listOf(row(lab="LAB-1"),row(lab="LAB-2")),
            listOf(source(lab="LAB-1")))
        assertEquals(SourceEventReconciliation.State.CONFLICTING_RESULT_ID,decision.state)
    }

    @Test fun resultColumnDateCannotOverrideConflictingCollectionDate() {
        val decision=SourceEventReconciliation.assess(
            listOf(row(lab="LAB-1")),listOf(source(lab="LAB-1",collected="01-Jan-2025")))
        assertEquals(SourceEventReconciliation.State.DATE_CONFLICT,decision.state)
    }

    @Test fun duplicateExactSourceRowsDoNotCreateFalseAmbiguity() {
        val a=source(lab="LAB-1",sample="S-1")
        val decision=SourceEventReconciliation.assess(
            listOf(row(lab="LAB-1",sample="S-1")),listOf(a,a.copy(id="duplicate")))
        assertTrue(decision.verified)
    }

    @Test fun CBCNameAliasesAreExplicitButRequireActualID() {
        val decision=SourceEventReconciliation.assess(
            listOf(row(test="CBC (HB, PCV, TLC, PLT, PS)",lab="HEM-3")),
            listOf(source(test="CBC",lab="HEM-3")))
        assertTrue(decision.verified)
    }

    @Test fun twoSameDaySamplesRequireExplicitIdentifiers() {
        val sourceA=source(lab="LAB-1",sample="S-1")
        val sourceB=source(lab="LAB-2",sample="S-2")
        val unresolved=SourceEventReconciliation.assess(
            listOf(row()),listOf(sourceA,sourceB))
        assertFalse(unresolved.verified)
        val explicit=SourceEventReconciliation.assess(
            listOf(row(lab="LAB-2",sample="S-2")),listOf(sourceA,sourceB))
        assertEquals("LAB-2",explicit.source?.labNumber)
    }
}
