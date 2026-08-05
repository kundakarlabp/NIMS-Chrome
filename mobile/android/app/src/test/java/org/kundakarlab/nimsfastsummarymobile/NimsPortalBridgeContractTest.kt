package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NimsPortalBridgeContractTest {
    @Test
    fun publicLandingCannotBeTreatedAsAuthenticatedSession() {
        val script = NimsPortalBridge.probeScript

        assertTrue(script.contains("const publicLanding=publicSignals"))
        assertTrue(script.contains("const authenticated=!sessionExpired&&!loginVisible&&!publicLanding"))
        assertTrue(script.contains("crReady||reportRows>0||logoutControl||protectedModule"))
        assertFalse(
            script.contains(
                "if(/logout|sign\\s*out|investigation|cr\\s*wise\\s*report/.test(lower)) authenticatedShell=true;"
            )
        )
    }

    @Test
    fun loginProbeRequiresARealVisibleLoginForm() {
        val script = NimsPortalBridge.probeScript

        assertTrue(script.contains("passwordVisible"))
        assertTrue(script.contains("usernameVisible&&(captchaVisible||loginActionVisible)"))
        assertTrue(script.contains("getBoundingClientRect"))
    }

    @Test
    fun publicNimsLandingAutomaticallyOpensLoginNavigation() {
        val script = NimsPortalBridge.probeScript

        assertTrue(script.contains("__nimsLoginPreparationState"))
        assertTrue(script.contains(".navbar-toggler"))
        assertTrue(script.contains("publicLanding)loginPreparation=prepareLogin(docs)"))
        assertTrue(script.contains("login_clicked"))
    }
}
