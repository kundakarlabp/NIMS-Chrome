package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.HelperApiClient
import org.kundakarlab.nimsfastsummarymobile.domain.model.*
import org.kundakarlab.nimsfastsummarymobile.domain.processing.*

class RemoteReportProcessor(private val clientProvider: () -> HelperApiClient) : ReportProcessor {
    override val name = "Railway"
    override val capabilities = setOf(ProcessingCapability.HTML, ProcessingCapability.PLAIN_TEXT, ProcessingCapability.PDF, ProcessingCapability.LABS, ProcessingCapability.CULTURES, ProcessingCapability.SUMMARY)

    override suspend fun parseReport(input: ReportInput): ProcessingResult<ParsedReport> = try {
        val payload = RemotePayloadBuilder.build(input)
        val response = clientProvider().parseReport(payload)
        val (report, warnings) = RemoteReportMapper.toParsedReport(response, input, name)
        ProcessingResult.Success(report, name, warnings)
    } catch (error: Exception) { error.toRemoteFailure() }

    override suspend fun summarize(reports: List<ParsedReport>, mode: SummaryMode): ProcessingResult<ProcessingSummary> = try {
        val reportArray = org.json.JSONArray().also { array -> reports.forEach { array.put(it.toHelperJson()) } }
        val response = clientProvider().summarize(JSONObject().put("mode", mode.name.lowercase()).put("reports", reportArray))
        ProcessingResult.Success(RemoteSummaryMapper.toProcessingSummary(response, reports.size), name)
    } catch (error: Exception) { error.toRemoteFailure() }
}

fun Throwable.toRemoteFailure(): ProcessingResult.Failure {
    val message = message.orEmpty()
    return when {
        "401" in message -> ProcessingResult.Failure("Railway helper rejected the API key. Check helper settings.", "REMOTE_UNAUTHORIZED", false, this)
        "413" in message -> ProcessingResult.Failure("The selected report is too large for remote processing.", "REMOTE_PAYLOAD_TOO_LARGE", false, this)
        "429" in message -> ProcessingResult.Failure("Railway helper is temporarily rate limited. Retry shortly.", "REMOTE_RATE_LIMITED", true, this)
        "timed out" in message.lowercase() || this is java.net.SocketTimeoutException -> ProcessingResult.Failure("Remote processing is temporarily unavailable.", "REMOTE_TIMEOUT", true, this)
        message.contains("json", true) -> ProcessingResult.Failure("Remote helper returned an invalid response.", "REMOTE_INVALID_RESPONSE", true, this)
        this is RemotePayloadTooLargeException -> ProcessingResult.Failure(message.ifBlank { RemotePayloadBuilder.TOO_LARGE_MESSAGE }, "REMOTE_PAYLOAD_TOO_LARGE", false, this)
        else -> ProcessingResult.Failure("Remote processing is temporarily unavailable.", "REMOTE_UNAVAILABLE", true, this)
    }
}
