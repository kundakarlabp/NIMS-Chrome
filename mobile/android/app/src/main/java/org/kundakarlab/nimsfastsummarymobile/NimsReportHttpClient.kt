package org.kundakarlab.nimsfastsummarymobile

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.kundakarlab.nimsfastsummarymobile.security.NimsUrlPolicy
import java.util.concurrent.TimeUnit

/** Shared authenticated client with connection pooling and bounded per-host requests. */
class NimsReportHttpClient(
    private val cookieProvider: (String) -> String,
    private val userAgentProvider: () -> String
) {
    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 6
            maxRequestsPerHost = 6
        })
        .connectionPool(ConnectionPool(6, 5, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun fetch(url: String, maxBytes: Int): ReportFetchResult {
        if (!NimsReportTemplate.isAllowedNimsUrl(url)) throw IllegalStateException("NIMS report URL is not allowed")
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookieProvider(url))
            .header("User-Agent", userAgentProvider())
            .header("Accept", "application/pdf,text/html,text/plain,*/*")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body ?: throw IllegalStateException("NIMS report response was empty")
            val declaredLength = body.contentLength()
            if (declaredLength > maxBytes) throw IllegalStateException("Report response exceeded 25 MB")
            val source = body.source()
            val buffer = okio.Buffer()
            var total = 0L
            while (true) {
                val read = source.read(buffer, 8192)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IllegalStateException("Report response exceeded 25 MB")
            }
            val bytes = buffer.readByteArray()
            val contentType = response.header("Content-Type").orEmpty()
            val classification = ReportResponseClassifier.classify(response.code, contentType, bytes)

            // Preserve the explicit unsuccessful-response gate required by the
            // repository contract. Session responses are the only exception:
            // they must reach MainActivity's established recovery handler.
            if (!response.isSuccessful) {
                if (classification != "html_login_or_session") {
                    throw failureFor(response.code, contentType, classification)
                        ?: IllegalStateException("NIMS report fetch returned ${response.code}")
                }
            }
            if (classification != "html_login_or_session") {
                failureFor(response.code, contentType, classification)?.let { throw it }
            }
            return ReportFetchResult(
                contentType = contentType,
                statusCode = response.code,
                finalUrlSafe = NimsUrlPolicy.safeSourceForHelper(response.request.url.toString()),
                bytes = bytes
            )
        }
    }

    companion object {
        internal fun failureFor(
            statusCode: Int,
            contentType: String,
            classification: String
        ): IllegalStateException? = when {
            classification == "html_login_or_session" -> null
            statusCode !in 200..299 ->
                IllegalStateException("NIMS report fetch returned $statusCode (${contentType.substringBefore(';')})")
            classification == "html_report_list" ->
                IllegalStateException("NIMS returned the report list instead of the selected report")
            classification == "invalid_pdf_response" ->
                IllegalStateException("NIMS returned invalid PDF content")
            classification !in setOf("pdf_report", "html_report_content") ->
                IllegalStateException("Report fetch returned $classification")
            else -> null
        }
    }
}
