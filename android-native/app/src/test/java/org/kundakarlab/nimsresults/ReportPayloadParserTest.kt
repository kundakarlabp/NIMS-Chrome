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

    @Test
    fun restIndexSupportsMultipleVerifiedSameDayReports() {
        val payload = JSONObject(
            """
            {"patient":{"crNo":"123456789012345"},"reports":[
              {"reportName":"Blood culture","reportDate":"15-Oct-2026",
               "fileName":"blood-a.pdf","crNo":"123456789012345"},
              {"reportName":"Blood culture","reportDate":"15-Oct-2026",
               "fileName":"blood-b.pdf","crNo":"123456789012345"}
            ]}
            """.trimIndent()
        )
        val rows = ReportPayloadParser.parseRest(payload, "123456789012345")
        assertEquals(2, rows.size)
        assertTrue(rows[0].url.contains("blood-a.pdf"))
        assertTrue(rows[1].url.contains("blood-b.pdf"))
    }

    @Test
    fun restIndexRejectsMismatchedCrWithoutExposingRows() {
        val payload = JSONObject(
            """
            {"patient":{"crNo":"123456789012346"},"reports":[
              {"reportName":"CBC","reportDate":"15-Oct-2026",
               "fileName":"cbc.pdf","crNo":"123456789012346"}
            ]}
            """.trimIndent()
        )
        var failedClosed = false
        try {
            ReportPayloadParser.parseRest(payload, "123456789012345")
        } catch (_: IdentityMismatchException) {
            failedClosed = true
        }
        assertTrue(failedClosed)
    }

    @Test
    fun restIndexRejectsUnverifiedPatientIdentity() {
        val payload = JSONObject(
            """
            {"reports":[
              {"reportName":"CBC","reportDate":"15-Oct-2026","fileName":"cbc.pdf"}
            ]}
            """.trimIndent()
        )
        assertTrue(ReportPayloadParser.parseRest(payload, "123456789012345").isEmpty())
    }

}
