package org.kundakarlab.nimsfastsummarymobile.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

class NimsNavigationCoordinator(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val retryDelayMs: Long = DEFAULT_RETRY_DELAY_MS,
    private val stepTimeoutMs: Long = DEFAULT_STEP_TIMEOUT_MS,
    private val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS
) {
    suspend fun execute(
        stepProvider: suspend () -> NimsNavigationStep,
        onStep: suspend (NimsNavigationStep) -> Unit = {}
    ): NimsNavigationOutcome {
        return try {
            withTimeout(maxDurationMs) {
                repeat(maxAttempts) {
                    coroutineContext.ensureActive()

                    val step = withTimeoutOrNull(stepTimeoutMs) {
                        stepProvider()
                    } ?: NimsNavigationStep(
                        ok = false,
                        stage = "unknown",
                        action = "none",
                        done = false,
                        errorCode = "navigation_step_timeout"
                    )

                    onStep(step)

                    when (step.stage) {
                        "cr_search" -> {
                            return@withTimeout NimsNavigationOutcome.CrSearchReady
                        }

                        "report_list" -> {
                            return@withTimeout NimsNavigationOutcome.ReportListReady
                        }

                        "login" -> {
                            return@withTimeout NimsNavigationOutcome.ManualLoginRequired
                        }

                        "session_expired" -> {
                            return@withTimeout NimsNavigationOutcome.SessionExpired
                        }
                    }

                    if (it < maxAttempts - 1) {
                        delay(retryDelayMs)
                    }
                }

                NimsNavigationOutcome.Failed(
                    errorCode = "navigation_target_not_found"
                )
            }
        } catch (_: TimeoutCancellationException) {
            NimsNavigationOutcome.Timeout
        } catch (_: CancellationException) {
            NimsNavigationOutcome.Cancelled
        }
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 14
        const val DEFAULT_RETRY_DELAY_MS = 750L
        const val DEFAULT_STEP_TIMEOUT_MS = 3_000L
        const val DEFAULT_MAX_DURATION_MS = 20_000L
    }
}
