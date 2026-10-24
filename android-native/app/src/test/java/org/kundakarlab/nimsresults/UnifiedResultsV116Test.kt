package org.kundakarlab.nimsresults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage for multi-accession PDFs and one unified result feed. */
class UnifiedResultsV116Test {
    private val cr = "123456789012345"
    private val url = "https://www.nimsts.edu.in/HISReportAction?hmode=PRINTREPORT&fileName=example.pdf"
    private fun row(test: String, accession: String): LabResult =
        LabResult(test + accession, test, "Result", "2025-07-08",
            "As reported", labNumber = accession)
    private val report = ReportRef("r1", "Combined laboratory report",
        "08-Jul-2025", "Pathology", url)

    @Test fun pdfWithTwoStudiesPreservesEachLabNumberAndTimestamp() {
        ReportTitleCache.clear()
        assertTrue(ReportTitleCache.learnPages(url, listOf(
            "CR No: $cr\nLab/Study No.: BB12345\n" +
                "Collection Date: 08-Jul-2025 10:05\n" +
                "Reporting Date: 08-Jul-2025 14:30\nCBC: Haemoglobin 10",
            "CR No: $cr\nLab/Study No.: BB67890\n" +
                "Collection Date: 08-Jul-2025 11:20\n" +
                "Reporting Date: 09-Jul-2025 08:10\n" +
                "CRP: 45 mg/L",
            "CR No: $cr\nAdditional report narrative on page 3"
        ), cr))
        assertEquals(3, ReportTitleCache.sections(url).size)
        assertEquals(setOf("BB12345", "BB67890"), ReportTitleCache.accessions(url))
        assertEquals("08-Jul-2025 10:05",
            ReportTitleCache.sections(url)[0].header.collectionDate)
        assertEquals("09-Jul-2025 08:10",
            ReportTitleCache.sections(url)[1].header.reportedDate)
        assertNotNull(ReportLinkResolver.candidates(listOf(row("CBC", "BB12345")),
            listOf(report), cr).verified)
        assertNotNull(ReportLinkResolver.candidates(listOf(row("CRP", "BB67890")),
            listOf(report), cr).verified)
        ReportTitleCache.clear()
    }

    @Test fun twoLabHeadersOnSamePageRemainSeparateSections() {
        val parts = PdfSourceEvidence.parts(listOf(
            "CR No: $cr\nLab/Study No.: BB12345\n" +
                "Collection Date: 08-Jul-2025 10:05\nCBC 10\n" +
                "Lab/Study No.: BB67890\n" +
                "Collection Date: 08-Jul-2025 11:20\nCRP 45"
        ), cr)
        assertEquals(2, parts.size)
        assertEquals("BB12345", parts[0].header.labNumber)
        assertEquals("BB67890", parts[1].header.labNumber)
        assertFalse(parts[1].text.contains("CBC 10"))
    }

    @Test fun samePhysicalPdfIsRenderedOnceEvenWhenUsedByMultipleStudies() {
        val bundle = ClinicalBundle(PatientInfo(crNo = cr),
            listOf(row("CBC", "BB12345"), row("CRP", "BB67890")),
            listOf(report, report.copy(id = "other list row")))
        val feed = UnifiedResultsFeed.entries(bundle)
        assertEquals(3, feed.size)
        assertEquals(2, feed.count { it.rows.isNotEmpty() })
        assertEquals(1, feed.count { it.report != null })
    }

    @Test fun sourcePdfRemainsVisibleWithoutAnyStructuredResults() {
        val bundle = ClinicalBundle(PatientInfo(crNo = cr), emptyList(),
            listOf(report))
        assertEquals(1, UnifiedResultsFeed.entries(bundle).size)
    }

    @Test fun wrongCrInAnyPageBlocksEvidenceAndAutoLinking() {
        ReportTitleCache.clear()
        assertFalse(ReportTitleCache.learnPages(url, listOf(
            "CR No: $cr\nLab/Study No.: BB12345",
            "CR No: 999999999999999\nLab/Study No.: BB67890"
        ), cr))
        assertTrue(ReportTitleCache.sections(url).isEmpty())
        assertNull(ReportLinkResolver.candidates(listOf(row("CBC", "BB12345")),
            listOf(report), cr).verified)
        ReportTitleCache.clear()
    }

    @Test fun missingLabNumberDoesNotInheritUnrelatedPdfAccession() {
        ReportTitleCache.clear()
        assertTrue(ReportTitleCache.learnPages(url, listOf(
            "CR No: $cr\nLab/Study No.: BB12345\nCBC: Haemoglobin 10"
        ), cr))
        val unknown = row("CRP", "").copy(date = "2024-01-01")
        assertNull(ReportLinkResolver.candidates(listOf(unknown),
            listOf(report), cr).verified)
        ReportTitleCache.clear()
    }
}
