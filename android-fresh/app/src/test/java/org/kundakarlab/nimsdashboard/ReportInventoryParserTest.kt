package org.kundakarlab.nimsdashboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportInventoryParserTest {
    @Test
    fun parsesRestReportRowsAndVerifiesCr() {
        val payload = JSONObject(
            """
            {
              "patient": {"crNo":"331012600350500","patientName":"Test Patient"},
              "reports":[
                {
                  "reportId":"R1",
                  "reportName":"Complete Blood Count",
                  "reportDate":"15-Oct-2026",
                  "fileName":"cbc-15oct.pdf",
                  "crNo":"331012600350500"
                }
              ]
            }
            """.trimIndent()
        )
        val (reports, verified) = ReportInventoryParser.parseRest(payload, "331012600350500")
        assertTrue(verified)
        assertEquals(1, reports.size)
        assertTrue(reports.first().url.contains("PRINTREPORT"))
        assertTrue(reports.first().url.contains("cbc-15oct.pdf"))
    }

    @Test
    fun rejectsConflictingRestCr() {
        val payload = JSONObject(
            """{"reports":[{"reportName":"CBC","fileName":"cbc.pdf","crNo":"331012600350501"}]}"""
        )
        var failed = false
        try {
            ReportInventoryParser.parseRest(payload, "331012600350500")
        } catch (_: NimsIdentityMismatch) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun parsesViewReportHtmlAndBuildsSafePdfLink() {
        val html = """
            <html><body>
            <div>CR No: 331012600350500</div>
            <table>
              <tr>
                <td>15-Oct-2026</td>
                <td>Hematology</td>
                <td>Complete Blood Count</td>
                <td><button onclick="return printReport('cbc-15oct.pdf')">View Report</button></td>
              </tr>
            </table>
            </body></html>
        """.trimIndent()
        val reports = ReportInventoryParser.parseHtml(
            html,
            "https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt",
            "331012600350500"
        )
        assertEquals(1, reports.size)
        assertEquals("15-Oct-2026", reports.first().date)
        assertTrue(reports.first().url.contains("fileName=cbc-15oct.pdf"))
    }

    @Test
    fun rejectsUnsafeReportTokensAndForeignUrls() {
        assertEquals("", ReportInventoryParser.verifiedReportUrlForToken("../secret.pdf"))
        assertEquals("", ReportInventoryParser.verifiedReportUrlForToken("https://evil.example/x.pdf"))
        assertEquals(
            "",
            ReportInventoryParser.safeReportUrl(
                "https://evil.example/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt?hmode=PRINTREPORT&fileName=x.pdf"
            )
        )
        assertFalse(
            ReportInventoryParser.safeReportUrl(
                "https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt?hmode=PRINTREPORT&fileName=x.pdf"
            ).isBlank()
        )
    }
}
