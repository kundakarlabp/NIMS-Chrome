package org.kundakarlab.nimsresults

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class MicrobiologySourceV152Test {
    private fun result(test:String,value:String)=LabResult(
        id=test,date="2026-10-30",test=test,parameter="Result",valueText=value)
    private fun report(test:String,lab:String,id:String)=ReportRef(
        id=id,title=test,labNumber=lab,date="2026-10-30",
        collectionDateTime="2026-10-30 12:33",
        url="https://hospital.example/"+id+".pdf")
    private val noGrowth="CULTURE SHOWS NO GROWTH AEROBICALLY AFTER INCUBATION FOR ABOUT 36to 48 HOURS."

    private fun pdf(test:String,lab:String,body:String,specimen:String="Ascitic fluid") = """
        Department of Microbiology
        CR No : 123456789012345
        Lab/Study No. : $lab
        Coll./Study Date : 30-Oct-2026 12:33
        Reporting Date : 30-Oct-2026 17:36
        $test
        Sample Processed: $specimen
        $body
        ******** END OF REPORT ********
    """.trimIndent()

    @Test fun twoCultureCandidatesRequireBothInspectedAndExactlyOneMatchedNarrative() {
        val first=report("AEROBIC CULTURE","E10011","a")
        val second=report("AEROBIC CULTURE","E10012","b")
        val rows=listOf(result("AEROBIC CULTURE",noGrowth))
        ReportTitleCache.clear()
        try {
            assertTrue(ReportTitleCache.learnPages(first.url,listOf(
                pdf("AEROBIC CULTURE","E10011",noGrowth)),
                "123456789012345"))
            assertNull(MicrobiologyReportDisplay.uniqueReport(
                rows,listOf(first,second),"123456789012345",
                LocalDate.parse("2026-10-30")))
            assertTrue(ReportTitleCache.learnPages(second.url,listOf(
                pdf("AEROBIC CULTURE","E10012",
                    "CULTURE SHOWS GROWTH OF ENTEROCOCCUS")),
                "123456789012345"))
            val unique=MicrobiologyReportDisplay.uniqueReport(
                rows,listOf(first,second),"123456789012345",
                LocalDate.parse("2026-10-30"))
            assertEquals("E10011",unique?.header?.labNumber)
            assertEquals("Ascitic fluid",unique?.specimen)
            assertTrue(ReportTitleCache.learnPages(second.url,listOf(
                pdf("AEROBIC CULTURE","E10012",noGrowth,"Pleural fluid")),
                "123456789012345"))
            assertNull(MicrobiologyReportDisplay.uniqueReport(
                rows,listOf(first,second),"123456789012345",
                LocalDate.parse("2026-10-30")))
        } finally { ReportTitleCache.clear() }
    }

    @Test fun calcofluorAndKohAliasesStaySeparate() {
        val calco=result("FUNGAL FLUORESCENCE STAINING (CALCOFLUOR)",
            "CALCOFLOUR WHITE MOUNT SHOWS NO FUNGAL ELEMENTS.")
        val koh=result("FUNGAL KOH PREPARATION",
            "KOH PREPARATION SHOWS NO FUNGAL ELEMENTS.")
        val pdfs=listOf(
            report("Fungal Staining (Calcoflour)","E10015","calco"),
            report("KOH Mount (Fungal)","E10016","koh"))
        assertEquals("calco",SourceMetadataReview.pdfCandidates(
            listOf(calco),pdfs).single().id)
        assertEquals("koh",SourceMetadataReview.pdfCandidates(
            listOf(koh),pdfs).single().id)
    }

    @Test fun kohNegativeDoesNotImplyFinalNegativeFungalCulture() {
        val findings=FungalReportSummary.extract("FUNGAL KOH PREPARATION",
            "KOH PREPARATION SHOWS NO FUNGAL ELEMENTS. " +
                "PRELIMINARY REPORT OF FUNGAL CULTURE AFTER ONE WEEK")
        assertEquals(2,findings.size)
        assertEquals("KOH",findings[0].label)
        assertEquals("No fungal elements seen",findings[0].result)
        assertEquals("Fungal culture",findings[1].label)
        assertEquals("Preliminary report after one week",findings[1].result)
    }

    @Test fun combinedCoombsTableHasIndependentTracesAndIatNegative() {
        val table="""
            Direct Coombs Test    Traces     Control: Neg
            Indirect Coombs Test
            Result: NEGATIVE
            Comments: For further workup advice Monospecific DAT
        """.trimIndent()
        assertEquals("Traces",PdfClinicalEvidence.coombs(
            "Direct Coombs Test (GEL)",table))
        assertEquals("Negative",PdfClinicalEvidence.coombs(
            "Indirect Coombs Test (GEL)",table))
        assertNull(PdfClinicalEvidence.coombs(
            "Direct Coombs Test (GEL)","Direct Coombs Test\nControl: Neg"))
        assertNull(PdfClinicalEvidence.coombs(
            "Direct Coombs Test (GEL)",
            "Indirect Coombs Test\nResult: Negative"))
    }

    @Test fun conflictingPatientReactionsAreRejected() {
        assertNull(PdfClinicalEvidence.coombs("Direct Coombs Test",
            "Direct Coombs Test\nTraces\nResult: Negative\nControl: Neg"))
    }
}
