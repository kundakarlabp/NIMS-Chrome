package org.kundakarlab.nimsresults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic patient-safe regression cases for dashboard/pdf identity. */
class IntegratedResultsV115Test {
    private fun row(name: String, date: String, accession: String = "") =
        LabResult("r", name, "STAINING", date, "No organisms in the specimen",
            labNumber = accession)
    private fun report(name: String, date: String, id: Int = 1, lab: String = "",
                       dept: String = "Microbiology") =
        ReportRef(id.toString(), name, date, dept,
            "https://www.nimsts.edu.in/HISReportAction?synthetic=1&hmode=PRINTREPORT",
            labNumber = lab).copy(url = "https://www.nimsts.edu.in/test-$id.pdf")

    @Test fun allResultsTabIsImmediatelyAfterOverviewAndNoReportsTabRemains() {
        assertEquals(listOf(ResultsTab.OVERVIEW, ResultsTab.ALL,
            ResultsTab.LABS, ResultsTab.MICROBIOLOGY),
            ResultsTab.entries.take(4))
        assertFalse(ResultsTab.entries.any { it.name == "REPORTS" })
    }

    @Test fun gramsStainingAndGramSmearMatchByKindNotByAccidentalDate() {
        val results = listOf(row("GRAMS STAINING", "2025-07-08"))
        val match = ReportLinkResolver.candidates(results,
            listOf(report("Gram smear", "08-Jul-2025")))
        assertNull(match.verified)
        assertEquals(1, match.candidates.size)
        val far = ReportLinkResolver.candidates(results,
            listOf(report("Gram smear", "20-Jul-2025")))
        assertTrue(far.candidates.isEmpty())
    }

    @Test fun nearbyGenericMicrobiologyPdfIsCandidateOnly() {
        val results = listOf(row("GRAMS STAINING", "2025-07-08"))
        val result = ReportLinkResolver.candidates(results,
            listOf(report("Microbiology", "09-Jul-2025")))
        assertNull(result.verified)
        assertEquals(1, result.candidates.size)
    }

    @Test fun wrongPatientPdfCannotBecomeCandidateDespiteSameTestAndDate() {
        ReportTitleCache.clear()
        val pdf = report("Gram stain", "08-Jul-2025")
        assertTrue(ReportTitleCache.learn(pdf.url,
            "CR No: 111111111111111\nLab/Study No.: M23456\nGram stain",
            "111111111111111"))
        val found = ReportLinkResolver.candidates(
            listOf(row("Gram stain", "2025-07-08")),
            listOf(pdf), "222222222222222")
        assertTrue(found.candidates.isEmpty())
        assertNull(found.verified)
        ReportTitleCache.clear()
    }

    @Test fun inventoryLabNumberAloneMustNotVerifyPdf() {
        val result = ReportLinkResolver.candidates(
            listOf(row("Gram stain", "2025-07-08", "M2222")),
            listOf(report("Gram stain", "08-Jul-2025", lab = "M2222")))
        assertNull(result.verified)
    }

    @Test fun realPdfHeaderFieldsAreParsedWhenExplicitlyLabelled() {
        val header = PdfDocumentHeaders.parse(
            "CR No: 123456789012345\nLab/Study No.: M00567\n" +
            "Coll/Study Date: 08-Jul-2025 09:17\n" +
            "Reporting Date: 09-Jul-2025 15:22\n" +
            "Requisition Date: 07-Jul-2025 17:30")
        assertEquals("123456789012345", header.crNo)
        assertEquals("M00567", header.labNumber)
        assertEquals("08-Jul-2025 09:17", header.collectionDate)
        assertEquals("09-Jul-2025 15:22", header.reportedDate)
        assertEquals("07-Jul-2025 17:30", header.orderDate)
    }

    @Test fun gramStainPdfHeadingIsRecognized() {
        val title = OpaqueReportTitles.titleFromExtractedText(
            "NIMS PDF CR No: 123456789012345\nGRAMS STAINING\nNo organisms")
        assertEquals("Gram stain", title)
    }

    @Test fun matchedAccessionIsNotAutomaticallyAssignedToUnlinkedTests() {
        val rows = listOf(row("GRAMS STAINING", "2025-07-08"))
        val pdf = report("Gram stain", "08-Jul-2025", lab = "M00567")
        val matched = ReportLinkResolver.candidates(rows, listOf(pdf))
        assertNull(matched.verified)
        assertEquals(1, matched.candidates.size)
    }
}
