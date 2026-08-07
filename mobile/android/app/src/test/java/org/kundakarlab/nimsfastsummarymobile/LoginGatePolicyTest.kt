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
                loginVisible = false,
                publicLanding = false,
                sessionExpired = false,
                leftLoginRoute = false,
                crReady = true,
                reportRows = 0,
                logoutVisible = false
            )
        )
    }

    @Test
    fun publicLandingCannotEnterResultsAfterLoginFormWasSeen() {
        assertFalse(
            LoginGatePolicy.canEnterResults(
                loginFormSeen = true,
                loginVisible = false,
                publicLanding = true,
                sessionExpired = false,
                leftLoginRoute = true,
                crReady = false,
                reportRows = 0,
                logoutVisible = false
            )
        )
    }

    @Test
    fun visibleLoginFormCannotEnterResults() {
        assertFalse(
            LoginGatePolicy.canEnterResults(
                loginFormSeen = true,
                loginVisible = true,
                publicLanding = false,
                sessionExpired = false,
                leftLoginRoute = true,
                crReady = false,
                reportRows = 0,
                logoutVisible = false
            )
        )
    }

    @Test
    fun naturalNavigationAwayFromLoginMayEnterResults() {
        assertTrue(
            LoginGatePolicy.canEnterResults(
                loginFormSeen = true,
                loginVisible = false,
                publicLanding = false,
                sessionExpired = false,
                leftLoginRoute = true,
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
                loginVisible = false,
                publicLanding = false,
                sessionExpired = false,
                leftLoginRoute = false,
                crReady = true,
                reportRows = 0,
                logoutVisible = false
            )
        )
    }

    @Test
    fun expiredSessionCannotEnterResults() {
        assertFalse(
            LoginGatePolicy.canEnterResults(
                loginFormSeen = true,
                loginVisible = false,
                publicLanding = false,
                sessionExpired = true,
                leftLoginRoute = true,
                crReady = true,
                reportRows = 1,
                logoutVisible = true
            )
        )
    }

    @Test
    fun gateProbeIsReadOnlyAndDoesNotForceProtectedCrRoute() {
        val script = LoginGateActivity.LOGIN_GATE_SCRIPT
        assertTrue(script.contains("String(x.type||'').toLowerCase()==='password'"))
        assertTrue(script.contains("sessionExpired"))
        assertFalse(script.contains("viewcrnowisereportprocess.cnt"))
        assertFalse(script.contains(".click()"))
    }

    @Test
    fun loginNavigationIsSeparatedFromAuthenticationProbe() {
        val script = LoginGateActivity.OPEN_LOGIN_NAVIGATION_SCRIPT
        assertTrue(script.contains("login_clicked"))
        assertTrue(script.contains("menu_opened"))
    }
}
