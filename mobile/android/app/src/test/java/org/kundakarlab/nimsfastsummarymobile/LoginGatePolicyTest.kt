package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginGatePolicyTest {
    @Test
    fun staleCrFieldCannotBypassManualLogin() {
        assertFalse(
            LoginGatePolicy.canEnterResults(
                loginFormSeen = false,
                crReady = true,
                reportRows = 0,
                logoutVisible = false
            )
        )
    }

    @Test
    fun publicLandingCannotEnterResults() {
        assertFalse(
            LoginGatePolicy.canEnterResults(
                loginFormSeen = false,
                crReady = false,
                reportRows = 0,
                logoutVisible = false
            )
        )
    }

    @Test
    fun verifiedLoginMayContinueWhenProtectedCrModuleIsReady() {
        assertTrue(
            LoginGatePolicy.canEnterResults(
                loginFormSeen = true,
                crReady = true,
                reportRows = 0,
                logoutVisible = false
            )
        )
    }

    @Test
    fun verifiedLoginMayContinueWhenLogoutControlIsVisible() {
        assertTrue(
            LoginGatePolicy.canEnterResults(
                loginFormSeen = true,
                crReady = false,
                reportRows = 0,
                logoutVisible = true
            )
        )
    }

    @Test
    fun gateScriptContainsRealLoginAndPublicLandingChecks() {
        val script = LoginGateActivity.LOGIN_GATE_SCRIPT
        assertTrue(script.contains("String(x.type||'').toLowerCase()==='password'"))
        assertTrue(script.contains("recommended\\s+to\\s+use\\s+firefox"))
        assertTrue(script.contains("navigationAction='menu_opened'"))
        assertTrue(script.contains("navigationAction='login_clicked'"))
    }
}
