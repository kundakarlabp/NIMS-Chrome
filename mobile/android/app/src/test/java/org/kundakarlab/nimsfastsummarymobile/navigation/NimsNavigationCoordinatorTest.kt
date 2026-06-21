package org.kundakarlab.nimsfastsummarymobile.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NimsNavigationCoordinatorTest {

    @Test
    fun crSearchStopsImmediately() = runTest {
        var calls = 0

        val coordinator = NimsNavigationCoordinator(
            retryDelayMs = 1,
            stepTimeoutMs = 50,
            maxDurationMs = 500
        )

        val outcome = coordinator.execute(
            stepProvider = {
                calls += 1

                NimsNavigationStep(
                    ok = true,
                    stage = "cr_search",
                    action = "none",
                    done = true,
                    errorCode = ""
                )
            }
        )

        assertEquals(
            NimsNavigationOutcome.CrSearchReady,
            outcome
        )
        assertEquals(1, calls)
    }

    @Test
    fun reportListStopsImmediately() = runTest {
        var calls = 0

        val coordinator = NimsNavigationCoordinator(
            retryDelayMs = 1,
            stepTimeoutMs = 50,
            maxDurationMs = 500
        )

        val outcome = coordinator.execute(
            stepProvider = {
                calls += 1

                NimsNavigationStep(
                    ok = true,
                    stage = "report_list",
                    action = "none",
                    done = true,
                    errorCode = ""
                )
            }
        )

        assertEquals(
            NimsNavigationOutcome.ReportListReady,
            outcome
        )
        assertEquals(1, calls)
    }

    @Test
    fun loginIsTerminal() = runTest {
        var calls = 0

        val coordinator = NimsNavigationCoordinator(
            retryDelayMs = 1,
            stepTimeoutMs = 50,
            maxDurationMs = 500
        )

        val outcome = coordinator.execute(
            stepProvider = {
                calls += 1

                NimsNavigationStep(
                    ok = false,
                    stage = "login",
                    action = "none",
                    done = false,
                    errorCode = "manual_login_required"
                )
            }
        )

        assertEquals(
            NimsNavigationOutcome.ManualLoginRequired,
            outcome
        )
        assertEquals(1, calls)
    }

    @Test
    fun sessionExpiredIsTerminal() = runTest {
        var calls = 0

        val coordinator = NimsNavigationCoordinator(
            retryDelayMs = 1,
            stepTimeoutMs = 50,
            maxDurationMs = 500
        )

        val outcome = coordinator.execute(
            stepProvider = {
                calls += 1

                NimsNavigationStep(
                    ok = false,
                    stage = "session_expired",
                    action = "none",
                    done = false,
                    errorCode = "session_expired"
                )
            }
        )

        assertEquals(
            NimsNavigationOutcome.SessionExpired,
            outcome
        )
        assertEquals(1, calls)
    }

    @Test
    fun unknownRetriesAreBounded() = runTest {
        var calls = 0
        val observedSteps = mutableListOf<NimsNavigationStep>()

        val coordinator = NimsNavigationCoordinator(
            maxAttempts = 3,
            retryDelayMs = 1,
            stepTimeoutMs = 50,
            maxDurationMs = 500
        )

        val outcome = coordinator.execute(
            stepProvider = {
                calls += 1

                NimsNavigationStep(
                    ok = false,
                    stage = "unknown",
                    action = "none",
                    done = false,
                    errorCode = "navigation_target_not_found"
                )
            },
            onStep = { step ->
                observedSteps += step
            }
        )

        assertEquals(3, calls)
        assertEquals(3, observedSteps.size)

        assertEquals(
            NimsNavigationOutcome.Failed(
                errorCode = "navigation_target_not_found"
            ),
            outcome
        )
    }

    @Test
    fun hangingStepUsesPerStepTimeoutAndRemainsBounded() = runTest {
        var calls = 0
        val observedSteps = mutableListOf<NimsNavigationStep>()

        val coordinator = NimsNavigationCoordinator(
            maxAttempts = 2,
            retryDelayMs = 1,
            stepTimeoutMs = 10,
            maxDurationMs = 100
        )

        val outcome = coordinator.execute(
            stepProvider = {
                calls += 1
                delay(1_000)

                NimsNavigationStep(
                    ok = false,
                    stage = "unknown",
                    action = "none",
                    done = false,
                    errorCode = ""
                )
            },
            onStep = { step ->
                observedSteps += step
            }
        )

        assertEquals(2, calls)
        assertEquals(2, observedSteps.size)

        assertTrue(
            observedSteps.all {
                it.errorCode == "navigation_step_timeout"
            }
        )

        assertEquals(
            NimsNavigationOutcome.Failed(
                errorCode = "navigation_target_not_found"
            ),
            outcome
        )
    }

    @Test
    fun overallTimeoutReturnsTimeout() = runTest {
        val coordinator = NimsNavigationCoordinator(
            maxAttempts = 100,
            retryDelayMs = 50,
            stepTimeoutMs = 1_000,
            maxDurationMs = 100
        )

        val outcome = coordinator.execute(
            stepProvider = {
                NimsNavigationStep(
                    ok = false,
                    stage = "unknown",
                    action = "none",
                    done = false,
                    errorCode = "navigation_target_not_found"
                )
            }
        )

        assertEquals(
            NimsNavigationOutcome.Timeout,
            outcome
        )
    }

    @Test
    fun cancellationExitsCleanly() = runTest {
        val coordinator = NimsNavigationCoordinator(
            maxAttempts = 100,
            retryDelayMs = 100,
            stepTimeoutMs = 1_000,
            maxDurationMs = 10_000
        )

        val job = launch {
            coordinator.execute(
                stepProvider = {
                    delay(5_000)

                    NimsNavigationStep(
                        ok = false,
                        stage = "unknown",
                        action = "none",
                        done = false,
                        errorCode = ""
                    )
                }
            )
        }

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
    }

    @Test
    fun onStepReceivesTerminalStepBeforeReturn() = runTest {
        val observed = mutableListOf<NimsNavigationStep>()

        val coordinator = NimsNavigationCoordinator(
            retryDelayMs = 1,
            stepTimeoutMs = 50,
            maxDurationMs = 500
        )

        val terminalStep = NimsNavigationStep(
            ok = true,
            stage = "cr_search",
            action = "none",
            done = true,
            errorCode = ""
        )

        val outcome = coordinator.execute(
            stepProvider = {
                terminalStep
            },
            onStep = { step ->
                observed += step
            }
        )

        assertEquals(
            NimsNavigationOutcome.CrSearchReady,
            outcome
        )

        assertEquals(
            listOf(terminalStep),
            observed
        )
    }
}
