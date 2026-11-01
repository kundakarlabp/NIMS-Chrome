package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class ClinicalResultsV147Test {
    private fun row(test:String,parameter:String,value:String="1",
        date:String="2026-03-17")=LabResult(
        id=test+parameter,test=test,parameter=parameter,date=date,valueText=value)

    @Test fun signedFluidSpecimenIsRecognizedOnlyFromExplicitLabels() {
        assertEquals("Pleural fluid",PdfClinicalEvidence.specimen(
            "Sample Type/No : Pleural Fluid/P5631\nReporting Date: 17-Mar-2026"))
        assertEquals("CSF",PdfClinicalEvidence.specimen(
            "Specimen: CSF\nProtein 2.0"))
        assertEquals("Synovial fluid",PdfClinicalEvidence.specimen(
            "Sample Type/No: Synovial Fluid/839"))
        assertEquals("",PdfClinicalEvidence.specimen(
            "Nature Of Specimen: 3\nCSF/Pleural/Ascitic/Syno fld"))
    }

    @Test fun fluidPanelHeadingMustRemainVisible() {
        val test="CSF/Pleural/Ascitic/Syno fld, (Protein,SU"
        assertFalse(ClinicalResultsLayout.omitRoutineHeading(test,listOf(
            row(test,"Glucose","111"),row(test,"Protein","2.0"))))
        val cell="FLUID CELL COUNT (ASCITIC/PLEURAL/PERICARDIAL/CSF/OTHERS)"
        assertFalse(ClinicalResultsLayout.omitRoutineHeading(cell,listOf(
            row(cell,"Total cells","42"))))
    }

    @Test fun noPdfButtonForRoutineUrineAndViralScreen() {
        for(test in listOf("COMPLETE URINE EXAMINATION (CHEMISTRY + MICROSCOPY)",
            "HIV - ELFA","HCV Ab Rapid","HBS Ag RAPID","C REACTIVE PROTEIN (CRP)")) {
            assertTrue(test,ClinicalResultsLayout.omitRoutinePdf(
                test,listOf(row(test,"Result","Negative"))))
        }
        assertFalse(ClinicalResultsLayout.omitRoutinePdf(
            "Urine Aerobic Culture",listOf(row("Urine Aerobic Culture","Result","No growth"))))
    }

    @Test fun coombsCannotInferFromCom1NumericCode() {
        assertNull(PdfClinicalEvidence.coombs(
            "Indirect Coombs Test (GEL)","com1: 2"))
        assertEquals("Negative",PdfClinicalEvidence.coombs(
            "Indirect Coombs Test (GEL)",
            "Indirect Coombs Test (GEL)\nResult: Negative"))
        assertNull(PdfClinicalEvidence.coombs(
            "Direct Coombs Test (GEL)",
            "Indirect Coombs Test (GEL)\nResult: Negative"))
    }

    @Test fun sourceIndexDoesNotClaimProvenResultsAndReportDateNotInvented() {
        val study="Urine Aerobic Culture"
        val source=ReportRef(id="r",title=study,labNumber="U31383",
            sampleNumber="26IBC13549",collectionDateTime="2026-10-30")
        val results=ClinicalResultsLayout.microbiologyProvenance(
            listOf(row(study,"Result","No growth","2026-10-30")),
            listOf(source),verified=null,bloodCulture=false)
        assertTrue(results.any{it=="Report index · Lab U31383"})
        assertTrue(results.any{it=="Sample 26IBC13549"})
        assertFalse(results.any{it.startsWith("Reported ")})
        assertFalse(results.any{it.contains("Possible")})
        val verified=PdfDocumentHeader(crNo="999999999999999",
            labNumber="U31383",reportedDate="30-Oct-2026 11:55")
        val confirmed=ClinicalResultsLayout.microbiologyProvenance(
            listOf(row(study,"Result","No growth","2026-10-30")),
            listOf(source),verified,false)
        assertTrue(confirmed.contains("Lab U31383"))
        assertTrue(confirmed.any{it.startsWith("Reported ")})
    }
}
