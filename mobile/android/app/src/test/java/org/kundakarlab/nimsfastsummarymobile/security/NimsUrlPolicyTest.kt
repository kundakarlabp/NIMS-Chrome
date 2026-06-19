package org.kundakarlab.nimsfastsummarymobile.security

import org.junit.Assert.*
import org.junit.Test

class NimsUrlPolicyTest {
    @Test fun allowsOnlyApprovedHttpsNimsDefaultPortAndPaths() {
        assertTrue(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/AHIMSG5/report?hmode=x&fileName=y"))
        assertTrue(NimsUrlPolicy.isAllowedUrl("https://www.nimsts.edu.in/HISInvestigationG5/report"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("http://nimsts.edu.in/AHIMSG5/report"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://user@nimsts.edu.in/AHIMSG5/report"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in:444/AHIMSG5/report"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://evilnimsts.edu.in/AHIMSG5/report"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in.evil.test/AHIMSG5/report"))
    }

    @Test fun rejectsEncodedTraversalAndMalformedPaths() {
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/AHIMSG5/%2e%2e/report"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/HISInvestigationG5/%2E%2E/report"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/AHIMSG5/a%2fb/report"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/AHIMSG5/a%5Cb/report"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/AHIMSG5//report"))
        assertFalse(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/../AHIMSG5/report"))
    }

    @Test fun validNimsReportPathsRemainAccepted() {
        assertTrue(NimsUrlPolicy.isAllowedUrl("https://nimsts.edu.in/AHIMSG5/report/print"))
        assertTrue(NimsUrlPolicy.isAllowedUrl("https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/report.cnt"))
    }

    @Test fun safeSourceStripsQueryAndKeepsHostPathOnly() {
        assertEquals("https://nimsts.edu.in/AHIMSG5/report", NimsUrlPolicy.safeSourceForHelper("https://nimsts.edu.in/AHIMSG5/report?hmode=x&fileName=secret"))
    }
}
