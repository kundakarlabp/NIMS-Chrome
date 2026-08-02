package org.kundakarlab.nimsfastsummarymobile

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.kundakarlab.nimsfastsummarymobile.security.NimsUrlPolicy
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** Shared authenticated client with connection pooling, bounded requests and transient retry. */
class NimsReportHttpClient(
    private val cookieProvider: (String) -> String,
    private val userAgentProvider: () -> String,
    private val sleeper: (Long) -> Unit = Thread::sleep
) {
    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 4
            maxRequestsPerHost = 4
        })
        .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(50, TimeUnit.SECONDS)
        .callTimeout(65, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun fetch(url: String, maxBytes: Int): ReportFetchResult {
        var lastFailure: Throwable? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return execute(url, maxBytes)
            } catch (error: Throwable) {
                if (!isTransient(error) || attempt == MAX_ATTEMPTS) throw error
                lastFailure = error
                sleeper(retryDelayMs(attempt, url.hashCode()))
            }
        }
        throw lastFailure ?: IllegalStateException("NIMS report fetch failed")
    }

    private fun execute(url: String, maxBytes: Int): ReportFetchResult {
        if (!NimsReportTemplate.isAllowedNimsUrl(url)) throw IllegalStateException("NIMS report URL is not allowed")
        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookieProvider(url))
            .header("User-Agent", userAgentProvider())
            .header("Accept", "application/pdf,text/html,text/plain,*/*")
            .header("Connection", "keep-alive")
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
        private const val MAX_ATTEMPTS = 3

        internal fun retryDelayMs(attempt: Int, stableSeed: Int): Long {
            val base = min(250L * (1L shl (attempt - 1)), 1_500L)
            val jitter = (stableSeed.toLong().let { if (it < 0) -it else it } % 180L)
            return base + jitter
        }

        internal fun isTransient(error: Throwable): Boolean = when (error) {
            is SocketTimeoutException, is SocketException -> true
            is IOException -> {
                val message = error.message.orEmpty().lowercase()
                listOf("failed to connect", "connection abort", "connection reset", "unexpected end", "timeout", "stream was reset").any(message::contains)
            }
            else -> false
        }

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
