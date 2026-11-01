package org.kundakarlab.nimsresults

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class MicrobiologyOneLineV152Test {
    private val title="GRAMS STAINING"
    private val finding="NO POLYMORPHS / LYMPHOCYTES / ORGANISMS COULD BE SEEN IN THE GRAMS SMEAR OF THE SPECIMEN."
    private fun result(value:String=finding)=LabResult(
        id="g",test=title,parameter="Result",
        date="2026-10-30",valueText=value
    )
    private fun pdfHeader(lab:String="E18848")=PdfDocumentHeaders.parse("""
        CR No : 123456789012345
        Lab/Study No. : $lab
        Coll./Study Date : 30-Oct-2026 12:33
        Reporting Date : 30-Oct-2026 17:36
        Sample Type/No : Any Other Specimen/26BCT22946
    """.trimIndent())
    private val sourcePdf="""
        Department of Microbiology
        CR No : 123456789012345
        Lab/Study No. : E18848
        Coll./Study Date : 30-Oct-2026 12:33
        Reporting Date : 30-Oct-2026 17:36
        Sample Type/No : Any Other Specimen/26BCT22946
        Sample Processed: ASCITIC FLUID
        STAINING
        NO POLYMORPHS / LYMPHOCYTES / ORGANISMS COULD
        BE SEEN IN THE GRAMS SMEAR OF THE SPECIMEN.
    """.trimIndent()
    private fun candidate(lab:String="E18848")=ReportRef(
        id="pdf",title=title,labNumber=lab,
        sampleNumber="26BCT22946",
        collectionDateTime="2026-10-30 12:33",
        url="https://hospital.example/test.pdf"
    )

    @Test fun sampleProcessedBecomesTitleSpecimenAfterClinicalMatch() {
        val extracted=MicrobiologyReportDisplay.specimen(
            listOf(result()),candidate(),pdfHeader(),sourcePdf,
            LocalDate.parse("2026-10-30"))
        assertEquals("Ascitic fluid",extracted)
        assertEquals("ASCITIC FLUID",PdfSourceEvidence.specimen(sourcePdf))
    }

    @Test fun singleLineContainsLabBothTimesButNoSpecimenIdOrAuditText() {
        val parts=ClinicalResultsLayout.oneLineMicrobiologyMetadata(
            listOf(result()),listOf(candidate()),pdfHeader())
        assertEquals(
            "Lab E18848 · Col 30/10 12:33 · Rep 30/10 17:36",parts)
        assertFalse(parts.contains("26BCT22946"))
        assertFalse(parts.contains("candidate",true))
        assertFalse(parts.contains("index",true))
    }

    @Test fun missingVerifiedReportDateNeverCreatesReportedTime() {
        val onlyIndex=ClinicalResultsLayout.oneLineMicrobiologyMetadata(
            listOf(result()),listOf(candidate()),null)
        assertTrue(onlyIndex.contains("Lab E18848"))
        assertTrue(onlyIndex.contains("Col 30/10 12:33"))
        assertFalse(onlyIndex.contains("Rep"))
    }

    @Test fun doNotBorrowSpecimenAcrossDifferentAccessionsOrFindings() {
        assertEquals("",MicrobiologyReportDisplay.specimen(
            listOf(result()),candidate("E18849"),pdfHeader(),sourcePdf,
            LocalDate.parse("2026-10-30")))
        assertEquals("",MicrobiologyReportDisplay.specimen(
            listOf(result("GRAM POSITIVE COCCI SEEN IN CLUSTERS IN THIS SMEAR")),
            candidate(),pdfHeader(),sourcePdf,LocalDate.parse("2026-10-30")))
        assertEquals("",MicrobiologyReportDisplay.specimen(
            listOf(result()),candidate(),pdfHeader(),sourcePdf,
            LocalDate.parse("2026-10-29")))
        assertEquals("",MicrobiologyReportDisplay.specimen(
            listOf(result()),candidate(),pdfHeader(),sourcePdf.replace(
                "Sample Processed: ASCITIC FLUID",""),
            LocalDate.parse("2026-10-30")))
    }

    @Test fun noSourceMeansNoSpecimenInferenceFromAnyOtherSpecimen() {
        assertEquals("",MicrobiologyReportDisplay.specimen(
            listOf(result()),candidate(),pdfHeader(),
            "Sample Type/No: Any Other Specimen/26BCT22946\n"+finding,
            LocalDate.parse("2026-10-30")))
    }
}
