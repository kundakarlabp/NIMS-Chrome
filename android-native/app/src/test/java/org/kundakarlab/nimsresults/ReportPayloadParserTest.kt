package org.kundakarlab.nimsresults

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportPayloadParserTest {
    @Test
    fun mapsSafeReportToken() {
        val payload = JSONObject(
            """
            {
              "patient":{"crNo":"123456789012345"},
              "reports":[
                {"reportName":"CBC","reportDate":"15-Oct-2026","fileName":"cbc-15.pdf","crNo":"123456789012345"}
              ]
            }
            """.trimIndent()
        )
        val reports = ReportPayloadParser.parseRest(payload, "123456789012345")
        assertEquals(1, reports.size)
        assertTrue(reports[0].url.contains("hmode=PRINTREPORT"))
        assertTrue(reports[0].url.contains("cbc-15.pdf"))
    }

    @Test
    fun mapsViewReportHtml() {
        val html = """
            <html><body>
            <div>CR No: 123456789012345</div>
            <table><tr>
              <td>15-Oct-2026</td><td>Hematology</td><td>Complete Blood Count</td>
              <td><button onclick="printReport('cbc-15.pdf')">View Report</button></td>
            </tr></table>
            </body></html>
        """.trimIndent()
        val reports = ReportPayloadParser.parseHtml(
            html,
            NimsUrls.CR_REPORTS,
            "123456789012345"
        )
        assertEquals(1, reports.size)
        assertTrue(reports[0].url.contains("cbc-15.pdf"))
    }

    @Test
    fun rejectsUnsafePdfTargets() {
        assertEquals("", ReportPayloadParser.reportUrlForToken("../secret.pdf"))
        assertEquals("", ReportPayloadParser.reportUrlForToken("https://evil.example/x.pdf"))
        assertEquals("", ReportPayloadParser.safeReportUrl("https://evil.example/file.pdf"))
        assertFalse(
            ReportPayloadParser.safeReportUrl(
                "https://www.nimsts.edu.in${NimsUrls.REPORT_PATH}?hmode=PRINTREPORT&fileName=x.pdf"
            ).isBlank()
        )
    }
}
