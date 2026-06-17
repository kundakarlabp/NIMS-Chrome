package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.domain.model.ReportInput
import java.util.Base64

object RemotePayloadBuilder {
    const val MAX_REMOTE_REPORT_BYTES = 18 * 1024 * 1024
    const val TOO_LARGE_MESSAGE = "This report is too large for Railway processing. Select On-device only for supported text reports, or open the source report manually."
    fun build(input: ReportInput): JSONObject {
        if (input.bytes.size > MAX_REMOTE_REPORT_BYTES) throw RemotePayloadTooLargeException(TOO_LARGE_MESSAGE)
        val encodedContent = Base64.getEncoder().encodeToString(input.bytes)
        return JSONObject()
            .put("report_id", input.reportId)
            .put("report_name", input.reportName)
            .put("date_sent", input.dateSent)
            .put("source_url", input.safeSource)
            .put("content_type", input.contentType)
            .put("pdf_base64", encodedContent)
    }
}
class RemotePayloadTooLargeException(message: String) : IllegalStateException(message)
