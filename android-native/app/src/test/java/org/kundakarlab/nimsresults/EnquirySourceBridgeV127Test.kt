package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class EnquirySourceBridgeV127Test {
    private fun order(sample:String="", lab:String="",test:String="CBC")=
        EnquiryOrder(test,"Report Generated",sampleNumber=sample,labNumber=lab)
    private fun report(sample:String="",lab:String="",test:String="CBC",stage:String="FINAL")=
        ReportRef(id="id",title=test,sampleNumber=sample,labNumber=lab,reportStage=stage)

    @Test fun packingListIsNotInterpretedAsLabOrSampleIdentity() {
        val e=order().copy(packingListNumber="PK-777")
        val decision=EnquirySourceBridge.assess(e,listOf(report(lab="PK-777")))
        assertEquals(EnquirySourceBridge.State.MISSING_IDENTIFIER,decision.state)
    }

    @Test fun sameSampleAndInvestigationVerifiesReportIndexOnly() {
        val decision=EnquirySourceBridge.assess(order("S-101"),listOf(report("S-101","L-22")))
        assertEquals(EnquirySourceBridge.State.VERIFIED_INDEX,decision.state)
        assertEquals("L-22",decision.source?.labNumber)
    }

    @Test fun sameSampleDifferentTestIsNotLinked() {
        val decision=EnquirySourceBridge.assess(
            order("S-101"),listOf(report("S-101",test="AEROBIC CULTURE")))
        assertEquals(EnquirySourceBridge.State.UNMATCHED,decision.state)
    }

    @Test fun repeatedStagesRemainAmbiguousForOrderToIndexLink() {
        val decision=EnquirySourceBridge.assess(order("S-101"),
            listOf(report("S-101",stage="48 HOUR"),report("S-101",stage="FINAL")))
        assertEquals(EnquirySourceBridge.State.AMBIGUOUS,decision.state)
    }

    @Test fun conflictingLabNumberCannotBeOverruledByMatchingSample() {
        val decision=EnquirySourceBridge.assess(order("S-101","L-99"),
            listOf(report("S-101","L-01")))
        assertEquals(EnquirySourceBridge.State.UNMATCHED,decision.state)
    }

    @Test fun separateSameDaySamplesRemainSeparateEvenWithSameTestName() {
        val reports=listOf(report("S-101","L-1"),report("S-102","L-2"))
        assertEquals("L-2",EnquirySourceBridge.assess(
            order("S-102"),reports).source?.labNumber)
        assertEquals("L-1",EnquirySourceBridge.assess(
            order("S-101"),reports).source?.labNumber)
    }

    @Test fun coverageCountsPendingOrdersWithoutClaimingMissingResults() {
        val bundle=ClinicalBundle(
            patient=PatientInfo(crNo="111111111111111"),
            results=emptyList(),reports=listOf(report("S-1","L-1")),
            enquiryOrders=listOf(
                order("S-1").copy(status="Report Generated"),
                order("S-2").copy(status="Sample Collected"),
                order().copy(status="Requisition Raised")
            )
        )
        val c=EnquirySourceBridge.coverage(bundle)
        assertEquals(3,c.total)
        assertEquals(1,c.linked)
        assertEquals(1,c.unmatched)
        assertEquals(1,c.missingIds)
        assertEquals(2,c.pending)
        assertEquals(1,c.reported)
    }
}
