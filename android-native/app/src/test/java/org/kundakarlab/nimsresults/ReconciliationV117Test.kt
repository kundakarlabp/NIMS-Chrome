package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

/** Synthetic NIMS-like layouts without names, real CRs or clinical PHI. */
class ReconciliationV117Test {
    private val cr = "111111111111111"
    private fun url(index: Int) =
        "https://www.nimsts.edu.in/HISReportAction?hmode=PRINTREPORT&fileName=synthetic$index"
    private fun report(index: Int, lab: String, title: String) =
        ReportRef(index.toString(), title, "14-Jul-2025", "Bacteriology", url(index), labNumber = lab)
    private fun row(test: String, lab: String) =
        LabResult("id", test, "Result", "2025-07-14", "Reported test result",
            labNumber = lab)

    @Test fun nimsCollectionStudyTimestampWithDotIsExtracted() {
        val header = PdfDocumentHeaders.parse(
            "CR No: $cr   Lab/Study No. : E9999  Requisition Date: 14-Jul-2025 17:45\n" +
            "Patient Name: Synthetic  Coll./Study Date : 14-Jul-2025 18:02\n" +
            "Reporting Date : 15-Jul-2025 15:38")
        assertEquals("E9999", header.labNumber)
        assertEquals("14-Jul-2025 18:02", header.collectionDate)
        assertEquals("15-Jul-2025 15:38", header.reportedDate)
    }

    @Test fun stainAndCultureSameLabAccessionLinkToDifferentDocuments() {
        ReportTitleCache.clear()
        val stain = report(1, "E9999", "STAINING")
        val culture = report(2, "E9999", "AEROBIC CULTURE")
        assertTrue(ReportTitleCache.learnPages(stain.url, listOf(
            "CR No: $cr Lab/Study No. : E9999\nWard: OPD\n" +
            "Sample Processed: TRACHEAL ASPIRATE\nSTAINING\n" +
            "GRAMS SMEAR SHOWS GRAM NEGATIVE BACILLI"
        ), cr, "hashstain"))
        assertTrue(ReportTitleCache.learnPages(culture.url, listOf(
            "CR No: $cr Lab/Study No. : E9999\nWard: OPD\n" +
            "Sample Processed: TRACHEAL ASPIRATE\nAEROBIC CULTURE\n" +
            "CULTURE REPORT\nSCANTY GROWTH OF GRAM NEGATIVE BACILLI"
        ), cr, "hashculture"))
        assertEquals(stain.url, ReportLinkResolver.candidates(
            listOf(row("STAINING", "E9999")), listOf(stain, culture), cr).verified?.url)
        assertEquals(culture.url, ReportLinkResolver.candidates(
            listOf(row("AEROBIC CULTURE", "E9999")), listOf(stain, culture), cr).verified?.url)
        ReportTitleCache.clear()
    }

    @Test fun coagAndChemistrySameStudyNumberAreNotConfused() {
        ReportTitleCache.clear()
        val chemistry = report(3, "202599O0123", "Liver function")
        val coag = report(4, "202599O0123", "PT INR APTT")
        assertTrue(ReportTitleCache.learnPages(chemistry.url,
            listOf("CR No: $cr Lab/Study No. : 202599O0123\n" +
                "Department of Biochemistry\nRoutine Chemistry\nWard/OPD: OPD\n" +
                "LIVER FUNCTION TESTS:\nSerum AST (SGOT) 38 U/L"), cr))
        assertTrue(ReportTitleCache.learnPages(coag.url,
            listOf("CR No: $cr Lab/Study No. : 202599O0123\n" +
                "Coagulation Chemistry\nWard/OPD: OPD\n" +
                "Prothrombin Time (PT) 14.7 Sec\nPlasma APTT 27.4 Sec"), cr))
        assertEquals(coag.url, ReportLinkResolver.candidates(
            listOf(row("PT INR APTT", "202599O0123")),
            listOf(chemistry, coag), cr).verified?.url)
        assertEquals(chemistry.url, ReportLinkResolver.candidates(
            listOf(row("Serum creatinine", "202599O0123")),
            listOf(chemistry, coag), cr).verified?.url)
        ReportTitleCache.clear()
    }

    @Test fun repeatedRadiologyPageHeadersProduceSingleWholeStudy() {
        val pages = listOf(
            "Department of Radio Diagnosis\nCR No: $cr Lab/Study No. : 5303\n" +
                "Ward: OPD\nEXAMINATION PERFORMED: CT ABDOMEN\n" +
                "Findings begin here.\nTest results relate only to item received",
            "Department of Radio Diagnosis\nCR No: $cr Lab/Study No. : 5303\n" +
                "Ward: OPD\nContrast leak through stomach.\nIMPRESSION:\nGastric perforation.",
            "Department of Radio Diagnosis\nCR No: $cr Lab/Study No. : 5303\n" +
                "Ward: OPD\nSplenic infarct.\n******** END OF THE REPORT ********"
        )
        val parts = PdfSourceEvidence.parts(pages, cr)
        assertEquals(1, parts.size)
        assertEquals(3, parts.single().endPage)
        assertTrue(parts.single().text.contains("Gastric perforation"))
        assertTrue(parts.single().text.contains("Splenic infarct"))
        assertFalse(parts.single().text.contains("Test results relate"))
    }

    @Test fun duplicatePhysicalPdfIsNotDisplayedTwice() {
        ReportTitleCache.clear()
        val pdf1 = report(5, "E9999", "AEROBIC CULTURE")
        val pdf2 = report(6, "E9999", "AEROBIC CULTURE")
        val source = listOf("CR No: $cr Lab/Study No.: E9999\nAEROBIC CULTURE\nCULTURE REPORT")
        assertTrue(ReportTitleCache.learnPages(pdf1.url, source, cr, "samehexsha"))
        assertTrue(ReportTitleCache.learnPages(pdf2.url, source, cr, "samehexsha"))
        val feed = UnifiedResultsFeed.entries(ClinicalBundle(
            PatientInfo(crNo=cr), emptyList(), listOf(pdf1,pdf2)))
        assertEquals(1, feed.size)
        ReportTitleCache.clear()
    }

    @Test fun linkedPdfDoesNotAppearAsSeparateReportCard() {
        ReportTitleCache.clear()
        val pdf = report(7, "E9999", "STAINING")
        assertTrue(ReportTitleCache.learnPages(pdf.url,
            listOf("CR No: $cr Lab/Study No.: E9999\nSTAINING\nGram negative bacilli"),
            cr))
        val feed = UnifiedResultsFeed.entries(ClinicalBundle(
            PatientInfo(crNo=cr), listOf(row("STAINING", "E9999")), listOf(pdf)))
        assertEquals(1, feed.size)
        assertNull(feed.single().report)
        ReportTitleCache.clear()
    }

    @Test fun separateBloodCultureBottleAccessionsRemainSeparate() {
        val set = "25BCT12345"
        val p1 = PdfSourceEvidence.parts(listOf(
            "CR No: $cr Lab/Study No.: B11111\nSample Type/No: Specimen/$set\n" +
            "Ward: OPD\nFAN AEROBIC BLOOD CULTURE Final Report\nNo growth"),cr)
        val p2 = PdfSourceEvidence.parts(listOf(
            "CR No: $cr Lab/Study No.: B22222\nSample Type/No: Specimen/$set\n" +
            "Ward: OPD\nFAN AEROBIC BLOOD CULTURE Final Report\nGrowth"),cr)
        assertEquals("B11111",p1.single().header.labNumber)
        assertEquals("B22222",p2.single().header.labNumber)
        assertEquals(set,p1.single().sampleNumber)
        assertEquals(set,p2.single().sampleNumber)
    }

    @Test fun wrongCrNeverLearnsAuthenticatedSourceParts() {
        ReportTitleCache.clear()
        val pdf = report(8,"E9999","STAINING")
        assertFalse(ReportTitleCache.learnPages(pdf.url,
            listOf("CR No: 999999999999999 Lab/Study No.: E9999\nSTAINING"),cr))
        assertTrue(ReportTitleCache.sections(pdf.url).isEmpty())
        ReportTitleCache.clear()
    }
}
