package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class NimsLoginRecoveryTest {
    private val www = "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action"
    private val apex = "https://nimsts.edu.in/AHIMSG5/hissso/loginLogin.action"

    @Test fun dnsErrorTriesOnlyHospitalAlternateOnce() {
        assertEquals(apex, NimsLoginRecovery.alternateLoginUrl(www, -2, false))
        assertEquals(www, NimsLoginRecovery.alternateLoginUrl(apex, -2, false))
        assertNull(NimsLoginRecovery.alternateLoginUrl(www, -2, true))
    }

    @Test fun doesNotTryAlternateForTlsHttpOrTimeoutFailures() {
        for (code in listOf(-6, -8, -11, -12, -1)) {
            assertNull(NimsLoginRecovery.alternateLoginUrl(www, code, false))
        }
    }

    @Test fun onlyExactHttpsHospitalLoginPathsAllowed() {
        assertNull(NimsLoginRecovery.alternateLoginUrl(
            "http://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action", -2, false))
        assertNull(NimsLoginRecovery.alternateLoginUrl(
            "https://evil.example/AHIMSG5/hissso/loginLogin.action", -2, false))
        assertNull(NimsLoginRecovery.alternateLoginUrl(
            "https://www.nimsts.edu.in.evil.example/AHIMSG5/hissso/loginLogin.action", -2, false))
        assertNull(NimsLoginRecovery.alternateLoginUrl(
            "https://www.nimsts.edu.in:444/AHIMSG5/hissso/loginLogin.action", -2, false))
        assertNull(NimsLoginRecovery.alternateLoginUrl(
            "https://www.nimsts.edu.in/HISInvestigationG5/report", -2, false))
        assertNull(NimsLoginRecovery.alternateLoginUrl(
            "$www?token=12345", -2, false))
        assertNull(NimsLoginRecovery.alternateLoginUrl(
            "https://www.nimsts.edu.in@evil.example/AHIMSG5/hissso/loginLogin.action", -2, false))
    }

    @Test fun errorClassificationDoesNotClaimCookieMeansAuthentication() {
        assertTrue(NimsLoginRecovery.errorDescription(-2).contains("DNS"))
        assertTrue(NimsLoginRecovery.errorDescription(-8).contains("timed out"))
        assertTrue(NimsLoginRecovery.errorDescription(-11).contains("secure"))
    }
}
