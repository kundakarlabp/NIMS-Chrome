package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NimsReportHttpClientFailureTest {
    @Test
    fun loginHtmlProducesTypedSessionExpiry() {
        val error = NimsReportHttpClient.failureFor(200, "text/html", "html_login_or_session")
        assertTrue(error is NimsSessionExpiredException)
        assertTrue(error?.message.orEmpty().contains("completed reports were preserved"))
    }

    @Test
    fun unauthorizedStatusProducesTypedSessionExpiry() {
        assertTrue(
            NimsReportHttpClient.failureFor(401, "text/html", "unsupported_content_type")
                is NimsSessionExpiredException
        )
        assertTrue(
            NimsReportHttpClient.failureFor(403, "text/html", "html_report_content")
                is NimsSessionExpiredException
        )
    }

    @Test
    fun staleReportListIsNotMisreportedAsSessionExpiry() {
        val error = NimsReportHttpClient.failureFor(200, "text/html", "html_report_list")
        assertTrue(error !is NimsSessionExpiredException)
        assertEquals("NIMS returned the report list instead of the selected report", error?.message)
    }

    @Test
    fun validReportsHaveNoFailure() {
        assertNull(NimsReportHttpClient.failureFor(200, "application/pdf", "pdf_report"))
        assertNull(NimsReportHttpClient.failureFor(200, "text/html", "html_report_content"))
    }
}
