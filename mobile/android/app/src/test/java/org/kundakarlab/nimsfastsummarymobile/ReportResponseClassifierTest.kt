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
        assertEquals("html_report_content", ReportResponseClassifier.classify(200, "text/html", html.toByteArray()))
    }

    @Test fun genuineCredentialFormIsClassifiedAsLogin() {
        val html = """
            <html><form action='/AHIMSG5/hissso/loginLogin.action'>
            <input name='username'><input type='password' name='password'></form></html>
        """.trimIndent()

        assertTrue(ReportResponseClassifier.isLoginOrExpiredHtml(html))
        assertEquals("html_login_or_session", ReportResponseClassifier.classify(200, "text/html", html.toByteArray()))
    }

    @Test fun explicitExpiryIsClassifiedWithoutCredentialFields() {
        assertTrue(ReportResponseClassifier.isLoginOrExpiredHtml("Your session has expired. Please login again."))
    }

    @Test fun esrHtmlIsRecognizedAsReportContent() {
        val html = "<html><table><tr><td>ESR</td><td>42</td><td>mm/hr</td></tr></table></html>"
        assertEquals("html_report_content", ReportResponseClassifier.classify(200, "text/html", html.toByteArray()))
    }

    @Test fun reportListIsNotAcceptedAsTheOriginalReport() {
        val html = """
            <html><body><h2>Investigation Result</h2><input id='patCrNo'>
            <table id='report_list'><tr><td><a onclick='printReport(101)'>CBC</a></td></tr>
            <tr><td><a onclick='printReport(102)'>RFT</a></td></tr></table></body></html>
        """.trimIndent()
        assertTrue(ReportResponseClassifier.isReportListHtml(html))
        assertEquals("html_report_list", ReportResponseClassifier.classify(200, "text/html", html.toByteArray()))
    }

    @Test fun declaredPdfWithoutPdfBytesIsRejected() {
        val html = "<html><body>Unexpected gateway response</body></html>".toByteArray()
        assertEquals("invalid_pdf_response", ReportResponseClassifier.classify(200, "application/pdf", html))
    }

    @Test fun pdfMagicWinsEvenWhenServerContentTypeIsWrong() {
        val pdf = "  %PDF-1.7\nmock".toByteArray()
        assertTrue(ReportResponseClassifier.hasPdfSignature(pdf))
        assertEquals("pdf_report", ReportResponseClassifier.classify(200, "text/html", pdf))
    }
}
