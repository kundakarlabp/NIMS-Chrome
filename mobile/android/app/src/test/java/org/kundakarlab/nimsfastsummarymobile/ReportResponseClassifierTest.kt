package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportResponseClassifierTest {
    @Test fun authenticatedReportWithLoginNavigationIsNotExpired() {
        val html = """
            <html><body><a href='/AHIMSG5/hissso/loginLogin.action'>Logout</a>
            <table><tr><td>Hemoglobin</td><td>10.2</td><td>g/dL</td></tr></table></body></html>
        """.trimIndent()

        assertFalse(ReportResponseClassifier.isLoginOrExpiredHtml(html))
        assertEquals(
            "html_report_content",
            ReportResponseClassifier.classify(200, "text/html", html.toByteArray())
        )
    }

    @Test fun genuineCredentialFormIsClassifiedAsLogin() {
        val html = """
            <html><form action='/AHIMSG5/hissso/loginLogin.action'>
            <input name='username'><input type='password' name='password'></form></html>
        """.trimIndent()

        assertTrue(ReportResponseClassifier.isLoginOrExpiredHtml(html))
        assertEquals(
            "html_login_or_session",
            ReportResponseClassifier.classify(200, "text/html", html.toByteArray())
        )
    }

    @Test fun explicitExpiryIsClassifiedWithoutCredentialFields() {
        assertTrue(ReportResponseClassifier.isLoginOrExpiredHtml("Your session has expired. Please login again."))
    }

    @Test fun esrHtmlIsRecognizedAsReportContent() {
        val html = "<html><table><tr><td>ESR</td><td>42</td><td>mm/hr</td></tr></table></html>"
        assertEquals(
            "html_report_content",
            ReportResponseClassifier.classify(200, "text/html", html.toByteArray())
        )
    }
}
