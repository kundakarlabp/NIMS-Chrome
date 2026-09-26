package org.kundakarlab.nimsfastsummarymobile

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.data.pdf.PdfBoxAndroidTextExtractor
import org.kundakarlab.nimsfastsummarymobile.data.processing.CultureEpisodeReconciler
import org.kundakarlab.nimsfastsummarymobile.data.processing.LocalTextReportProcessor
import org.kundakarlab.nimsfastsummarymobile.data.processing.OnDeviceReportProcessor
import org.kundakarlab.nimsfastsummarymobile.domain.model.Abnormality
import org.kundakarlab.nimsfastsummarymobile.domain.model.GrowthStatus
import org.kundakarlab.nimsfastsummarymobile.domain.model.NumericComparator
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedReport
import org.kundakarlab.nimsfastsummarymobile.domain.model.ReportInput
import org.kundakarlab.nimsfastsummarymobile.domain.processing.ProcessingResult
import java.util.concurrent.TimeUnit

class NimsAuthenticationRequiredException : IllegalStateException("NIMS authentication required")

class NimsBackgroundRetriever(context: Context) {
    private val appContext = context.applicationContext
    private val processor = OnDeviceReportProcessor(LocalTextReportProcessor(), PdfBoxAndroidTextExtractor(appContext))
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(50, TimeUnit.SECONDS)
        .callTimeout(65, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
    private val reportClient = NimsReportHttpClient(
        cookieProvider = { CookieManager.getInstance().getCookie(it).orEmpty() },
        userAgentProvider = { USER_AGENT }
    )

    suspend fun retrieve(crNo: String): JSONObject = withContext(Dispatchers.IO) {
        require(crNo.matches(Regex("""\d{15}"""))) { "Invalid CR number" }
        val (body, finalUrl) = fetchReportList(crNo)
        if (NimsBackgroundReportListParser.looksLikeLoginOrExpired(body, finalUrl)) {
            throw NimsAuthenticationRequiredException()
        }

        val reportList = NimsBackgroundReportListParser.parse(body)
        if (reportList.rows.isEmpty()) {
            if (Regex("login|captcha|password", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
                throw NimsAuthenticationRequiredException()
            }
            throw IllegalStateException("No NIMS reports were found for the requested CR")
        }

        val template = ReportTemplate(origin = NIMS_ORIGIN, pathname = NimsReportTemplate.VERIFIED_REPORT_PATH)
        val slots = Semaphore(4)
        val parsed = coroutineScope {
            reportList.rows.map { row ->
                async(Dispatchers.IO) {
                    slots.withPermit { fetchAndParse(row, template) }
                }
            }.awaitAll().filterNotNull()
        }
        if (parsed.isEmpty()) throw IllegalStateException("NIMS reports were found but none could be parsed")

        val reconciled = CultureEpisodeReconciler.reconcile(parsed)
        canonicalBundle(crNo, reportList.patientName, reportList.rows, reconciled)
    }

    private fun fetchReportList(crNo: String): Pair<String, String> {
        val cookie = CookieManager.getInstance().getCookie(CR_RESULTS_URL).orEmpty()
        if (cookie.isBlank()) throw NimsAuthenticationRequiredException()
        val form = FormBody.Builder()
            .add("hmode", "SHOWPATDETAILS")
            .add("patCrNo", crNo)
            .build()
        val request = Request.Builder()
            .url(CR_RESULTS_URL)
            .post(form)
            .header("Cookie", cookie)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .header("Origin", NIMS_ORIGIN)
            .header("Referer", CR_RESULTS_URL)
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) throw NimsAuthenticationRequiredException()
            if (!response.isSuccessful && !NimsBackgroundReportListParser.looksLikeLoginOrExpired(body, response.request.url.toString())) {
                throw IllegalStateException("NIMS CR request returned ${response.code}")
            }
            return body to response.request.url.toString()
        }
    }

    private suspend fun fetchAndParse(row: BackgroundReportRow, template: ReportTemplate): ParsedReport? {
        val url = NimsReportTemplate.directReportUrlOrNull(template, row.token) ?: return null
        val fetched = reportClient.fetch(url, MAX_REPORT_BYTES)
        if (ReportResponseClassifier.classify(fetched.statusCode, fetched.contentType, fetched.bytes) == "html_login_or_session") {
            throw NimsAuthenticationRequiredException()
        }
        val input = ReportInput(
            reportId = row.reportId,
            reportName = row.reportName,
            dateSent = row.dateSent,
            reportType = row.reportType,
            contentType = fetched.contentType.substringBefore(';').ifBlank { "application/octet-stream" },
            bytes = fetched.bytes,
            safeSource = fetched.finalUrlSafe
        )
        return when (val result = processor.parseReport(input)) {
            is ProcessingResult.Success -> result.value
            is ProcessingResult.Failure -> ParsedReport(
                reportId = row.reportId,
                reportName = row.reportName,
                dateSent = row.dateSent,
                reportType = row.reportType,
                warnings = listOf(result.userMessage),
                processorName = "On-device"
            )
            is ProcessingResult.Unsupported -> ParsedReport(
                reportId = row.reportId,
                reportName = row.reportName,
                dateSent = row.dateSent,
                reportType = row.reportType,
                warnings = listOf(result.reason),
                processorName = "On-device"
            )
        }
    }

    private fun canonicalBundle(
        crNo: String,
        patientName: String,
        sourceRows: List<BackgroundReportRow>,
        reports: List<ParsedReport>
    ): JSONObject {
        val results = JSONArray()
        reports.forEach { report ->
            report.labs.forEachIndexed { index, lab ->
                val valueText = when {
                    lab.numericValue != null -> comparatorPrefix(lab.comparator) + trimNumber(lab.numericValue)
                    !lab.textValue.isNullOrBlank() -> lab.textValue
                    else -> return@forEachIndexed
                }
                results.put(
                    JSONObject()
                        .put("id", "${report.reportId}-lab-$index")
                        .put("group", if (report.reportType == "culture") "Microbiology" else report.reportType)
                        .put("test", report.reportName)
                        .put("parameter", lab.displayName)
                        .put("date", lab.resultDate.orEmpty().ifBlank { report.dateSent })
                        .put("value", valueText)
                        .put("numericValue", lab.numericValue ?: JSONObject.NULL)
                        .put("unit", lab.unit.orEmpty())
                        .put("refRange", lab.referenceRangeText.orEmpty())
                        .put("abnormal", abnormality(lab.abnormality))
                        .put("reportId", report.reportId)
                )
            }
            report.cultures.forEachIndexed { index, culture ->
                val value = buildString {
                    append(
                        when (culture.growthStatus) {
                            GrowthStatus.NO_GROWTH -> "No growth"
                            GrowthStatus.GROWTH_DETECTED -> "Growth detected"
                            GrowthStatus.PENDING -> "Pending"
                            GrowthStatus.UNKNOWN -> "Culture result"
                        }
                    )
                    culture.organism?.takeIf(String::isNotBlank)?.let { append(": ").append(it) }
                    if (culture.susceptibility.isNotEmpty()) {
                        append(". AST: ")
                        append(culture.susceptibility.joinToString("; ") { "${it.antibiotic} ${it.interpretation}" })
                    }
                    if (culture.comments.isNotEmpty()) append(". ").append(culture.comments.joinToString(" | "))
                }
                results.put(
                    JSONObject()
                        .put("id", "${report.reportId}-culture-$index")
                        .put("group", "Microbiology")
                        .put("test", report.reportName)
                        .put("parameter", culture.specimen?.takeIf(String::isNotBlank)?.let { "$it culture" } ?: "Culture")
                        .put("date", culture.reportingDate.orEmpty().ifBlank { culture.collectionDate.orEmpty().ifBlank { report.dateSent } })
                        .put("value", value.take(4000))
                        .put("abnormal", "unknown")
                        .put("reportId", report.reportId)
                        .put("narrative", true)
                )
            }
        }

        val sourceReports = JSONArray().also { array ->
            sourceRows.forEach { row ->
                array.put(
                    JSONObject()
                        .put("id", row.reportId)
                        .put("title", row.reportName)
                        .put("date", row.dateSent)
                )
            }
        }

        return JSONObject()
            .put("schemaVersion", "nims-dashboard-canonical-v1")
            .put("patient", JSONObject().put("name", patientName).put("crNo", crNo))
            .put("results", results)
            .put("reports", sourceReports)
            .put("enquiryRows", JSONArray())
            .put(
                "coverage",
                JSONObject()
                    .put("resultCount", results.length())
                    .put("reportCount", sourceRows.size)
                    .put("enquiryCount", 0)
            )
    }

    private fun abnormality(value: Abnormality): String = when (value) {
        Abnormality.NORMAL -> "normal"
        Abnormality.HIGH -> "high"
        Abnormality.LOW -> "low"
        Abnormality.CRITICAL -> "critical"
        Abnormality.UNKNOWN -> "unknown"
    }

    private fun comparatorPrefix(value: NumericComparator): String = when (value) {
        NumericComparator.LESS_THAN -> "<"
        NumericComparator.GREATER_THAN -> ">"
        NumericComparator.EQUAL -> ""
    }

    private fun trimNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    companion object {
        private const val NIMS_ORIGIN = "https://www.nimsts.edu.in"
        private const val CR_RESULTS_URL = "$NIMS_ORIGIN/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt"
        private const val MAX_REPORT_BYTES = 25 * 1024 * 1024
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}
