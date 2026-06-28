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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiCultureRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiLabTrendRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSourceReport
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Chrome-pattern implementation:
 *
 * 1. NIMS loads and navigates with no document-start injection or persistent JS bridge.
 * 2. The clinician reaches the CR result list using the normal NIMS interface.
 * 3. A single read-only extractor runs only after Analyze is tapped.
 * 4. Reports are fetched with the authenticated WebView cookie session and parsed locally.
 */
class ChromeModeActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var extractor: OnDemandNimsExtractor
    private lateinit var secureSettings: SecureSettings

    private var stage by mutableStateOf(PortalStage.BROWSING)
    private var statusMessage by mutableStateOf("Login and use NIMS normally. Tap Analyze only when the report list is visible.")
    private var currentPage by mutableStateOf("NIMS")
    private var loadProgress by mutableIntStateOf(0)
    private var selectedTab by mutableIntStateOf(0)
    private var showMore by mutableStateOf(false)
    private var uiSummary by mutableStateOf<UiSummary?>(null)
    private var physicianNote by mutableStateOf("")
    private var rawSummaryJson by mutableStateOf("")
    private var activeJob: Job? = null
    private var userAgent = ""
    private var lastLoadedUrl = NIMS_LOGIN_URL

    private val localProcessor by lazy {
        OnDeviceReportProcessor(
            textProcessor = LocalTextReportProcessor(),
            pdfExtractor = PdfBoxAndroidTextExtractor(applicationContext),
            onPdfProgress = { completed, total -> updateStatus(PortalStage.PROCESSING, "Reading PDF page $completed of $total…") }
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureSettings = SecureSettings(this)
        physicianNote = secureSettings.physicianNote()
        loadSavedSummary()

        CookieManager.getInstance().setAcceptCookie(true)
        webView = createChromePatternWebView()
        extractor = OnDemandNimsExtractor(webView)
        userAgent = webView.settings.userAgentString

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
                    onClearSession = { clearNimsSession() },
                    onAnalyze = { analyzeVisibleResults(NimsAnalysisMode.FAST) },
                    onAnalyzeCultures = { analyzeVisibleResults(NimsAnalysisMode.CULTURES_ONLY) },
                    onAnalyzeFull = { analyzeVisibleResults(NimsAnalysisMode.FULL) },
                    onStop = { activeJob?.cancel() },
                    summary = uiSummary,
                    physicianNote = physicianNote,
                    onPhysicianNoteChange = { updatePhysicianNote(it) },
                    onCopySummary = { copyText("NIMS Fast Summary", cleanSummaryText()) },
                    onExportSummary = { shareText("NIMS Fast Summary", cleanSummaryText()) },
                    onClearResults = { clearResults() }
                )
            }
        }

        webView.loadUrl(NIMS_LOGIN_URL)
        webView.requestFocus()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createChromePatternWebView(): WebView {
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
            settings.userAgentString = currentDesktopChromeUserAgent(WebSettings.getDefaultUserAgent(this@ChromeModeActivity))
            setInitialScale(90)
            isFocusable = true
            isFocusableInTouchMode = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    loadProgress = newProgress
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    // Do not modify app state for NIMS console errors. They remain available
                    // through chrome://inspect in debug builds without filling the clinical UI.
                    return true
                }
            }

            webViewClient = NimsWebViewClient(
                onPageChanged = { safeUrl ->
                    lastLoadedUrl = url ?: NIMS_LOGIN_URL
                    currentPage = safeUrl.ifBlank { "NIMS" }
                    if (activeJob?.isActive != true) {
                        updateStatus(
                            PortalStage.BROWSING,
                            "Page loaded. Navigate in NIMS normally; when View Report rows are visible, tap Analyze."
                        )
                    }
                },
                onBlockedInternalNavigation = {
                    updateStatus(PortalStage.ERROR, "NIMS attempted a blocked internal navigation.")
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
            result.onFailure {
                updateStatus(PortalStage.ERROR, it.message ?: "Unable to read the current NIMS page.")
            }.onSuccess { extracted ->
                handleExtraction(mode, extracted)
            }
        }
    }

    private fun handleExtraction(mode: NimsAnalysisMode, extracted: JSONObject) {
        if (!extracted.optBoolean("ok")) {
            updateStatus(PortalStage.ERROR, extracted.optString("error", "NIMS page extraction failed."))
            return
        }
        val rows = extracted.optJSONArray("rows") ?: JSONArray()
        if (rows.length() == 0) {
            val pageKind = extracted.optString("pageKind")
            val blocked = extracted.optInt("blockedFrames")
            val message = when (pageKind) {
                "login" -> "Login to NIMS first."
                "cr_search" -> "Enter and submit the CR number; then keep the result list visible."
                else -> if (blocked > 0) {
                    "No report rows were reachable. Keep the CR result list visible in the main NIMS page and retry."
                } else {
                    "No View Report rows were found. Open the submitted CR result list and retry."
                }
            }
            updateStatus(PortalStage.ERROR, message)
            return
        }

        val templateJson = extracted.optJSONObject("template")
        if (templateJson == null) {
            updateStatus(PortalStage.ERROR, "Report rows were found, but the live NIMS report request could not be verified.")
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
            updateStatus(PortalStage.ERROR, if (mode == NimsAnalysisMode.CULTURES_ONLY) "No culture reports were found." else "No usable reports were found.")
            return
        }

        val prepared = selected.mapNotNull { row ->
            val transient = row.optString("transientPrintReportArg")
            runCatching {
                OnDemandReportRequest(
                    row = row,
                    transientArg = transient,
                    directUrl = NimsReportTemplate.directReportUrl(template, transient)
                )
            }.getOrNull()
        }
        if (prepared.isEmpty()) {
            updateStatus(PortalStage.ERROR, "The visible rows did not contain safe NIMS report references.")
            return
        }
        startAnalysis(mode, prepared)
    }

    private fun startAnalysis(mode: NimsAnalysisMode, prepared: List<OnDemandReportRequest>) {
        val job = lifecycleScope.launch {
            updateStatus(PortalStage.PROCESSING, "Validating the first report…")
            try {
                val first = withContext(Dispatchers.IO) { fetchAndParse(prepared.first(), 1, prepared.size) }
                if (first.labs.isEmpty() && first.cultures.isEmpty()) {
                    updateStatus(PortalStage.ERROR, first.warnings.firstOrNull() ?: "The first report could not be parsed.")
                    return@launch
                }

                val remaining = if (prepared.size > 1) processRemaining(prepared.drop(1), prepared.size) else emptyList()
                val parsedReports = listOf(first) + remaining
                val summaryMode = when (mode) {
                    NimsAnalysisMode.FULL -> SummaryMode.FULL
                    NimsAnalysisMode.CULTURES_ONLY -> SummaryMode.CULTURES_ONLY
                    NimsAnalysisMode.FAST -> SummaryMode.FAST
                }
                updateStatus(PortalStage.PROCESSING, "Preparing summary from ${parsedReports.size} reports…")
                when (val result = withContext(Dispatchers.IO) { localProcessor.summarize(parsedReports, summaryMode) }) {
                    is ProcessingResult.Success -> {
                        val json = result.value.helperJson ?: localSummaryJson(parsedReports, result.value.text)
                        rawSummaryJson = json.toString()
                        secureSettings.saveLastSummaryJson(rawSummaryJson)
                        uiSummary = SummaryJsonMapper.parseSummaryJsonToUiSummary(json, physicianNote)
                        selectedTab = 4
                        updateStatus(PortalStage.READY, "Summary ready. Verify values against the source NIMS reports.")
                    }
                    is ProcessingResult.Unsupported -> updateStatus(PortalStage.ERROR, result.reason)
                    is ProcessingResult.Failure -> updateStatus(PortalStage.ERROR, result.userMessage)
                }
            } catch (cancelled: CancellationException) {
                updateStatus(PortalStage.BROWSING, "Analysis stopped.")
                throw cancelled
            } catch (error: Exception) {
                updateStatus(PortalStage.ERROR, error.message ?: "Report processing failed.")
            } finally {
                if (activeJob == coroutineContext[Job]) activeJob = null
            }
        }
        activeJob = job
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
                    runCatching { fetchAndParse(request, index + 2, total) }
                        .getOrElse { errorReport(request.row, it.message ?: "Report failed") }
                }
            }
        }.awaitAll()
    }

    private suspend fun fetchAndParse(request: OnDemandReportRequest, position: Int, total: Int): ParsedReport {
        updateStatus(PortalStage.PROCESSING, "Fetching report $position of $total…")
        val response = fetchWithWebViewSession(request.directUrl)
        val classification = ReportResponseClassifier.classify(response.statusCode, response.contentType, response.bytes)
        if (classification == "html_login_or_session") throw IllegalStateException("NIMS session expired. Login again and reopen the result list.")
        if (classification !in setOf("pdf_report", "html_report_content")) throw IllegalStateException("NIMS returned $classification instead of a report.")

        val input = ReportInput(
            reportId = safeReportKey(request.transientArg, request.row),
            reportName = request.row.optString("report_name"),
            dateSent = request.row.optString("date_sent"),
            reportType = request.row.optString("report_type", "other"),
            contentType = response.contentType.substringBefore(';').ifBlank { "application/octet-stream" },
            bytes = response.bytes,
            safeSource = NimsUrlPolicy.safeSourceForHelper(request.directUrl)
        )
        return when (val parsed = localProcessor.parseReport(input)) {
            is ProcessingResult.Success -> parsed.value.copy(warnings = parsed.value.warnings + parsed.warnings)
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
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/pdf,text/html,text/plain,*/*")
            setRequestProperty("Accept-Language", "en-IN,en;q=0.9")
            CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { setRequestProperty("Cookie", it) }
            lastLoadedUrl.takeIf { NimsReportTemplate.isAllowedNimsUrl(it) }?.let { setRequestProperty("Referer", it) }
        }
        try {
            val status = connection.responseCode
            val finalUrl = connection.url.toString()
            require(NimsReportTemplate.isAllowedNimsUrl(finalUrl)) { "NIMS redirected the report request to an unapproved URL." }
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_REPORT_BYTES) throw IllegalStateException("Report exceeded the 25 MB safety limit.")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            if (status >= 400) throw IllegalStateException("NIMS report request returned HTTP $status.")
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
        val material = listOf(transient, row.optString("date_sent"), row.optString("report_name"), row.optString("department")).joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "report_key:$hash"
    }

    private fun localSummaryJson(reports: List<ParsedReport>, text: String): JSONObject = JSONObject()
        .put("source_reports", JSONArray().also { array ->
            reports.forEach { report ->
                array.put(
                    JSONObject()
                        .put("date_sent", report.dateSent)
                        .put("report_name", report.reportName)
                        .put("type", report.reportType)
                        .put("status", if (report.labs.isEmpty() && report.cultures.isEmpty()) "unsupported" else "parsed")
                        .put("notes", report.warnings.joinToString("; "))
                        .put("action", if (report.labs.isEmpty() && report.cultures.isEmpty()) "Open source report in NIMS" else "")
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

    private fun loadSavedSummary() {
        rawSummaryJson = secureSettings.lastSummaryJson()
        if (rawSummaryJson.isNotBlank()) {
            runCatching { uiSummary = SummaryJsonMapper.parseSummaryJsonToUiSummary(JSONObject(rawSummaryJson), physicianNote) }
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
        rawSummaryJson = ""
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
        runCatching {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            webView.removeAllViews()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val NIMS_LOGIN_URL = "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action"
        private const val MAX_REPORT_BYTES = 25 * 1024 * 1024

        internal fun currentDesktopChromeUserAgent(defaultUserAgent: String): String {
            val chromeVersion = Regex("(?:Chrome|Chromium)/([0-9.]+)").find(defaultUserAgent)?.groupValues?.get(1)
                ?: "126.0.0.0"
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/$chromeVersion Safari/537.36"
        }
    }
}

private enum class PortalStage {
    BROWSING,
    SCANNING,
    PROCESSING,
    READY,
    ERROR
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

@Composable
private fun ChromeModeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF075985),
            secondary = Color(0xFF006B5F),
            error = Color(0xFFB3261E)
        ),
        content = content
    )
}

@Composable
private fun ChromeModeApp(
    webView: WebView,
    stage: PortalStage,
    statusMessage: String,
    currentPage: String,
    loadProgress: Int,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    showMore: Boolean,
    onToggleMore: () -> Unit,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onLogin: () -> Unit,
    onClearSession: () -> Unit,
    onAnalyze: () -> Unit,
    onAnalyzeCultures: () -> Unit,
    onAnalyzeFull: () -> Unit,
    onStop: () -> Unit,
    summary: UiSummary?,
    physicianNote: String,
    onPhysicianNoteChange: (String) -> Unit,
    onCopySummary: () -> Unit,
    onExportSummary: () -> Unit,
    onClearResults: () -> Unit
) {
    Scaffold(
        topBar = { CompactHeader(currentPage, statusMessage, loadProgress) },
        bottomBar = {
            NavigationBar {
                listOf("NIMS", "Reports", "Trends", "Cultures", "Summary").forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { onTabSelected(index) },
                        icon = { Text(label.take(1)) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (selectedTab) {
            0 -> PortalScreen(
                modifier = modifier,
                webView = webView,
                stage = stage,
                showMore = showMore,
                onToggleMore = onToggleMore,
                onBack = onBack,
                onReload = onReload,
                onLogin = onLogin,
                onClearSession = onClearSession,
                onAnalyze = onAnalyze,
                onAnalyzeCultures = onAnalyzeCultures,
                onAnalyzeFull = onAnalyzeFull,
                onStop = onStop
            )
            1 -> SourceReportsScreen(modifier, summary?.sourceReports.orEmpty())
            2 -> LabTrendsScreen(modifier, summary?.labTrends.orEmpty())
            3 -> CultureResultsScreen(modifier, summary?.cultures.orEmpty())
            else -> ClinicalSummaryScreen(
                modifier,
                summary,
                physicianNote,
                onPhysicianNoteChange,
                onCopySummary,
                onExportSummary,
                onClearResults
            )
        }
    }
}

@Composable
private fun CompactHeader(currentPage: String, message: String, progress: Int) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text("NIMS Fast Summary", color = Color.White, fontWeight = FontWeight.Bold)
        Text(currentPage, color = Color(0xFFD7E8FF), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        Text(
            if (progress in 1..99) "Loading $progress%" else message,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun PortalScreen(
    modifier: Modifier,
    webView: WebView,
    stage: PortalStage,
    showMore: Boolean,
    onToggleMore: () -> Unit,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onLogin: () -> Unit,
    onClearSession: () -> Unit,
    onAnalyze: () -> Unit,
    onAnalyzeCultures: () -> Unit,
    onAnalyzeFull: () -> Unit,
    onStop: () -> Unit
) {
    Column(modifier) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { OutlinedButton(onClick = onBack) { Text("Back") } }
            item { OutlinedButton(onClick = onReload) { Text("Reload") } }
            item { Button(onClick = onAnalyze, enabled = stage !in setOf(PortalStage.SCANNING, PortalStage.PROCESSING)) { Text("Analyze") } }
            item { OutlinedButton(onClick = onToggleMore) { Text(if (showMore) "Less" else "More") } }
            if (stage == PortalStage.PROCESSING || stage == PortalStage.SCANNING) {
                item { OutlinedButton(onClick = onStop) { Text("Stop") } }
            }
        }
        if (showMore) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item { OutlinedButton(onClick = onLogin) { Text("Login") } }
                item { OutlinedButton(onClick = onAnalyzeCultures) { Text("Cultures") } }
                item { OutlinedButton(onClick = onAnalyzeFull) { Text("Full") } }
                item { OutlinedButton(onClick = onClearSession) { Text("Clear session") } }
            }
        }
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun SourceReportsScreen(modifier: Modifier, reports: List<UiSourceReport>) {
    LazyColumn(modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Source reports (${reports.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (reports.isEmpty()) item { SimpleCard("No reports parsed yet.") }
        items(reports) { report ->
            SimpleCard {
                Text(report.reportName.ifBlank { "Report" }, fontWeight = FontWeight.Bold)
                Text("${report.dateSent.ifBlank { "No date" }} · ${report.type}", style = MaterialTheme.typography.bodySmall)
                Text(report.status, color = if (report.hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                if (report.notes.isNotBlank()) Text(report.notes, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LabTrendsScreen(modifier: Modifier, rows: List<UiLabTrendRow>) {
    LazyColumn(modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Lab trends (${rows.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (rows.isEmpty()) item { SimpleCard("No laboratory trends yet.") }
        items(rows) { row ->
            SimpleCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.parameter, fontWeight = FontWeight.Bold)
                        Text(row.latestDate.ifBlank { "No date" }, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(row.latestValue.ifBlank { "-" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text(row.trendText, style = MaterialTheme.typography.bodySmall)
                if (row.history.isNotEmpty()) Text(row.history.take(5).joinToString(" | ") { "${it.first}: ${it.second}" }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CultureResultsScreen(modifier: Modifier, rows: List<UiCultureRow>) {
    LazyColumn(modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Cultures (${rows.size})", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (rows.isEmpty()) item { SimpleCard("No culture results yet.") }
        items(rows) { row ->
            SimpleCard {
                Text(row.organism.ifBlank { row.status.ifBlank { "Culture" } }, fontWeight = FontWeight.Bold)
                Text(row.collectionDate.ifBlank { "No date" }, style = MaterialTheme.typography.bodySmall)
                Text(row.site.ifBlank { row.specimen }.ifBlank { "Site/specimen not parsed" })
                if (row.sensitivitySummary.isNotBlank()) Text(row.sensitivitySummary, style = MaterialTheme.typography.bodySmall)
                if (row.comment.isNotBlank()) Text(row.comment, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ClinicalSummaryScreen(
    modifier: Modifier,
    summary: UiSummary?,
    physicianNote: String,
    onPhysicianNoteChange: (String) -> Unit,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit
) {
    LazyColumn(modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Clinical summary", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        if (summary == null) {
            item { SimpleCard("No summary generated yet.") }
        } else {
            item {
                SimpleCard {
                    Text("Reports: ${summary.sourceReports.size} · Failed: ${summary.failedReportCount}")
                    Text("Cultures: ${summary.cultures.size} · Trends: ${summary.labTrends.size}")
                    Text(summary.dateRange, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(summary.interpretation) { line -> SimpleCard(line) }
        }
        item {
            OutlinedTextField(
                value = physicianNote,
                onValueChange = onPhysicianNoteChange,
                label = { Text("Physician note") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCopy) { Text("Copy") }
                OutlinedButton(onClick = onExport) { Text("Share") }
                TextButton(onClick = onClear) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun SimpleCard(text: String) {
    SimpleCard { Text(text) }
}

@Composable
private fun SimpleCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
    }
}
