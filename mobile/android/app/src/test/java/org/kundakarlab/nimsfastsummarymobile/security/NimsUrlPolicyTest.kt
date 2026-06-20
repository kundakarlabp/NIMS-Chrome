package org.kundakarlab.nimsfastsummarymobile.security

import org.junit.Assert.*
import org.junit.Test

class NimsUrlPolicyTest {
    @Test fun allowsKnownControlledNimsContexts() {
        assertTrue(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/AHIMSG5/report"))
        assertTrue(NimsUrlPolicy.isAllowedUrl("https://www.nimsts.edu.in/HISInvestigationG5/report"))
        assertTrue(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/HIS/report"))
        assertTrue(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/hislogin/login"))
        assertTrue(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/HISUtilities/tool"))
        assertTrue(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/HBIMS/page"))
    }

    @Test fun blocksUntrustedHostsSchemesCredentialsPortsAndUnknownPaths() {
        assertFalse(NimsUrlPolicy.isAllowedUrl("http://nimsts.edu.in/AHIMSG5/"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://evil.example/AHIMSG5/"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in.evil.example/AHIMSG5/"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://evilnimsts.edu.in/AHIMSG5/"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://user:pass@nimsts.edu.in/AHIMSG5/"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in:444/AHIMSG5/"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/UNKNOWN/"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("javascript:alert(1)"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("file:///etc/passwd"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("content://example"))
    }

    @Test fun rejectsEncodedTraversalAndMalformedPaths() {
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/HIS/../admin"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/HIS/%2e%2e/admin"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/HIS/%2fadmin"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/HIS/%5cadmin"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/AHIMSG5//report"))
    }

    @Test fun safeSourceStripsQueryAndKeepsHostPathOnly() {
        assertEquals("https://nimsts.edu.in/AHIMSG5/report", NimsUrlPolicy.safeSourceForHelper("https://nimsts.edu.in/AHIMSG5/report?hmode=x&fileName=secret"))
    }
}
