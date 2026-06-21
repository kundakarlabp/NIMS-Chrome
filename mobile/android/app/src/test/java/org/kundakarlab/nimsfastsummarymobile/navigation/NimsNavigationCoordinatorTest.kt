package org.kundakarlab.nimsfastsummarymobile.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NimsNavigationCoordinatorTest {
    @Test fun crSearchStopsImmediately() = runTest {
        val coordinator = NimsNavigationCoordinator(retryDelayMs = 1, stepTimeoutMs = 50, maxDurationMs = 500)
        val outcome = coordinator.run { NimsNavigationStep(true, "cr_search", "none", true, "") }
        assertEquals(NimsNavigationOutcome.CrSearchReady, outcome)
    }

    @Test fun reportListStopsImmediately() = runTest {
        val coordinator = NimsNavigationCoordinator(retryDelayMs = 1, stepTimeoutMs = 50, maxDurationMs = 500)
        val outcome = coordinator.run { NimsNavigationStep(true, "report_list", "none", true, "") }
        assertEquals(NimsNavigationOutcome.ReportListReady, outcome)
    }

    @Test fun loginAndSessionAreTerminal() = runTest {
        val coordinator = NimsNavigationCoordinator(retryDelayMs = 1, stepTimeoutMs = 50, maxDurationMs = 500)
        assertEquals(NimsNavigationOutcome.ManualLoginRequired, coordinator.run { NimsNavigationStep(false, "login", "none", false, "manual_login_required") })
        assertEquals(NimsNavigationOutcome.SessionExpired, coordinator.run { NimsNavigationStep(false, "session_expired", "none", false, "session_expired") })
    }

    @Test fun unknownRetriesAreBounded() = runTest {
        var calls = 0
        val outcome = NimsNavigationCoordinator(maxAttempts = 3, retryDelayMs = 1, stepTimeoutMs = 50, maxDurationMs = 500).run {
            calls += 1
            NimsNavigationStep(false, "unknown", "none", false, "navigation_target_not_found")
        }
        assertEquals(3, calls)
        assertEquals(NimsNavigationOutcome.Failed("navigation_target_not_found"), outcome)
    }

    @Test fun hangingStepTimesOutAndOverallDurationIsBounded() = runTest {
        val outcome = NimsNavigationCoordinator(maxAttempts = 2, retryDelayMs = 1, stepTimeoutMs = 10, maxDurationMs = 100).run {
            kotlinx.coroutines.delay(1_000)
            NimsNavigationStep(false, "unknown", "none", false, "")
        }
        assertEquals(NimsNavigationOutcome.Failed("navigation_target_not_found"), outcome)
    }

    @Test fun cancellationExitsCleanly() = runTest {
        val coordinator = NimsNavigationCoordinator(maxAttempts = 10, retryDelayMs = 100, stepTimeoutMs = 100, maxDurationMs = 5_000)
        val job = launch { coordinator.run { NimsNavigationStep(false, "unknown", "none", false, "") } }
        job.cancelAndJoin()
        assertEquals(true, job.isCancelled)
    }
}
