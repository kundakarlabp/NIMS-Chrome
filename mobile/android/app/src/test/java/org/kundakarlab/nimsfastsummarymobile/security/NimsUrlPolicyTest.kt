package org.kundakarlab.nimsfastsummarymobile.security

import org.junit.Assert.*
import org.junit.Test

class NimsUrlPolicyTest {
    @Test fun allowsApprovedNimsUrls() {
        assertTrue(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/AHIMSG5/login"))
        assertTrue(NimsUrlPolicy.isAllowedUrl("https://www.nimsts.edu.in/HISInvestigationG5/report"))
    }

    @Test fun blocksUnsafeUrls() {
        assertFalse(NimsUrlPolicy.isAllowedUrl("http://nimsts.edu.in/AHIMSG5/login"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("javascript:alert(1)"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("file:///tmp/a"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("content://x"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("data:text/html,x"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("intent://x"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://evil-nimsts.edu.in/AHIMSG5/login"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://user@nimsts.edu.in/AHIMSG5/login"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/other/path"))
    }

    @Test fun safeSourceStripsQueryAndRejectsNonNims() {
        assertEquals("https://nimsts.edu.in/AHIMSG5/report", NimsUrlPolicy.safeSourceForHelper("https://nimsts.edu.in/AHIMSG5/report?fileName=secret#frag"))
        assertEquals("", NimsUrlPolicy.safeSourceForHelper("https://example.com/AHIMSG5/report?x=1"))
    }
}
