package org.kundakarlab.nimsfastsummarymobile

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.ui.formatters.ClinicalSummaryFormatter
import org.kundakarlab.nimsfastsummarymobile.ui.mappers.SummaryJsonMapper
import org.kundakarlab.nimsfastsummarymobile.ui.models.Abnormality
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiCultureRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiLabTrendRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSourceReport
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var settings: SecureSettings
    private var mapping: ReportTemplate? = null
    private var mappingValidated = false
    private var webViewUserAgent = ""

    private var appStateValue by mutableStateOf(AppState.NEED_HELPER_SETTINGS)
    private var statusMessage by mutableStateOf("Open NIMS and login manually.")
    private var currentPage by mutableStateOf("NIMS login")
    private var loadProgress by mutableIntStateOf(0)
    private var helperUrlInput by mutableStateOf("")
    private var helperKeyInput by mutableStateOf("")
    private var logText by mutableStateOf("")
    private var showSettings by mutableStateOf(false)
    private var selectedTab by mutableIntStateOf(0)
    private var uiSummary by mutableStateOf<UiSummary?>(null)
    private var sanitizedSummaryText by mutableStateOf("")
    private var physicianNote by mutableStateOf("")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SecureSettings(this)
        helperUrlInput = settings.helperUrl()
        physicianNote = settings.physicianNote()
        loadPersistedSummary()
        CookieManager.getInstance().setAcceptCookie(true)
        webView = createWebView()
        webViewUserAgent = webView.settings.userAgentString
        setState(
            if (settings.helperUrl().isBlank() || !settings.hasApiKey()) AppState.NEED_HELPER_SETTINGS else AppState.HELPER_READY,
            if (settings.helperUrl().isBlank() || !settings.hasApiKey()) "Add Railway helper URL and API key." else "Helper ready. Login to NIMS manually."
        )
        setContent {
            NimsTheme {
                NimsFastSummaryApp(
                    webView = webView,
                    state = appStateValue,
                    statusMessage = statusMessage,
                    currentPage = currentPage,
                    loadProgress = loadProgress,
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    helperUrl = helperUrlInput,
                    helperKey = helperKeyInput,
                    onHelperUrlChange = { helperUrlInput = it },
                    onHelperKeyChange = { helperKeyInput = it },
                    showSettings = showSettings,
                    onShowSettings = { showSettings = true },
                    onDismissSettings = { showSettings = false },
                    onSaveHelper = { saveHelperSettings() },
                    onTestHelper = { testHelper() },
                    onClearHelper = { clearHelperSettings() },
                    onNimsLogin = { webView.loadUrl(NIMS_LOGIN_URL) },
                    onBack = { if (webView.canGoBack()) webView.goBack() },
                    onForward = { if (webView.canGoForward()) webView.goForward() },
                    onReload = { webView.reload() },
                    onZoomIn = { webView.zoomIn() },
                    onZoomOut = { webView.zoomOut() },
                    onDiagnose = { diagnosePage() },
                    onDiscover = { discoverMapping() },
                    onTestOne = { runMode("test_direct") },
                    onFast = { runMode("bulk_fast") },
                    onCulturesOnly = { runMode("bulk_cultures_only") },
                    onFull = { runMode("bulk_full") },
                    summary = uiSummary,
                    physicianNote = physicianNote,
                    onPhysicianNoteChange = { updatePhysicianNote(it) },
                    logText = logText,
                    onCopySummary = { copyCleanSummary() },
                    onCopyJson = { copyText("NIMS Fast Summary JSON", sanitizedSummaryText.ifBlank { "{}" }) },
                    onExportText = { shareText("NIMS Fast Summary", cleanSummaryText()) },
                    onClearResults = { clearResults() }
                )
            }
        }
        webView.loadUrl(NIMS_LOGIN_URL)
        webView.requestFocus()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.textZoom = 100
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            isFocusable = true
            isFocusableInTouchMode = true
            setInitialScale(85)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    loadProgress = newProgress
                }

                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    val popup = WebView(view.context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(popupView: WebView, request: WebResourceRequest): Boolean {
                                webView.loadUrl(request.url.toString())
                                return true
                            }

                            override fun onPageFinished(popupView: WebView, url: String) {
                                if (url.isNotBlank() && url != "about:blank") webView.loadUrl(url)
                            }
                        }
                    }
                    val transport = resultMsg.obj as WebView.WebViewTransport
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
            }
            webViewClient = NimsWebViewClient { safeUrl ->
                currentPage = safeUrl.ifBlank { "NIMS" }
            }
        }
    }

    private fun saveHelperSettings() {
        runCatching {
            settings.saveHelperUrl(helperUrlInput)
            if (helperKeyInput.isBlank() && !settings.hasApiKey()) throw IllegalArgumentException("Set Railway helper API key first.")
            settings.saveApiKey(helperKeyInput)
        }.onFailure {
            setState(AppState.ERROR, it.message ?: "Helper settings invalid")
            return
        }
        helperKeyInput = ""
        showSettings = false
        setState(AppState.HELPER_READY, "Helper settings saved. Login to NIMS manually.")
    }

    private fun clearHelperSettings() {
        settings.clear()
        helperUrlInput = ""
        helperKeyInput = ""
        uiSummary = null
        sanitizedSummaryText = ""
        physicianNote = ""
        setState(AppState.NEED_HELPER_SETTINGS, "Helper settings cleared.")
    }

    private fun testHelper() {
        Thread {
            runCatching {
                val health = publicHelper().health()
                val version = publicHelper().version()
                "Helper ok: version=${version.optString("version")} remote_mode=${health.optBoolean("remote_mode")} cache_enabled=${health.optBoolean("cache_enabled")} api_key_configured=${health.optBoolean("api_key_configured")}"
            }
                .onSuccess { setState(AppState.HELPER_READY, it) }
                .onFailure { setState(AppState.ERROR, "Helper connection failed: ${it.message}") }
        }.start()
    }

    private fun diagnosePage() {
        evaluateCore("JSON.stringify(NimsReportCore.diagnosePage(document))") { json ->
            val rows = json.optInt("viewReportRowCount", json.optInt("view_report_rows"))
            if (rows > 0 && appStateValue.ordinal < AppState.REPORT_PAGE_READY.ordinal) {
                setState(AppState.REPORT_PAGE_READY, "Report list detected. Discover mapping.")
            }
            log("Diagnosis: ${safeJson(json)}")
        }
    }

    private fun discoverMapping() {
        mappingValidated = false
        evaluateCore("JSON.stringify(NimsReportCore.clickFirstReportForMode('test_direct', document))") { click ->
            if (!click.optBoolean("ok")) {
                setState(AppState.ERROR, click.optString("error", "No View Report button found for row"))
                return@evaluateCore
            }
            log("Waiting for report mapping")
            Handler(Looper.getMainLooper()).postDelayed({
                evaluateCore("JSON.stringify(NimsReportCore.discoverSetPdfTemplate(document))") { template ->
                    if (!template.optBoolean("discovered")) {
                        setState(AppState.ERROR, "Mapping not discovered. Open the report page and retry.")
                        return@evaluateCore
                    }
                    mapping = ReportTemplate(
                        origin = template.optString("origin"),
                        pathname = template.optString("pathname"),
                        modeParamName = template.optString("modeParamName", "hmode"),
                        modeParamValue = template.optString("modeParamValue", "PRINTREPORT"),
                        argumentParameterName = template.optString("argumentParameterName", "fileName")
                    )
                    mappingValidated = false
                    setState(AppState.MAPPING_DISCOVERED, "Mapping ready. Run Test One Report.")
                }
            }, 1200)
        }
    }

    private fun runMode(mode: String) {
        val currentMapping = mapping
        if (currentMapping == null) {
            setState(AppState.ERROR, "Mapping not discovered. Tap Discover after opening the report list.")
            return
        }
        if (mode != "test_direct" && !mappingValidated) {
            setState(AppState.ERROR, "Run Test One Report successfully before bulk summary.")
            return
        }
        evaluateJson("JSON.stringify(NimsReportCore.rowsFromBestFrame(document))") { rowsText ->
            val rows = JSONArray(rowsText)
            evaluateJson("JSON.stringify(NimsReportCore.selectRowsForMode(${rows}, '$mode'))") { selectedText ->
                val selectedAll = JSONArray(selectedText)
                val selected = if (mode == "test_direct" && selectedAll.length() > 1) {
                    JSONArray().apply { selectedAll.optJSONObject(0)?.let { put(it) } }
                } else {
                    selectedAll
                }
                log("Selected ${selected.length()} reports")
                prepareReportRequests(selected, currentMapping) { prepared ->
                    Thread { fetchParseSummarize(mode, prepared) }.start()
                }
            }
        }
    }

    private fun prepareReportRequests(selected: JSONArray, template: ReportTemplate, callback: (List<PreparedReportRequest>) -> Unit) {
        val prepared = mutableListOf<PreparedReportRequest>()
        fun step(index: Int) {
            if (index >= selected.length()) {
                callback(prepared)
                return
            }
            val row = selected.optJSONObject(index)
            if (row == null) {
                step(index + 1)
                return
            }
            evaluateCore("JSON.stringify(NimsReportCore.transientPayloadForRow(${row}, document))") { payload ->
                val transient = payload.optString("transientPrintReportArg")
                if (transient.isBlank()) {
                    log("Skipping ${row.optString("report_name", "report")}: Required report argument missing")
                } else {
                    prepared.add(PreparedReportRequest(row, transient, NimsReportTemplate.directReportUrl(template, transient)))
                }
                step(index + 1)
            }
        }
        step(0)
    }

    private fun fetchParseSummarize(mode: String, prepared: List<PreparedReportRequest>) {
        setState(AppState.FETCHING, "Fetching and parsing reports...")
        val parsedReports = JSONArray()
        if (mode == "test_direct") {
            val request = prepared.firstOrNull()
            if (request == null) {
                setState(AppState.ERROR, "No report selected for Test One Report.")
                return
            }
            val report = fetchAndParseOne(request, 0, 1)
            parsedReports.put(report)
            val valid = isParsedReportValid(report)
            mappingValidated = valid
            if (!valid) {
                mappingValidated = false
                setState(AppState.ERROR, report.optJSONArray("errors")?.optString(0) ?: "Test One Report did not parse a report.")
                return
            }
        } else {
            val results = ReportFetchQueue(concurrency = 3).run(prepared.withIndex().toList()) { indexed ->
                fetchAndParseOne(indexed.value, indexed.index, prepared.size)
            }
            results.forEach { result ->
                parsedReports.put(result.getOrElse { errorReport(JSONObject(), it.message ?: "direct fetch failed") })
            }
        }
        val summaryMode = when (mode) {
            "bulk_full" -> "full"
            "bulk_cultures_only" -> "cultures_only"
            else -> "fast"
        }
        log("Summarizing ${parsedReports.length()}/${prepared.size}")
        runCatching {
            helper().summarize(JSONObject().put("mode", summaryMode).put("reports", parsedReports))
        }
            .onSuccess { summary ->
                runOnUiThread {
                    sanitizedSummaryText = summary.toString()
                    settings.saveLastSummaryJson(sanitizedSummaryText)
                    uiSummary = SummaryJsonMapper.parseSummaryJsonToUiSummary(summary, physicianNote)
                    selectedTab = 4
                    setState(AppState.SUMMARY_READY, "Summary ready.")
                }
            }
            .onFailure { error ->
                setState(AppState.ERROR, "Summary failed: ${error.message ?: "unknown error"}")
            }
    }

    private fun fetchAndParseOne(request: PreparedReportRequest, index: Int, total: Int): JSONObject {
        val row = request.row
        return runCatching {
            validateHelperSettings()
            log("Fetching selected report ${index + 1}/$total")
            val transient = request.transientArg
            val url = request.directUrl
            val response = fetchWithWebViewCookies(url)
            val classification = ReportResponseClassifier.classify(response.statusCode, response.contentType, response.bytes)
            if (classification == "html_login_or_session") throw IllegalStateException("NIMS session appears expired. Login again in the WebView.")
            if (classification !in setOf("pdf_report", "html_report_content")) throw IllegalStateException("Report fetch returned $classification")
            log("Parsing report ${index + 1}/$total")
            val payload = JSONObject()
                .put("report_id", safeReportKey(transient, row))
                .put("report_name", row.optString("report_name"))
                .put("date_sent", row.optString("date_sent"))
                .put("source_url", SafeUrl.hostPath(url))
                .put("content_type", contentType(response.contentType))
                .put("pdf_base64", Base64.encodeToString(response.bytes, Base64.NO_WRAP))
            helper().parseReport(payload)
        }.getOrElse {
            errorReport(row, it.message ?: "direct fetch failed")
        }
    }

    private fun fetchWithWebViewCookies(url: String): ReportFetchResult {
        if (!NimsReportTemplate.isAllowedNimsUrl(url)) throw IllegalStateException("NIMS report URL is not allowed")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 45_000
            setRequestProperty("Cookie", CookieManager.getInstance().getCookie(url).orEmpty())
            setRequestProperty("User-Agent", webViewUserAgent)
            setRequestProperty("Accept", "application/pdf,text/html,text/plain,*/*")
        }
        val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        val bytes = stream?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_REPORT_BYTES) throw IllegalStateException("Report response exceeded 25 MB")
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } ?: ByteArray(0)
        if (connection.responseCode >= 400) {
            throw IllegalStateException("NIMS report fetch returned ${connection.responseCode} (${contentType(connection.contentType.orEmpty())})")
        }
        return ReportFetchResult(connection.contentType.orEmpty(), connection.responseCode, SafeUrl.hostPath(connection.url.toString()), bytes)
    }

    private fun contentType(value: String): String = value.substringBefore(";").ifBlank { "application/octet-stream" }

    private fun helper(): HelperApiClient {
        validateHelperSettings()
        return HelperApiClient(settings.helperUrl()) { settings.apiKey() }
    }

    private fun publicHelper(): HelperApiClient {
        val url = HelperSettingsValidator.normalizeUrl(helperUrlInput.ifBlank { settings.helperUrl() })
        return HelperApiClient(url) { settings.apiKey() }
    }

    private fun validateHelperSettings() {
        HelperSettingsValidator.normalizeUrl(settings.helperUrl())
        if (settings.apiKey().isBlank()) throw IllegalStateException("Set Railway helper API key first.")
    }

    private fun evaluateCore(expression: String, callback: (JSONObject) -> Unit) {
        evaluateJson(expression) { callback(JSONObject(it)) }
    }

    private fun evaluateJson(expression: String, callback: (String) -> Unit) {
        val core = assets.open("nimsReportCore.js").bufferedReader().use { it.readText() }
        webView.evaluateJavascript("$core\n(function(){ return $expression; })();") { value ->
            callback(decodeJsString(value))
        }
    }

    private fun decodeJsString(value: String): String = JSONArray("[$value]").getString(0)
    private fun safeJson(json: JSONObject): String = json.toString(2)

    private fun isParsedReportValid(report: JSONObject): Boolean {
        val parameters = report.optJSONArray("parameters")?.length() ?: 0
        val cultures = report.optJSONArray("culture_results")?.length() ?: 0
        val culture = report.optJSONObject("culture")
        return parameters > 0 || cultures > 0 || (culture != null && culture.length() > 0)
    }

    private fun errorReport(row: JSONObject, error: String): JSONObject {
        return JSONObject()
            .put("report_name", row.optString("report_name"))
            .put("date_sent", row.optString("date_sent"))
            .put("report_type", row.optString("report_type", "other"))
            .put("report_tags", row.optJSONArray("report_tags") ?: JSONArray().put("other"))
            .put("parameters", JSONArray())
            .put("errors", JSONArray().put(error))
    }

    private fun safeReportKey(transientArg: String, row: JSONObject): String {
        val input = listOf(
            transientArg,
            row.optString("date_sent"),
            row.optString("report_name"),
            row.optString("department")
        ).joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "report_key:$hash"
    }

    private fun loadPersistedSummary() {
        val saved = settings.lastSummaryJson()
        sanitizedSummaryText = saved
        if (saved.isNotBlank()) {
            runCatching { uiSummary = SummaryJsonMapper.parseSummaryJsonToUiSummary(JSONObject(saved), physicianNote) }
        }
    }

    private fun updatePhysicianNote(value: String) {
        physicianNote = value
        settings.savePhysicianNote(value)
        uiSummary = uiSummary?.copy(editableNote = value)
    }

    private fun cleanSummaryText(): String {
        return ClinicalSummaryFormatter.cleanText((uiSummary ?: UiSummary()).copy(editableNote = physicianNote))
    }

    private fun copyCleanSummary() {
        copyText("NIMS Fast Summary", cleanSummaryText())
    }

    private fun copyText(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        log("Copied: $label")
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

    private fun clearResults() {
        settings.clearResults()
        sanitizedSummaryText = ""
        uiSummary = null
        physicianNote = ""
        setState(AppState.HELPER_READY, "Results cleared.")
    }

    private fun log(message: String) {
        runOnUiThread {
            logText = (logText + "\n" + message).trim().takeLast(8000)
        }
    }

    private fun setState(state: AppState, message: String) {
        runOnUiThread {
            appStateValue = state
            statusMessage = message
            logText = (logText + "\n${state.name}: $message").trim().takeLast(8000)
        }
    }

    companion object {
        private const val NIMS_LOGIN_URL = "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action"
        private const val MAX_REPORT_BYTES = 25 * 1024 * 1024
    }
}

@Composable
private fun NimsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF0F4C81),
            secondary = Color(0xFF006B5F),
            tertiary = Color(0xFF7A4D00),
            error = Color(0xFFB3261E)
        ),
        content = content
    )
}

@Composable
private fun NimsFastSummaryApp(
    webView: WebView,
    state: AppState,
    statusMessage: String,
    currentPage: String,
    loadProgress: Int,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    helperUrl: String,
    helperKey: String,
    onHelperUrlChange: (String) -> Unit,
    onHelperKeyChange: (String) -> Unit,
    showSettings: Boolean,
    onShowSettings: () -> Unit,
    onDismissSettings: () -> Unit,
    onSaveHelper: () -> Unit,
    onTestHelper: () -> Unit,
    onClearHelper: () -> Unit,
    onNimsLogin: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onDiagnose: () -> Unit,
    onDiscover: () -> Unit,
    onTestOne: () -> Unit,
    onFast: () -> Unit,
    onCulturesOnly: () -> Unit,
    onFull: () -> Unit,
    summary: UiSummary?,
    physicianNote: String,
    onPhysicianNoteChange: (String) -> Unit,
    logText: String,
    onCopySummary: () -> Unit,
    onCopyJson: () -> Unit,
    onExportText: () -> Unit,
    onClearResults: () -> Unit
) {
    Scaffold(
        topBar = {
            AppHeader(state, statusMessage, currentPage, loadProgress, onShowSettings)
        },
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
        val contentModifier = Modifier
            .fillMaxSize()
            .padding(padding)
        when (selectedTab) {
            0 -> NimsWebViewScreen(
                modifier = contentModifier,
                webView = webView,
                state = state,
                onNimsLogin = onNimsLogin,
                onBack = onBack,
                onForward = onForward,
                onReload = onReload,
                onZoomIn = onZoomIn,
                onZoomOut = onZoomOut,
                onDiagnose = onDiagnose,
                onDiscover = onDiscover,
                onTestOne = onTestOne,
                onFast = onFast,
                onCulturesOnly = onCulturesOnly,
                onFull = onFull,
                logText = logText
            )
            1 -> ReportsScreen(contentModifier, summary?.sourceReports.orEmpty())
            2 -> TrendsScreen(contentModifier, summary?.labTrends.orEmpty())
            3 -> CulturesScreen(contentModifier, summary?.cultures.orEmpty())
            else -> SummaryScreen(
                modifier = contentModifier,
                summary = summary,
                physicianNote = physicianNote,
                onPhysicianNoteChange = onPhysicianNoteChange,
                onCopySummary = onCopySummary,
                onCopyJson = onCopyJson,
                onExportText = onExportText,
                onClearResults = onClearResults
            )
        }
    }
    if (showSettings) {
        SettingsDialog(
            helperUrl = helperUrl,
            helperKey = helperKey,
            onHelperUrlChange = onHelperUrlChange,
            onHelperKeyChange = onHelperKeyChange,
            onSave = onSaveHelper,
            onTest = onTestHelper,
            onClear = onClearHelper,
            onDismiss = onDismissSettings
        )
    }
}

@Composable
private fun AppHeader(state: AppState, message: String, currentPage: String, progress: Int, onSettings: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(14.dp, 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("NIMS Fast Summary", color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(currentPage, color = Color(0xFFD7E8FF), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onSettings) { Text("Settings", color = Color.White) }
        }
        Spacer(Modifier.height(6.dp))
        Text("${state.name}: $message", color = Color.White, style = MaterialTheme.typography.bodySmall)
        Text(if (progress >= 100) "Loaded" else "Loading $progress%", color = Color(0xFFD7E8FF), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun NimsWebViewScreen(
    modifier: Modifier,
    webView: WebView,
    state: AppState,
    onNimsLogin: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onDiagnose: () -> Unit,
    onDiscover: () -> Unit,
    onTestOne: () -> Unit,
    onFast: () -> Unit,
    onCulturesOnly: () -> Unit,
    onFull: () -> Unit,
    logText: String
) {
    Column(modifier) {
        StatusCard(state)
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedButton(onClick = onBack) { Text("Back") } }
            item { OutlinedButton(onClick = onForward) { Text("Forward") } }
            item { OutlinedButton(onClick = onReload) { Text("Reload") } }
            item { OutlinedButton(onClick = onNimsLogin) { Text("NIMS Login") } }
            item { OutlinedButton(onClick = onZoomOut) { Text("Zoom -") } }
            item { OutlinedButton(onClick = onZoomIn) { Text("Zoom +") } }
            item { Button(onClick = onDiagnose) { Text("Diagnose") } }
            item { Button(onClick = onDiscover) { Text("Discover") } }
            item { Button(onClick = onTestOne) { Text("Test One") } }
            item { Button(onClick = onFast) { Text("Fast") } }
            item { Button(onClick = onCulturesOnly) { Text("Cultures") } }
            item { Button(onClick = onFull) { Text("Full") } }
        }
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
        if (logText.isNotBlank()) {
            Text(
                logText.takeLast(1200),
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(Color(0xFFF7F9FC))
                    .padding(8.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ReportsScreen(modifier: Modifier, reports: List<UiSourceReport>) {
    LazyColumn(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("Source reports", "${reports.size} reports") }
        if (reports.isEmpty()) item { EmptyCard("No reports parsed yet.") }
        items(reports) { report ->
            ResultCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(report.reportName, fontWeight = FontWeight.Bold)
                        Text(report.dateSent.ifBlank { "No date" }, style = MaterialTheme.typography.bodySmall)
                    }
                    Badge(report.type.uppercase())
                    Spacer(Modifier.width(6.dp))
                    Badge(report.status, if (report.hasError) Color(0xFFFFE2E0) else Color(0xFFE6F4EA))
                }
                if (report.notes.isNotBlank()) Text(report.notes, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TrendsScreen(modifier: Modifier, rows: List<UiLabTrendRow>) {
    LazyColumn(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("Lab trends", "${rows.size} parameters") }
        if (rows.isEmpty()) item { EmptyCard("Run Fast Summary to view lab trends.") }
        items(rows) { row ->
            ResultCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.parameter, fontWeight = FontWeight.Bold)
                        Text(row.latestDate.ifBlank { "No date" }, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(row.latestValue.ifBlank { "-" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Badge(row.trendText)
                    Badge(row.abnormality.name, abnormalityColor(row.abnormality))
                    if (!row.previousValue.isNullOrBlank()) Badge("Prev ${row.previousValue}")
                }
                Text(row.history.take(5).joinToString(" | ") { "${it.first}: ${it.second}" }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CulturesScreen(modifier: Modifier, rows: List<UiCultureRow>) {
    LazyColumn(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("Cultures", "${rows.size} rows") }
        if (rows.isEmpty()) item { EmptyCard("No culture data parsed yet.") }
        items(rows) { row ->
            ResultCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.organism.ifBlank { row.status.ifBlank { "Culture" } }, fontWeight = FontWeight.Bold)
                        Text(row.collectionDate.ifBlank { "No date" }, style = MaterialTheme.typography.bodySmall)
                    }
                    Badge(row.status, if (row.status.contains("positive", true)) Color(0xFFFFE8CC) else Color(0xFFE6F4EA))
                }
                Text(row.site.ifBlank { row.specimen }.ifBlank { "Site/specimen not parsed" })
                if (row.sensitivitySummary.isNotBlank()) Text(row.sensitivitySummary, style = MaterialTheme.typography.bodySmall)
                if (row.comment.isNotBlank()) Text(row.comment, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SummaryScreen(
    modifier: Modifier,
    summary: UiSummary?,
    physicianNote: String,
    onPhysicianNoteChange: (String) -> Unit,
    onCopySummary: () -> Unit,
    onCopyJson: () -> Unit,
    onExportText: () -> Unit,
    onClearResults: () -> Unit
) {
    LazyColumn(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Clinical summary", summary?.dateRange ?: "No summary") }
        item {
            ResultCard {
                Text("Snapshot", fontWeight = FontWeight.Bold)
                Text("Reports: ${summary?.sourceReports?.size ?: 0}")
                Text("Failed: ${summary?.failedReportCount ?: 0}")
                Text("Cultures: ${summary?.cultures?.size ?: 0}")
            }
        }
        item {
            ResultCard {
                Text("Interpretation", fontWeight = FontWeight.Bold)
                val bullets = summary?.interpretation.orEmpty()
                if (bullets.isEmpty()) Text("No interpretation available")
                bullets.take(8).forEach { Text("- $it") }
            }
        }
        item {
            OutlinedTextField(
                value = physicianNote,
                onValueChange = onPhysicianNoteChange,
                label = { Text("Editable physician note") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Button(onClick = onCopySummary) { Text("Copy summary") } }
                item { OutlinedButton(onClick = onExportText) { Text("Share text") } }
                item { OutlinedButton(onClick = onCopyJson) { Text("Copy JSON") } }
                item { OutlinedButton(onClick = onClearResults) { Text("Clear results") } }
            }
        }
        item {
            Text(
                "Auto-parsed summary. Verify with source NIMS reports before clinical decisions.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SettingsDialog(
    helperUrl: String,
    helperKey: String,
    onHelperUrlChange: (String) -> Unit,
    onHelperKeyChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button(onClick = onSave) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Settings and security") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(helperUrl, onHelperUrlChange, label = { Text("Railway helper URL") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(helperKey, onHelperKeyChange, label = { Text(if (helperKey.isBlank()) "API key" else "API key entered") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onTest) { Text("Test helper") }
                    OutlinedButton(onClick = onClear) { Text("Clear") }
                }
                Text("NIMS login is manual. NIMS credentials are not stored. NIMS cookies stay on this phone. Railway receives report content for parsing only.")
            }
        }
    )
}

@Composable
private fun StatusCard(state: AppState) {
    ResultCard {
        Text("Next action", fontWeight = FontWeight.Bold)
        Text(
            when (state) {
                AppState.NEED_HELPER_SETTINGS -> "Add Railway helper URL and API key."
                AppState.HELPER_READY -> "Login to NIMS manually."
                AppState.NIMS_LOGIN -> "Open the report page after login."
                AppState.REPORT_PAGE_READY -> "Report list detected. Discover mapping."
                AppState.MAPPING_DISCOVERED -> "Mapping ready. Run Test One Report."
                AppState.FETCHING -> "Fetching and parsing reports..."
                AppState.SUMMARY_READY -> "Summary ready."
                AppState.ERROR -> "Review error and retry the relevant step."
            }
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
    }
}

@Composable
private fun ResultCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            content()
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    ResultCard { Text(text, color = Color.DarkGray) }
}

@Composable
private fun Badge(text: String, color: Color = Color(0xFFE8EEF7)) {
    AssistChip(onClick = {}, label = { Text(text.ifBlank { "unknown" }) }, modifier = Modifier.fillMaxHeight(), leadingIcon = null)
}

private fun abnormalityColor(value: Abnormality): Color {
    return when (value) {
        Abnormality.HIGH -> Color(0xFFFFE8CC)
        Abnormality.LOW -> Color(0xFFE4E7FF)
        Abnormality.CRITICAL -> Color(0xFFFFDAD6)
        Abnormality.NORMAL -> Color(0xFFE6F4EA)
        Abnormality.UNKNOWN -> Color(0xFFE8EEF7)
    }
}

data class ReportFetchResult(
    val contentType: String,
    val statusCode: Int,
    val finalUrlSafe: String,
    val bytes: ByteArray
)

data class PreparedReportRequest(
    val row: JSONObject,
    val transientArg: String,
    val directUrl: String
)
