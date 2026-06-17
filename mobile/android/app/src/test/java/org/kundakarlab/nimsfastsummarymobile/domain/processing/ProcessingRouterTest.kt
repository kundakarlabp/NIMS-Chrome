package org.kundakarlab.nimsfastsummarymobile.domain.processing

import org.junit.Assert.*
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.model.*
import kotlin.coroutines.*

class ProcessingRouterTest {
    @Test fun autoLocalSuccessDoesNotCallRemote() {
        val local = FakeProcessor(ProcessingResult.Success(parsed("local"), "local"))
        val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        val result = runSuspend { ProcessingRouter(local, remote) { ProcessingMode.AUTO }.parse(input()) }
        assertTrue(result is ProcessingResult.Success); assertEquals(1, local.parseCalls); assertEquals(0, remote.parseCalls)
    }
    @Test fun autoUnsupportedFallsBackOnceWithWarning() {
        val local = FakeProcessor(ProcessingResult.Unsupported("unsupported"))
        val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        val result = runSuspend { ProcessingRouter(local, remote) { ProcessingMode.AUTO }.parse(input()) }
        assertTrue(result is ProcessingResult.Success); assertEquals(1, remote.parseCalls); assertTrue((result as ProcessingResult.Success).warnings.any { it.contains("fallback", true) })
    }
    @Test fun autoEligibleFailureFallsBackOnce() {
        val local = FakeProcessor(ProcessingResult.Failure("incomplete", "LOCAL_PARSE_INCOMPLETE", true))
        val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        runSuspend { ProcessingRouter(local, remote) { ProcessingMode.AUTO }.parse(input()) }
        assertEquals(1, local.parseCalls); assertEquals(1, remote.parseCalls)
    }
    @Test fun autoLoginFailureDoesNotFallback() {
        val local = FakeProcessor(ProcessingResult.Failure("login", "LOCAL_LOGIN_HTML", false))
        val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        val result = runSuspend { ProcessingRouter(local, remote) { ProcessingMode.AUTO }.parse(input()) }
        assertTrue(result is ProcessingResult.Failure); assertEquals(0, remote.parseCalls)
    }
    @Test fun autoCaptchaFailureDoesNotFallback() {
        val local = FakeProcessor(ProcessingResult.Failure("captcha", "LOCAL_CAPTCHA_PAGE", false))
        val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        runSuspend { ProcessingRouter(local, remote) { ProcessingMode.AUTO }.parse(input()) }
        assertEquals(0, remote.parseCalls)
    }
    @Test fun autoPdfCallsRemoteOnly() {
        val local = FakeProcessor(ProcessingResult.Success(parsed("local"), "local"))
        val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        runSuspend { ProcessingRouter(local, remote) { ProcessingMode.AUTO }.parse(input(type = "application/pdf")) }
        assertEquals(0, local.parseCalls); assertEquals(1, remote.parseCalls)
    }
    @Test fun localOnlyPdfDoesNotCallRemote() {
        val local = FakeProcessor(ProcessingResult.Unsupported("unsupported")); val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        val result = runSuspend { ProcessingRouter(local, remote) { ProcessingMode.LOCAL_ONLY }.parse(input(type = "application/pdf")) }
        assertTrue(result is ProcessingResult.Unsupported); assertEquals(0, remote.parseCalls)
    }
    @Test fun remoteOnlyNeverCallsLocal() {
        val local = FakeProcessor(ProcessingResult.Success(parsed("local"), "local")); val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        runSuspend { ProcessingRouter(local, remote) { ProcessingMode.REMOTE_ONLY }.parse(input()) }
        assertEquals(0, local.parseCalls); assertEquals(1, remote.parseCalls)
    }
    private class FakeProcessor(private val result: ProcessingResult<ParsedReport>) : ReportProcessor {
        var parseCalls = 0
        override val name = "fake"; override val capabilities = emptySet<ProcessingCapability>()
        override suspend fun parseReport(input: ReportInput): ProcessingResult<ParsedReport> { parseCalls++; return result }
        override suspend fun summarize(reports: List<ParsedReport>, mode: SummaryMode): ProcessingResult<ProcessingSummary> = ProcessingResult.Success(ProcessingSummary("ok", reports.size), name)
    }
    private fun input(type: String = "text/plain") = ReportInput("r", "Report", "2026-01-01", "lab", type, "Hemoglobin 1".toByteArray())
    private fun parsed(name: String) = ParsedReport("r", "Report", "2026-01-01", "lab", processorName = name)
}

private fun <T> runSuspend(block: suspend () -> T): T { var value: Result<T>? = null; block.startCoroutine(object : Continuation<T> { override val context = EmptyCoroutineContext; override fun resumeWith(result: Result<T>) { value = result } }); return value!!.getOrThrow() }
