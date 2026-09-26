package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.*
import org.junit.Test

class NimsBackgroundReportListParserTest {
    @Test fun parsesSyntheticReportRowsWithoutExportingTransientTokens() {
        val html = """
            <html><body>
              <div>Patient Name: TEST PATIENT</div>
              <table>
                <tr><td>01-Jan-2026</td><td>CBC</td><td><button onclick="return printReport('SAFE_FIXTURE_ARG');">View Report</button></td></tr>
                <tr><td>02-Jan-2026</td><td>Blood Culture</td><td><a onclick="printReport('CULTURE_TOKEN_2')">View Report</a></td></tr>
              </table>
            </body></html>
        """.trimIndent()
        val parsed = NimsBackgroundReportListParser.parse(html)
        assertEquals("TEST PATIENT", parsed.patientName)
        assertEquals(2, parsed.rows.size)
        assertEquals("CBC", parsed.rows[0].reportName)
        assertEquals("lab", parsed.rows[0].reportType)
        assertEquals("culture", parsed.rows[1].reportType)
        assertNotEquals(parsed.rows[0].token, parsed.rows[0].reportId)
        assertFalse(parsed.rows[0].reportId.contains("SAFE_FIXTURE_ARG"))
    }

    @Test fun detectsSyntheticLoginPage() {
        assertTrue(
            NimsBackgroundReportListParser.looksLikeLoginOrExpired(
                "<html><body>User ID <input><input type='password'> CAPTCHA</body></html>",
                "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action"
            )
        )
    }
}
