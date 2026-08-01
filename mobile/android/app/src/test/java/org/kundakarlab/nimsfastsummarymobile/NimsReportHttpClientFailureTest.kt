package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NimsReportHttpClientFailureTest {
    @Test
    fun loginHtmlIsPropagatedToMainActivityRecovery() {
        assertNull(NimsReportHttpClient.failureFor(200, "text/html", "html_login_or_session"))
    }

    @Test
    fun unauthorizedClassificationIsPropagatedToMainActivityRecovery() {
        assertNull(NimsReportHttpClient.failureFor(401, "text/html", "html_login_or_session"))
        assertNull(NimsReportHttpClient.failureFor(403, "text/plain", "html_login_or_session"))
    }

    @Test
    fun staleReportListRemainsAReportFetchFailure() {
        val error = NimsReportHttpClient.failureFor(200, "text/html", "html_report_list")
        assertEquals("NIMS returned the report list instead of the selected report", error?.message)
    }

    @Test
    fun validReportsHaveNoFailure() {
        assertNull(NimsReportHttpClient.failureFor(200, "application/pdf", "pdf_report"))
        assertNull(NimsReportHttpClient.failureFor(200, "text/html", "html_report_content"))
    }
}
