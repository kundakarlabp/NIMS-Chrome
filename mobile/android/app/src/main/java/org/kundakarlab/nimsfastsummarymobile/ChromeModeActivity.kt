package org.kundakarlab.nimsfastsummarymobile

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.data.pdf.PdfBoxAndroidTextExtractor
import org.kundakarlab.nimsfastsummarymobile.data.processing.LocalTextReportProcessor
import org.kundakarlab.nimsfastsummarymobile.data.processing.OnDeviceReportProcessor
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedReport
import org.kundakarlab.nimsfastsummarymobile.domain.model.ReportInput
import org.kundakarlab.nimsfastsummarymobile.domain.model.SummaryMode
import org.kundakarlab.nimsfastsummarymobile.domain.processing.ProcessingResult
import org.kundakarlab.nimsfastsummarymobile.security.NimsUrlPolicy
import org.kundakarlab.nimsfastsummarymobile.ui.formatters.ClinicalSummaryFormatter
import org.kundakarlab.nimsfastsummarymobile.ui.mappers.SummaryJsonMapper
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Browser-first NIMS activity.
 *
 * NIMS receives no app JavaScript during login or navigation. A single read-only
 * extraction script runs only after the clinician taps Analyze on a visible CR
 * result list. Report retrieval and parsing then occur outside the WebView.
 */
class ChromeModeActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var extractor: OnDemandNimsExtractor
    private lateinit var secureSettings: SecureSettings

    private var stage by mutableStateOf(PortalStage.BROWSING)
    private var statusMessage by mutableStateOf(DEFAULT_GUIDANCE)
    private var currentPage by mutableStateOf("NIMS")
    private var loadProgress by mutableIntStateOf(0)
    private var selectedTab by mutableIntStateOf(0)
    private var showMore by mutableStateOf(false)
    private var uiSummary by mutableStateOf<UiSummary?>(null)
    private var physicianNote by mutableStateOf("")
    private var savedSummaryJson = ""
    private var activeJob: Job? = null
    private var webViewUserAgent = ""
    private var lastLoadedUrl = NIMS_LOGIN_URL

    private val localProcessor by lazy {
        OnDeviceReportProcessor(
            textProcessor = LocalTextReportProcessor(),
            pdfExtractor = PdfBoxAndroidTextExtractor(applicationContext),
            onPdfProgress = { completed, total ->
                updateStatus(PortalStage.PROCESSING, "Reading PDF page $completed of $total…")
            }
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureSettings = SecureSettings(this)
        physicianNote = secureSettings.physicianNote()
        restoreSavedSummary()

        CookieManager.getInstance().setAcceptCookie(true)
        webView = createWebView()
        extractor = OnDemandNimsExtractor(webView)
        webViewUserAgent = webView.settings.userAgentString

        setContent {
            ChromeModeTheme {
                ChromeModeApp(
                    webView = webView,
                    stage = stage,
                    statusMessage = statusMessage,
                    currentPage = currentPage,
                    loadProgress = loadProgress,
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    showMore = showMore,
                    onToggleMore = { showMore = !showMore },
                    onBack = { if (webView.canGoBack()) webView.goBack() },
                    onReload = { webView.reload() },
                    onLogin = { webView.loadUrl(NIMS_LOGIN_URL) },
                    onClearSession = ::clearNimsSession,
                    onAnalyze = { analyzeVisibleResults(NimsAnalysisMode.FAST) },
                    onAnalyzeCultures = { analyzeVisibleResults(NimsAnalysisMode.CULTURES_ONLY) },
                    onAnalyzeFull = { analyzeVisibleResults(NimsAnalysisMode.FULL) },
                    onStop = { activeJob?.cancel() },
                    summary = uiSummary,
                    physicianNote = physicianNote,
                    onPhysicianNoteChange = ::updatePhysicianNote,
                    onCopySummary = { copyText("NIMS Fast Summary", cleanSummaryText()) },
                    onExportSummary = { shareText("NIMS Fast Summary", cleanSummaryText()) },
                    onClearResults = ::clearResults
                )
            }
        }

        webView.loadUrl(NIMS_LOGIN_URL)
        webView.requestFocus()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        return WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(false)
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.defaultTextEncodingName = "UTF-8"
            settings.userAgentString = desktopChromeUserAgent(
                WebSettings.getDefaultUserAgent(this@ChromeModeActivity)
            )
            setInitialScale(90)
            isFocusable = true
            isFocusableInTouchMode = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    loadProgress = newProgress
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    // Console output stays available through chrome://inspect in debug builds.
                    return true
                }
            }

            webViewClient = NimsWebViewClient(
                onPageChanged = { safeUrl ->
                    lastLoadedUrl = url ?: NIMS_LOGIN_URL
                    currentPage = safeUrl.ifBlank { "NIMS" }
                    if (activeJob?.isActive != true) {
                        updateStatus(PortalStage.BROWSING, DEFAULT_GUIDANCE)
                    }
                },
                onBlockedInternalNavigation = {
                    updateStatus(PortalStage.ERROR, "NIMS attempted an unapproved internal navigation.")
                },
                onResourceError = { detail ->
                    if (BuildConfig.DEBUG) currentPage = detail.take(140)
                }
            )
        }
    }

    private fun analyzeVisibleResults(mode: NimsAnalysisMode) {
        if (activeJob?.isActive == true) {
            updateStatus(PortalStage.PROCESSING, "Analysis is already running.")
            return
        }
        CookieManager.getInstance().flush()
        updateStatus(PortalStage.SCANNING, "Reading the visible NIMS result page…")
        extractor.extract { result ->
            result.fold(
                onSuccess = { handleExtraction(mode, it) },
                onFailure = {
                    updateStatus(
                        PortalStage.ERROR,
                        it.message ?: "Unable to read the current NIMS page."
                    )
                }
            )
        }
    }

    private fun handleExtraction(mode: NimsAnalysisMode, extracted: JSONObject) {
        if (!extracted.optBoolean("ok")) {
            updateStatus(
                PortalStage.ERROR,
                extracted.optString("error", "NIMS page extraction failed.")
            )
            return
        }

        val rows = extracted.optJSONArray("rows") ?: JSONArray()
        if (rows.length() == 0) {
            updateStatus(PortalStage.ERROR, noRowsMessage(extracted))
            return
        }

        val templateJson = extracted.optJSONObject("template")
        if (templateJson == null) {
            updateStatus(
                PortalStage.ERROR,
                "Report rows were found, but the live NIMS report request could not be verified."
            )
            return
        }

        val template = ReportTemplate(
            origin = templateJson.optString("origin"),
            pathname = templateJson.optString("pathname"),
            modeParamName = templateJson.optString("modeParamName", "hmode"),
            modeParamValue = templateJson.optString("modeParamValue", "PRINTREPORT"),
            argumentParameterName = templateJson.optString("argumentParameterName", "fileName")
        )
        val selected = ReportRowSelector.select(rows, mode)
        if (selected.isEmpty()) {
            val message = if (mode == NimsAnalysisMode.CULTURES_ONLY) {
                "No culture reports were found."
            } else {
                "No usable reports were found."
            }
            updateStatus(PortalStage.ERROR, message)
            return
        }

        val prepared = selected.mapNotNull { row -> prepareRequest(template, row) }
        if (prepared.isEmpty()) {
            updateStatus(
                PortalStage.ERROR,
                "The visible rows did not contain safe NIMS report references."
            )
            return
        }
        startAnalysis(mode, prepared)
    }

    private fun noRowsMessage(extracted: JSONObject): String = when (extracted.optString("pageKind")) {
        "login" -> "Login to NIMS first."
        "cr_search" -> "Enter and submit the CR number, then keep the result list visible."
        else -> if (extracted.optInt("blockedFrames") > 0) {
            "No report rows were reachable. Keep the CR result list visible in the main NIMS page and retry."
        } else {
            "No View Report rows were found. Open the submitted CR result list and retry."
        }
    }

    private fun prepareRequest(template: ReportTemplate, row: JSONObject): OnDemandReportRequest? {
        val transient = row.optString("transientPrintReportArg")
        return try {
            OnDemandReportRequest(
                row = row,
                transientArg = transient,
                directUrl = NimsReportTemplate.directReportUrl(template, transient)
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun startAnalysis(mode: NimsAnalysisMode, requests: List<OnDemandReportRequest>) {
        activeJob = lifecycleScope.launch {
            updateStatus(PortalStage.PROCESSING, "Validating the first report…")
            try {
                val first = withContext(Dispatchers.IO) {
                    fetchAndParse(requests.first(), 1, requests.size)
                }
                if (first.labs.isEmpty() && first.cultures.isEmpty()) {
                    updateStatus(
                        PortalStage.ERROR,
                        first.warnings.firstOrNull() ?: "The first report could not be parsed."
                    )
                    return@launch
                }

                val remaining = if (requests.size > 1) {
                    processRemaining(requests.drop(1), requests.size)
                } else {
                    emptyList()
                }
                val reports = listOf(first) + remaining
                createSummary(reports, mode)
            } catch (cancelled: CancellationException) {
                updateStatus(PortalStage.BROWSING, "Analysis stopped.")
                throw cancelled
            } catch (error: Exception) {
                updateStatus(
                    PortalStage.ERROR,
                    error.message ?: "Report processing failed."
                )
            } finally {
                activeJob = null
            }
        }
    }

    private suspend fun processRemaining(
        requests: List<OnDemandReportRequest>,
        total: Int
    ): List<ParsedReport> = coroutineScope {
        val semaphore = Semaphore(2)
        requests.mapIndexed { index, request ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    ensureActive()
                    try {
                        fetchAndParse(request, index + 2, total)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        errorReport(request.row, error.message ?: "Report failed")
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun createSummary(reports: List<ParsedReport>, mode: NimsAnalysisMode) {
        val summaryMode = when (mode) {
            NimsAnalysisMode.FAST -> SummaryMode.FAST
            NimsAnalysisMode.CULTURES_ONLY -> SummaryMode.CULTURES_ONLY
            NimsAnalysisMode.FULL -> SummaryMode.FULL
        }
        updateStatus(PortalStage.PROCESSING, "Preparing summary from ${reports.size} reports…")
        when (val result = withContext(Dispatchers.IO) {
            localProcessor.summarize(reports, summaryMode)
        }) {
            is ProcessingResult.Success -> {
                val json = result.value.helperJson ?: localSummaryJson(reports, result.value.text)
                savedSummaryJson = json.toString()
                secureSettings.saveLastSummaryJson(savedSummaryJson)
                uiSummary = SummaryJsonMapper.parseSummaryJsonToUiSummary(json, physicianNote)
                selectedTab = 4
                updateStatus(
                    PortalStage.READY,
                    "Summary ready. Verify values against the source NIMS reports."
                )
            }
            is ProcessingResult.Unsupported -> updateStatus(PortalStage.ERROR, result.reason)
            is ProcessingResult.Failure -> updateStatus(PortalStage.ERROR, result.userMessage)
        }
    }

    private suspend fun fetchAndParse(
        request: OnDemandReportRequest,
        position: Int,
        total: Int
    ): ParsedReport {
        updateStatus(PortalStage.PROCESSING, "Fetching report $position of $total…")
        val response = fetchWithWebViewSession(request.directUrl)
        val classification = ReportResponseClassifier.classify(
            response.statusCode,
            response.contentType,
            response.bytes
        )
        if (classification == "html_login_or_session") {
            throw IllegalStateException("NIMS session expired. Login again and reopen the result list.")
        }
        if (classification !in setOf("pdf_report", "html_report_content")) {
            throw IllegalStateException("NIMS returned $classification instead of a report.")
        }

        val input = ReportInput(
            reportId = safeReportKey(request.transientArg, request.row),
            reportName = request.row.optString("report_name"),
            dateSent = request.row.optString("date_sent"),
            reportType = request.row.optString("report_type", "other"),
            contentType = response.contentType.substringBefore(';')
                .ifBlank { "application/octet-stream" },
            bytes = response.bytes,
            safeSource = NimsUrlPolicy.safeSourceForHelper(request.directUrl)
        )
        return when (val parsed = localProcessor.parseReport(input)) {
            is ProcessingResult.Success -> parsed.value.copy(
                warnings = parsed.value.warnings + parsed.warnings
            )
            is ProcessingResult.Unsupported -> errorReport(request.row, parsed.reason)
            is ProcessingResult.Failure -> errorReport(request.row, parsed.userMessage)
        }
    }

    private fun fetchWithWebViewSession(url: String): NimsFetchResponse {
        require(NimsReportTemplate.isAllowedNimsUrl(url)) { "NIMS report URL is not allowed." }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 45_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", webViewUserAgent)
            setRequestProperty("Accept", "application/pdf,text/html,text/plain,*/*")
            setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
            CookieManager.getInstance().getCookie(url)
                ?.takeIf { it.isNotBlank() }
                ?.let { setRequestProperty("Cookie", it) }
            lastLoadedUrl.takeIf(NimsReportTemplate::isAllowedNimsUrl)
                ?.let { setRequestProperty("Referer", it) }
        }
        try {
            val status = connection.responseCode
            val finalUrl = connection.url.toString()
            require(NimsReportTemplate.isAllowedNimsUrl(finalUrl)) {
                "NIMS redirected the report request to an unapproved URL."
            }
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_REPORT_BYTES) {
                        throw IllegalStateException("Report exceeded the 25 MB safety limit.")
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            if (status >= 400) {
                throw IllegalStateException("NIMS report request returned HTTP $status.")
            }
            return NimsFetchResponse(connection.contentType.orEmpty(), status, bytes)
        } finally {
            connection.disconnect()
        }
    }

    private fun errorReport(row: JSONObject, message: String): ParsedReport = ParsedReport(
        reportId = row.optString("report_id", "error"),
        reportName = row.optString("report_name"),
        dateSent = row.optString("date_sent"),
        reportType = row.optString("report_type", "other"),
        warnings = listOf(message),
        processorName = "none"
    )

    private fun safeReportKey(transient: String, row: JSONObject): String {
        val material = listOf(
            transient,
            row.optString("date_sent"),
            row.optString("report_name"),
            row.optString("department")
        ).joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "report_key:$hash"
    }

    private fun localSummaryJson(reports: List<ParsedReport>, text: String): JSONObject =
        JSONObject()
            .put("source_reports", JSONArray().also { array ->
                reports.forEach { report ->
                    val unsupported = report.labs.isEmpty() && report.cultures.isEmpty()
                    array.put(
                        JSONObject()
                            .put("date_sent", report.dateSent)
                            .put("report_name", report.reportName)
                            .put("type", report.reportType)
                            .put("status", if (unsupported) "unsupported" else "parsed")
                            .put("notes", report.warnings.joinToString("; "))
                            .put("action", if (unsupported) "Open source report in NIMS" else "")
                    )
                }
            })
            .put("interpretation", JSONArray(text.lines()))

    private fun clearNimsSession() {
        activeJob?.cancel()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView.clearHistory()
            webView.clearFormData()
            webView.clearCache(true)
            runOnUiThread {
                webView.loadUrl(NIMS_LOGIN_URL)
                updateStatus(PortalStage.BROWSING, "Session cleared. Login to NIMS manually.")
            }
        }
    }

    private fun restoreSavedSummary() {
        savedSummaryJson = secureSettings.lastSummaryJson()
        if (savedSummaryJson.isNotBlank()) {
            try {
                uiSummary = SummaryJsonMapper.parseSummaryJsonToUiSummary(
                    JSONObject(savedSummaryJson),
                    physicianNote
                )
            } catch (_: Exception) {
                savedSummaryJson = ""
            }
        }
    }

    private fun updatePhysicianNote(value: String) {
        physicianNote = value
        secureSettings.savePhysicianNote(value)
        uiSummary = uiSummary?.copy(editableNote = value)
    }

    private fun clearResults() {
        secureSettings.clearResults()
        secureSettings.clearPhysicianNote()
        savedSummaryJson = ""
        physicianNote = ""
        uiSummary = null
        updateStatus(PortalStage.BROWSING, "Saved results cleared.")
    }

    private fun cleanSummaryText(): String = ClinicalSummaryFormatter.cleanText(
        (uiSummary ?: UiSummary()).copy(editableNote = physicianNote)
    )

    private fun copyText(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun shareText(title: String, text: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, title)
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                title
            )
        )
    }

    private fun updateStatus(newStage: PortalStage, message: String) {
        runOnUiThread {
            stage = newStage
            statusMessage = message
        }
    }

    override fun onDestroy() {
        activeJob?.cancel()
        if (::webView.isInitialized) {
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.webChromeClient = null
                webView.webViewClient = WebViewClient()
                webView.removeAllViews()
                webView.destroy()
            } catch (_: Exception) {
                // Activity destruction must continue even if WebView teardown fails.
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val NIMS_LOGIN_URL =
            "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action"
        private const val MAX_REPORT_BYTES = 25 * 1024 * 1024
        private const val DEFAULT_GUIDANCE =
            "Navigate in NIMS normally. When View Report rows are visible, tap Analyze."

        internal fun desktopChromeUserAgent(defaultUserAgent: String): String {
            val chromeVersion = Regex("(?:Chrome|Chromium)/([0-9.]+)")
                .find(defaultUserAgent)
                ?.groupValues
                ?.get(1)
                ?: "126.0.0.0"
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$chromeVersion Safari/537.36"
        }
    }
}

private data class OnDemandReportRequest(
    val row: JSONObject,
    val transientArg: String,
    val directUrl: String
)

private data class NimsFetchResponse(
    val contentType: String,
    val statusCode: Int,
    val bytes: ByteArray
)
