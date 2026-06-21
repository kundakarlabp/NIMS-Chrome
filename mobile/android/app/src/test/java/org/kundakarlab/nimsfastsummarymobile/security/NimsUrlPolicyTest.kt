package org.kundakarlab.nimsfastsummarymobile.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NimsUrlPolicyTest {
    @Test fun classifiesAllowedControlledNimsContexts() {
        assertEquals(UrlClassification.ALLOWED_NIMS, NimsUrlPolicy.classifyUrl("https://nimsts.edu.in/AHIMSG5/report"))
        assertEquals(UrlClassification.ALLOWED_NIMS, NimsUrlPolicy.classifyUrl("https://www.nimsts.edu.in/HISInvestigationG5/report"))
        assertEquals(UrlClassification.ALLOWED_NIMS, NimsUrlPolicy.classifyUrl("https://nimsts.edu.in/HIS/report"))
        assertEquals(UrlClassification.ALLOWED_NIMS, NimsUrlPolicy.classifyUrl("https://nimsts.edu.in/hislogin/login"))
        assertEquals(UrlClassification.ALLOWED_NIMS, NimsUrlPolicy.classifyUrl("https://nimsts.edu.in/HISUtilities/tool"))
        assertEquals(UrlClassification.ALLOWED_NIMS, NimsUrlPolicy.classifyUrl("https://nimsts.edu.in/HBIMS/page"))
    }

    @Test fun classifiesExternalAndUnsafeUrls() {
        assertEquals(UrlClassification.EXTERNAL_HTTPS, NimsUrlPolicy.classifyUrl("https://example.org/page"))
        assertEquals(UrlClassification.BLOCKED_UNSAFE, NimsUrlPolicy.classifyUrl("https://user:password@example.org/page"))
        assertEquals(UrlClassification.BLOCKED_UNSAFE, NimsUrlPolicy.classifyUrl("https://user:password@nimsts.edu.in/AHIMSG5/report"))
        assertEquals(UrlClassification.BLOCKED_NIMS, NimsUrlPolicy.classifyUrl("https://nimsts.edu.in:444/AHIMSG5/"))
    }

    @Test fun blocksUnknownNimsPathsAndTraversal() {
        assertEquals(UrlClassification.BLOCKED_NIMS, NimsUrlPolicy.classifyUrl("https://nimsts.edu.in/UNKNOWN/"))
        assertEquals(UrlClassification.BLOCKED_NIMS, NimsUrlPolicy.classifyUrl("https://nimsts.edu.in/HIS/../admin"))
        assertEquals(UrlClassification.BLOCKED_NIMS, NimsUrlPolicy.classifyUrl("https://nimsts.edu.in/HIS/%2e%2e/admin"))
        assertEquals(UrlClassification.BLOCKED_NIMS, NimsUrlPolicy.classifyUrl("https://nimsts.edu.in/HIS/%2fadmin"))
        assertEquals(UrlClassification.BLOCKED_NIMS, NimsUrlPolicy.classifyUrl("https://nimsts.edu.in/HIS/%5cadmin"))
        assertEquals(UrlClassification.BLOCKED_NIMS, NimsUrlPolicy.classifyUrl("https://nimsts.edu.in/AHIMSG5//report"))
    }

    @Test fun blocksUnsafeSchemes() {
        assertEquals(UrlClassification.BLOCKED_SCHEME, NimsUrlPolicy.classifyUrl("http://nimsts.edu.in/AHIMSG5/"))
        assertEquals(UrlClassification.BLOCKED_SCHEME, NimsUrlPolicy.classifyUrl("javascript:alert(1)"))
        assertEquals(UrlClassification.BLOCKED_SCHEME, NimsUrlPolicy.classifyUrl("file:///etc/passwd"))
        assertEquals(UrlClassification.BLOCKED_SCHEME, NimsUrlPolicy.classifyUrl("content://example"))
        assertEquals(UrlClassification.BLOCKED_SCHEME, NimsUrlPolicy.classifyUrl("data:text/html,test"))
        assertEquals(UrlClassification.BLOCKED_SCHEME, NimsUrlPolicy.classifyUrl("intent://example"))
    }

    @Test fun safeSourceStripsQueryAndKeepsHostPathOnly() {
        assertTrue(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/AHIMSG5/report?hmode=x&fileName=secret"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/UNKNOWN/"))
        assertEquals("https://nimsts.edu.in/AHIMSG5/report", NimsUrlPolicy.safeSourceForHelper("https://nimsts.edu.in/AHIMSG5/report?hmode=x&fileName=secret"))
    }
}
