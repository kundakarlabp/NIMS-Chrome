package org.kundakarlab.nimsfastsummarymobile.data.processing

import android.util.Base64
import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.HelperApiClient
import org.kundakarlab.nimsfastsummarymobile.domain.model.*
import org.kundakarlab.nimsfastsummarymobile.domain.processing.*

class RemoteReportProcessor(private val clientProvider: () -> HelperApiClient) : ReportProcessor {
    override val name = "Railway"
    override val capabilities = setOf(ProcessingCapability.HTML, ProcessingCapability.PLAIN_TEXT, ProcessingCapability.PDF, ProcessingCapability.LABS, ProcessingCapability.CULTURES, ProcessingCapability.SUMMARY)
    override suspend fun parseReport(input: ReportInput): ProcessingResult<ParsedReport> = try {
        val payload = JSONObject().put("report_id", input.reportId).put("report_name", input.reportName).put("date_sent", input.dateSent)
            .put("source_url", input.safeSource).put("content_type", input.contentType).put("pdf_base64", Base64.encodeToString(input.bytes, Base64.NO_WRAP))
        clientProvider().parseReport(payload)
        ProcessingResult.Unsupported("Remote JSON parsing is handled by legacy UI path.")
    } catch (error: Exception) { error.toRemoteFailure() }
    override suspend fun summarize(reports: List<ParsedReport>, mode: SummaryMode): ProcessingResult<ProcessingSummary> = try {
        clientProvider().summarize(JSONObject().put("mode", mode.name.lowercase()).put("reports", org.json.JSONArray().also { array -> reports.forEach { array.put(it.toHelperJson()) } }))
        ProcessingResult.Unsupported("Remote summary JSON is handled by legacy UI path.")
    } catch (error: Exception) { error.toRemoteFailure() }
}

fun Throwable.toRemoteFailure(): ProcessingResult.Failure {
    val message = message.orEmpty()
    return when {
        "401" in message -> ProcessingResult.Failure("Railway helper rejected the API key. Check helper settings.", "REMOTE_UNAUTHORIZED", false, this)
        "413" in message -> ProcessingResult.Failure("The selected report is too large for remote processing.", "REMOTE_PAYLOAD_TOO_LARGE", false, this)
        "429" in message -> ProcessingResult.Failure("Railway helper is temporarily rate limited. Retry shortly.", "REMOTE_RATE_LIMITED", true, this)
        "timed out" in message.lowercase() -> ProcessingResult.Failure("Remote processing is temporarily unavailable.", "REMOTE_TIMEOUT", true, this)
        else -> ProcessingResult.Failure("Remote processing is temporarily unavailable.", "REMOTE_UNAVAILABLE", true, this)
    }
}
