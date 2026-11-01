package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class ClinicalResultsV143Test {
    private fun row(test:String, parameter:String, value:String, date:String="2026-05-07") =
        LabResult(id=test+parameter,test=test,parameter=parameter,
            date=date,valueText=value)

    @Test fun liverFunctionAlkalinePhosphataseAlwaysBeforeProteinAlbumin() {
        val test="Liver Function Tests"
        val r=listOf(row(test,"Serum Total Protein","6.8"),
            row(test,"Serum Albumin","4.5"),row(test,"Sr. Alk. Phosphatase","92"),
            row(test,"Serum AST (SGOT)","19"),row(test,"Serum ALT (SGPT)","19"),
            row(test,"Bilirubin (direct)","0.1"),row(test,"Bilirubin (total)","0.3"))
        assertEquals(listOf("Bilirubin (total)","Bilirubin (direct)",
            "Serum AST (SGOT)","Serum ALT (SGPT)","Sr. Alk. Phosphatase",
            "Serum Total Protein","Serum Albumin"),
            ClinicalResultsLayout.ordered(test,r).map{it.parameter})
    }

    @Test fun cultureSourceIndexMetadataIsExplicitlyAttributed() {
        val test="Fan Blood Culture(1 set 2 bottle) Final"
        val values=listOf(row(test,"Result","CULTURE SHOWS NO GROWTH"))
        val index=ReportRef(id="p",title=test,date="2026-04-22",
            url="https://www.nimsts.edu.in/r.pdf",
            labNumber="B15336",sampleNumber="BLOOD-15336",
            collectionDateTime="22-Apr-2026 08:00")
        val provenance=ClinicalResultsLayout.cultureProvenance(values,listOf(index))
        assertTrue(provenance.contains("Source-index lab B15336"))
        assertTrue(provenance.contains("Source-index sample BLOOD-15336"))
        assertTrue(provenance.contains("Source-index collected 22-Apr-2026 08:00"))
        assertFalse(provenance.any{it.startsWith("Sent ")})
    }

    @Test fun ambiguousCultureSourcesDoNotSupplyLabOrCollectionDate() {
        val test="Fan Blood Culture(1 set 2 bottle) Final"
        val row=row(test,"Result","NO GROWTH")
        val a=ReportRef(id="a",title=test,labNumber="B15336",
            collectionDateTime="2026-04-22")
        val b=a.copy(id="b",labNumber="B15338")
        val provenance=ClinicalResultsLayout.cultureProvenance(listOf(row),listOf(a,b))
        assertTrue(provenance.isEmpty())
    }

    @Test fun enquiryIndexMatchingRecognizesCbcSynonymsOnlyWithRealSharedIdentifier() {
        val enquiry=EnquiryOrder("CBC+DIFF","Report generated",sampleNumber="S001")
        val index=ReportRef(id="r",title="CBC",sampleNumber="S001",labNumber="26CP188246")
        assertEquals(EnquirySourceBridge.State.VERIFIED_INDEX,
            EnquirySourceBridge.assess(enquiry,listOf(index)).state)
        assertEquals(EnquirySourceBridge.State.MISSING_IDENTIFIER,
            EnquirySourceBridge.assess(enquiry.copy(sampleNumber=""),
                listOf(index)).state)
    }

    @Test fun enquiryCultureBottleAndStageNeverCrossLink() {
        val enquiry=EnquiryOrder("Fan Blood Culture(1 set 2 bottle) Final",
            "Report generated",labNumber="B15336")
        val wrongBottle=ReportRef(id="w",title="Fan Blood Culture(2 set 2 bottle) Final",
            labNumber="B15336")
        val interim=ReportRef(id="i",title="Fan Blood Culture(1 set 2 bottle) 48 hrs",
            labNumber="B15336")
        val right=ReportRef(id="f",title="Fan Blood Culture(1 set 2 bottle) Final",
            labNumber="B15336")
        assertEquals(EnquirySourceBridge.State.VERIFIED_INDEX,
            EnquirySourceBridge.assess(enquiry,listOf(wrongBottle,interim,right)).state)
        assertEquals("f",EnquirySourceBridge.assess(enquiry,
            listOf(wrongBottle,interim,right)).source?.id)
    }

    @Test fun urineEncodedDropdownsStillNotFabricatedAsMeasuredValues() {
        val cue="COMPLETE URINE EXAMINATION (CHEMISTRY + MICROSCOPY)"
        assertEquals("7 (HIS code)",ClinicalResultsLayout.urineCodeDisplay(
            row(cue,"Specific Gravity(Result)","7")))
        assertFalse(ClinicalResultsLayout.urineChemistryCode(
            row(cue,"Specific Gravity(Result)","1.018")))
    }
    @Test fun cultureFinalMayReviewUniqueEarlierCollectionPdfButCbcMayNot() {
        val culture="Fan Blood Culture(1 set 2 bottle) Final"
        val study=listOf(row(culture,"Result","NO GROWTH","2026-04-28"))
        val source=ReportRef(id="blood",title=culture,
            date="2026-04-22",collectionDateTime="2026-04-22",
            labNumber="B15336",url="https://www.nimsts.edu.in/blood.pdf",
            sourceTag="nims_html_index")
        assertEquals(listOf("B15336"),
            SourceMetadataReview.pdfCandidates(study,listOf(source)).map{it.labNumber})
        val cbc="CBC (HB, PCV,TLC, PLT, PS)"
        val cbcSource=source.copy(id="cbc",title=cbc,labNumber="26CP169832",
            url="https://www.nimsts.edu.in/cbc.pdf")
        assertTrue(SourceMetadataReview.pdfCandidates(listOf(row(cbc,"Hb","13.1",
            "2026-04-28")),listOf(cbcSource)).isEmpty())
    }

    @Test fun cultureCandidatesRejectAnotherSetOrStageEvenIfDateNear() {
        val result=listOf(row("Fan Blood Culture(1 set 2 bottle) Final",
            "Result","NO GROWTH","2026-04-28"))
        val mismatch=ReportRef(id="bad",title="Fan Blood Culture(2 set 2 bottle) Final",
            date="2026-04-22",collectionDateTime="2026-04-22",
            labNumber="B15338",url="https://www.nimsts.edu.in/wrong.pdf",
            sourceTag="nims_html_index")
        assertTrue(SourceMetadataReview.pdfCandidates(result,listOf(mismatch)).isEmpty())
    }

}
