package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NimsReportTemplateTest {
    @Test
    fun directReportUrlUsesHmodeAndFileName() {
        val template = ReportTemplate(
            origin = "https://www.nimsts.edu.in",
            pathname = "/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt"
        )
        val url = NimsReportTemplate.directReportUrl(template, "ABC 123")
        assertTrue(url.contains("hmode=PRINTREPORT"))
        assertTrue(url.contains("fileName=ABC+123"))
        assertFalse(url.contains("mode=PRINTREPORT"))
    }

    @Test
    fun safeUrlStripsQuery() {
        assertEquals(
            "www.nimsts.edu.in/HISInvestigationG5/path",
            SafeUrl.stripQuery("https://www.nimsts.edu.in/HISInvestigationG5/path?fileName=SECRET")
        )
    }

    @Test
    fun nimsNavigationIsRestrictedToNimsHosts() {
        assertTrue(NimsReportTemplate.isAllowedNimsUrl("https://www.nimsts.edu.in/AHIMSG5/home"))
        assertFalse(NimsReportTemplate.isAllowedNimsUrl("https://example.com/"))
    }
}
