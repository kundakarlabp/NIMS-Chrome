package org.kundakarlab.nimsresults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportIdentityV111Test {
    private fun lab(lab: String = "", date: String = "2026-04-11") =
        LabResult(id="lab", test="Urine Aerobic Culture", parameter="Result",
            date=date, valueText="No growth", labNumber=lab)

    private fun report(no: String, date: String = "11-Apr-2026", id: String = no) =
        ReportRef(id, "Urine Aerobic Culture", date, "Microbiology",
            "https://www.nimsts.edu.in/example-" + id, labNumber=no)

    @Test fun separateRequisitionAndReportingDatesPreserveSourceMeaning() {
        val header = PdfDocumentHeaders.parse(
            "CR No: 123456789012345\nLab/Study No. : 26T010443\n" +
            "Requisition Date : 11-Apr-2026 13:11   Coll/Study Date : 11-Apr-2026 13:13\n" +
            "Reporting Date : 11-Apr-2026 17:14"
        )
        assertEquals("11-Apr-2026 13:11", header.orderDate)
        assertEquals("11-Apr-2026 13:13", header.collectionDate)
        assertEquals("11-Apr-2026 17:14", header.reportedDate)
    }

    @Test fun compoundAccessionCanMatchExactlyOneVerifiedInventoryReport() {
        val rows = listOf(lab("26T010443"))
        val reports = listOf(report("T048/26T010443", id="one"),report("T049/26T010444",id="two"))
        val result = ReportLinkResolver.candidates(rows,reports,"123456789012345")
        assertEquals("one",result.verified?.id)
        assertEquals(1,result.candidates.size)
    }

    @Test fun duplicateAccessionsNeverTriggerAutomaticOpen() {
        val rows = listOf(lab("26T010443"))
        val reports = listOf(report("T048/26T010443",id="one"),report("T049/26T010443",id="two"))
        assertNull(ReportLinkResolver.candidates(rows,reports).verified)
    }

    @Test fun dateTitleSimilarityIsOnlyCandidateNotVerifiedIdentity() {
        val result = ReportLinkResolver.candidates(
            listOf(lab()),listOf(report("T048/26T010443")), "123456789012345")
        assertNull(result.verified)
        assertEquals(1,result.candidates.size)
    }

    @Test fun negativeOrPositiveBloodGroupIsNeverInferredFromCodes() {
        val source = """
           Department of Immuno Haematology And Blood Transfusion
           Grouping and Antibody screening
           Anti-A  Anti-B  Rh    A1 Cells  B Cells
           Neg     Neg     4+    4+       4+
           RESULT: O POSITIVE
           2. Antibody Screening Negative
        """.trimIndent()
        val answer = PdfClinicalSummaries.bloodGrouping(source)
        assertEquals("O Positive",answer?.group)
        assertEquals("Negative",answer?.antibodyScreen)
        assertNull(PdfClinicalSummaries.bloodGrouping("Transfusion; RESULT: O POSITIVE"))
        assertNull(PdfClinicalSummaries.bloodGrouping("2. Antibody Screening Negative"))
    }

    @Test fun learnedSourceHeaderMustMatchSelectedPatient() {
        val uri="https://www.nimsts.edu.in/authorized-sample.pdf"
        ReportTitleCache.clear()
        assertFalse(ReportTitleCache.learn(uri, "Grouping and Antibody screening", "123456789012345"))
        assertNull(ReportTitleCache.get(uri))
        assertTrue(ReportTitleCache.learn(uri,
            "CR No : 123456789012345\nGrouping and Antibody screening\nRESULT: O POSITIVE",
            "123456789012345"))
        assertEquals("Blood grouping and antibody screening",ReportTitleCache.get(uri))
        ReportTitleCache.clear()
    }
}
