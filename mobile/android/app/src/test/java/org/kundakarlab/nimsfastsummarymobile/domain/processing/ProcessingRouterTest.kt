package org.kundakarlab.nimsfastsummarymobile.domain.processing

import org.junit.Assert.*
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.model.*
import org.kundakarlab.nimsfastsummarymobile.data.processing.LocalSummaryBuilder
import kotlin.coroutines.*

class ProcessingRouterTest {
    @Test fun autoLocalSuccessDoesNotCallRemote() {
        val local = FakeProcessor(ProcessingResult.Success(parsed("local"), "local"))
        val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        val result = runSuspend { ProcessingRouter(local, remote, { ProcessingMode.AUTO }).parse(input()) }
        assertTrue(result is ProcessingResult.Success); assertEquals(1, local.parseCalls); assertEquals(0, remote.parseCalls)
    }
    @Test fun autoUnsupportedFallsBackOnceWithWarning() {
        val local = FakeProcessor(ProcessingResult.Unsupported("unsupported"))
        val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        val result = runSuspend { ProcessingRouter(local, remote, { ProcessingMode.AUTO }).parse(input()) }
        assertTrue(result is ProcessingResult.Success); assertEquals(1, remote.parseCalls); assertTrue((result as ProcessingResult.Success).warnings.any { it.contains("fallback", true) })
    }
    @Test fun autoEligibleFailureFallsBackOnce() {
        val local = FakeProcessor(ProcessingResult.Failure("incomplete", "LOCAL_PARSE_INCOMPLETE", true))
        val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        runSuspend { ProcessingRouter(local, remote, { ProcessingMode.AUTO }).parse(input()) }
        assertEquals(1, local.parseCalls); assertEquals(1, remote.parseCalls)
    }
    @Test fun autoLoginFailureDoesNotFallback() {
        val local = FakeProcessor(ProcessingResult.Failure("login", "LOCAL_LOGIN_HTML", false))
        val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        val result = runSuspend { ProcessingRouter(local, remote, { ProcessingMode.AUTO }).parse(input()) }
        assertTrue(result is ProcessingResult.Failure); assertEquals(0, remote.parseCalls)
    }
    @Test fun autoCaptchaFailureDoesNotFallback() {
        val local = FakeProcessor(ProcessingResult.Failure("captcha", "LOCAL_CAPTCHA_PAGE", false))
        val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        runSuspend { ProcessingRouter(local, remote, { ProcessingMode.AUTO }).parse(input()) }
        assertEquals(0, remote.parseCalls)
    }
    @Test fun autoPdfLocalSuccessDoesNotCallRemote() {
        val local = FakeProcessor(ProcessingResult.Success(parsed("local"), "local"))
        val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        runSuspend { ProcessingRouter(local, remote, { ProcessingMode.AUTO }).parse(input(type = "application/pdf", bytes = "%PDF-1.4".toByteArray())) }
        assertEquals(1, local.parseCalls); assertEquals(0, remote.parseCalls)
    }
    @Test fun localOnlyPdfCallsLocalAndDoesNotCallRemote() {
        val local = FakeProcessor(ProcessingResult.Unsupported("This PDF appears to contain images without extractable text. OCR is not enabled. Open the source report in NIMS.")); val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        val result = runSuspend { ProcessingRouter(local, remote, { ProcessingMode.LOCAL_ONLY }).parse(input(type = "application/octet-stream", bytes = "%PDF-1.4".toByteArray())) }
        assertTrue(result is ProcessingResult.Unsupported); assertTrue((result as ProcessingResult.Unsupported).reason.contains("OCR is not enabled")); assertEquals(1, local.parseCalls); assertEquals(0, remote.parseCalls)
    }

    @Test fun localOnlyPdfUnsupportedAppearsInSourceReports() {
        val local = FakeProcessor(ProcessingResult.Unsupported("This PDF appears to contain images without extractable text. OCR is not enabled. Open the source report in NIMS."))
        val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        val input = input(type = "application/pdf", bytes = "%PDF-1.4".toByteArray()).copy(reportName = "PDF Report", dateSent = "02-06-2026")
        val parsed = runSuspend { ProcessingRouter(local, remote, { ProcessingMode.LOCAL_ONLY }).parse(input) }
        assertTrue(parsed is ProcessingResult.Unsupported)
        val unsupportedReport = ParsedReport(input.reportId, input.reportName, input.dateSent, input.reportType, warnings = listOf((parsed as ProcessingResult.Unsupported).reason), processorName = "none")
        val sourceReport = LocalSummaryBuilder().build(listOf(unsupportedReport), SummaryMode.FULL).helperJson!!.getJSONArray("source_reports").getJSONObject(0)
        assertEquals("PDF Report", sourceReport.getString("report_name"))
        assertEquals("02-06-2026", sourceReport.getString("date_sent"))
        assertEquals("unsupported", sourceReport.getString("status"))
        assertTrue(sourceReport.getString("notes").contains("OCR is not enabled"))
        assertEquals("Open source report in NIMS", sourceReport.getString("action"))
        assertEquals(0, remote.parseCalls)
    }

    @Test fun autoWithoutHelperProcessesLocalPdfWithoutRemote() {
        val local = FakeProcessor(ProcessingResult.Success(parsed("local"), "local"))
        val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        val router = ProcessingRouter(local, remote, { ProcessingMode.AUTO }, remoteConfigured = { false })
        assertTrue(runSuspend { router.parse(input()) } is ProcessingResult.Success)
        assertTrue(runSuspend { router.parse(input(type = "application/pdf", bytes = "%PDF-1.4".toByteArray())) } is ProcessingResult.Success)
        assertEquals(0, remote.parseCalls)
    }
    @Test fun remoteOnlyNeverCallsLocal() {
        val local = FakeProcessor(ProcessingResult.Success(parsed("local"), "local")); val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"))
        runSuspend { ProcessingRouter(local, remote, { ProcessingMode.REMOTE_ONLY }).parse(input()) }
        assertEquals(0, local.parseCalls); assertEquals(1, remote.parseCalls)
    }

    @Test fun autoSummaryWithoutHelperDoesNotFallbackToRemote() {
        val local = FakeProcessor(ProcessingResult.Success(parsed("local"), "local"), ProcessingResult.Unsupported("unsupported"))
        val remote = FakeProcessor(ProcessingResult.Success(parsed("remote"), "remote"), ProcessingResult.Success(ProcessingSummary("remote", 1), "remote"))
        val result = runSuspend { ProcessingRouter(local, remote, { ProcessingMode.AUTO }, remoteConfigured = { false }).summarize(listOf(parsed("local")), SummaryMode.FULL) }
        assertTrue(result is ProcessingResult.Failure)
        assertEquals("REMOTE_HELPER_REQUIRED", (result as ProcessingResult.Failure).technicalCode)
        assertEquals(0, remote.summaryCalls)
    }
    private class FakeProcessor(private val result: ProcessingResult<ParsedReport>, private val summaryResult: ProcessingResult<ProcessingSummary> = ProcessingResult.Success(ProcessingSummary("ok", 0), "fake")) : ReportProcessor {
        var parseCalls = 0
        var summaryCalls = 0
        override val name = "fake"; override val capabilities = emptySet<ProcessingCapability>()
        override suspend fun parseReport(input: ReportInput): ProcessingResult<ParsedReport> { parseCalls++; return result }
        override suspend fun summarize(reports: List<ParsedReport>, mode: SummaryMode): ProcessingResult<ProcessingSummary> { summaryCalls++; return summaryResult }
    }
    private fun input(type: String = "text/plain", bytes: ByteArray = "Hemoglobin 1".toByteArray()) = ReportInput("r", "Report", "2026-01-01", "lab", type, bytes)
    private fun parsed(name: String) = ParsedReport("r", "Report", "2026-01-01", "lab", processorName = name)
}

private fun <T> runSuspend(block: suspend () -> T): T { var value: Result<T>? = null; block.startCoroutine(object : Continuation<T> { override val context = EmptyCoroutineContext; override fun resumeWith(result: Result<T>) { value = result } }); return value!!.getOrThrow() }
