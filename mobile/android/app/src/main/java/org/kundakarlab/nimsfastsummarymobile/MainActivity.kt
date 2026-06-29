package org.kundakarlab.nimsfastsummarymobile

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebStorage
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
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
import org.kundakarlab.nimsfastsummarymobile.domain.processing.ProcessingRouter
import org.kundakarlab.nimsfastsummarymobile.domain.processing.ProcessingResult
import org.kundakarlab.nimsfastsummarymobile.domain.model.SummaryMode
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedReport
import org.kundakarlab.nimsfastsummarymobile.domain.model.ReportInput
import org.kundakarlab.nimsfastsummarymobile.data.processing.RemoteReportProcessor
import org.kundakarlab.nimsfastsummarymobile.data.processing.LocalTextReportProcessor
import org.kundakarlab.nimsfastsummarymobile.data.processing.OnDeviceReportProcessor
import org.kundakarlab.nimsfastsummarymobile.data.pdf.PdfBoxAndroidTextExtractor
import kotlinx.coroutines.withContext
import org.kundakarlab.nimsfastsummarymobile.security.SafeLogBuffer
import org.kundakarlab.nimsfastsummarymobile.security.NimsUrlPolicy
import org.kundakarlab.nimsfastsummarymobile.security.UrlClassification
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.lifecycle.lifecycleScope
import org.json.JSONArray
import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.ui.formatters.ClinicalSummaryFormatter
import org.kundakarlab.nimsfastsummarymobile.ui.mappers.SummaryJsonMapper
import org.kundakarlab.nimsfastsummarymobile.domain.model.ProcessingMode
import org.kundakarlab.nimsfastsummarymobile.ui.models.Abnormality
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiCultureRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiLabTrendRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSourceReport
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var settings: SecureSettings
    private var mapping: ReportTemplate? = null
    private var mappingValidated = false
    // Token identity for runModeInternal's evaluateJavascript watchdog.
    private var activeEvaluateWatchdog: Any? = null
    private var webViewUserAgent = ""

    private var appStateValue by mutableStateOf(AppState.HELPER_READY)
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
    private var processingMode by mutableStateOf(ProcessingMode.LOCAL_ONLY)
    private var activeProcessingJob: Job? = null
    private var navigationJob: Job? = null
    private var navigationGeneration = 0L
    private var navigationInProgress by mutableStateOf(false)
    private val safeLogBuffer = SafeLogBuffer()
    private val processingRouter by lazy {
        ProcessingRouter(
            local = OnDeviceReportProcessor(
                textProcessor = LocalTextReportProcessor(),
                pdfExtractor = PdfBoxAndroidTextExtractor(applicationContext),
                onPdfProgress = { completed, total -> runOnUiThread { setState(AppState.FETCHING, "Extracting PDF page $completed of $total...") } }
            ),
            remote = RemoteReportProcessor { helper() },
            modeProvider = { processingMode },
            remoteConfigured = { settings.helperUrl().isNotBlank() && settings.hasApiKey() }
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SecureSettings(this)
        helperUrlInput = settings.helperUrl()
        physicianNote = settings.physicianNote()
        processingMode = settings.processingMode()
        loadPersistedSummary()
        CookieManager.getInstance().setAcceptCookie(true)
        webView = createWebView()
        clearWebViewSession(coldStartOnly = true) { webView.loadUrl(NIMS_LOGIN_URL) }
        webViewUserAgent = webView.settings.userAgentString
        val initial = InitialStatePolicy.derive(processingMode, settings.helperUrl().isNotBlank(), settings.hasApiKey())
        setState(initial.state, initial.message)
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
                    processingMode = processingMode,
                    onProcessingModeChange = { updateProcessingMode(it) },
                    onNimsLogin = { webView.loadUrl(NIMS_LOGIN_URL) },
                    onClearNimsSession = { clearNimsSession() },
                    onBack = { if (webView.canGoBack()) webView.goBack() },
                    onForward = { if (webView.canGoForward()) webView.goForward() },
                    onReload = { webView.reload() },
                    onZoomIn = { webView.zoomIn() },
                    onZoomOut = { webView.zoomOut() },
                    onOpenCrSearchDirect = { openCrSearchDirect() },
                    onCopyFullLog = { copyFullLog() },
                    navigationInProgress = navigationInProgress,
                    onDiagnose = { diagnosePage() },
                    onDiscover = { discoverMapping() },
                    onTestOne = { runMode("test_direct") },
                    onFast = { runMode("bulk_fast") },
                    onCulturesOnly = { runMode("bulk_cultures_only") },
                    onFull = { runMode("bulk_full") },
                    onCancelProcessing = { cancelActiveProcessing() },
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
        webView.requestFocus()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        // Let a desktop Chrome (chrome://inspect over USB) attach to this WebView,
        // and let the WebView report its own console/network errors (wired below).
        runCatching { WebView.setWebContentsDebuggingEnabled(true) }
        val coreJs = runCatching { assets.open("nimsReportCore.js").bufferedReader().use { it.readText() } }.getOrNull()
        val utilsJs = runCatching { assets.open("contentUtils.js").bufferedReader().use { it.readText() } }.getOrNull()
        val bridgeJs = runCatching { assets.open("nimsAndroidFrameBridge.js").bufferedReader().use { it.readText() } }.getOrNull()
        // Runtime compatibility shim: neutralizes NIMS's confirmed crashes
        // (missing date_time global, and the $("#menuStrip").offset().left throw
        // in tabmenu.js) so the menu/content render isn't aborted in the WebView.
        val shimJs = runCatching { assets.open("nimsWebviewShim.js").bufferedReader().use { it.readText() } }.getOrNull()
        return WebView(this).apply {
            settings.javaScriptEnabled = true
            // Identify as the desktop Chrome the extension actually works in.
            // NIMS serves different (and partly broken/404) asset paths to a
            // "mobile" UA, which makes tabmenu.js crash and the content frame
            // stay blank. This UA is also reused for the Kotlin report fetch
            // (captured into webViewUserAgent right after createWebView()).
            settings.userAgentString = DESKTOP_CHROME_UA
            settings.domStorageEnabled = true
            settings.databaseEnabled = false
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
            // Real Chrome (which the extension relies on) allows passive mixed
            // content and blocks only active mixed content. NEVER_ALLOW is stricter
            // than Chrome and can blank a frame that pulls any http subresource.
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            isFocusable = true
            isFocusableInTouchMode = true
            setInitialScale(85)
            // NIMS frames/redirects can be treated as third-party inside a WebView;
            // with these blocked the session cookie is withheld and the content
            // frame comes back empty (shell renders, body blank). Chrome allows them.
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    loadProgress = newProgress
                }

                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    val level = consoleMessage.messageLevel()?.name ?: "LOG"
                    if (level == "ERROR" || level == "WARNING") {
                        val src = consoleMessage.sourceId()?.substringAfterLast('/').orEmpty()
                        val msg = consoleMessage.message().orEmpty().take(220)
                        log("JS $level: $msg ($src:${consoleMessage.lineNumber()})")
                    }
                    return true
                }

                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    val handled = AtomicBoolean(false)
                    fun cleanupPopup(popupView: WebView) {
                        runCatching { popupView.stopLoading() }
                        popupView.webChromeClient = null
                        // Do not assign null to popupView.webViewClient; Android's Kotlin API exposes it as non-null.
                        // The AtomicBoolean one-shot guard prevents duplicate popup handling.
                        popupView.post { runCatching { popupView.destroy() } }
                    }
                    fun handlePopupUrlOnce(popupView: WebView, uri: Uri, isMainFrame: Boolean): Boolean {
                        if (!handled.compareAndSet(false, true)) return true
                        log("Popup -> ${SafeUrl.stripQuery(uri.toString())}")
                        when (NimsUrlPolicy.classify(uri)) {
                            UrlClassification.ALLOWED_NIMS -> webView.loadUrl(uri.toString())
                            UrlClassification.EXTERNAL_HTTPS -> if (isMainFrame && isUserGesture) {
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                            }
                            UrlClassification.BLOCKED_NIMS,
                            UrlClassification.BLOCKED_SCHEME,
                            UrlClassification.BLOCKED_UNSAFE -> log("Blocked popup navigation")
                        }
                        cleanupPopup(popupView)
                        return true
                    }
                    val popup = WebView(view.context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(popupView: WebView, request: WebResourceRequest): Boolean =
                                handlePopupUrlOnce(popupView, request.url, request.isForMainFrame)

                            override fun onPageFinished(popupView: WebView, url: String) {
                                if (url.isNotBlank() && url != "about:blank") handlePopupUrlOnce(popupView, Uri.parse(url), true)
                            }
                        }
                    }
                    val transport = resultMsg.obj as WebView.WebViewTransport
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
            }
            webViewClient = NimsWebViewClient(
                onPageChanged = { safeUrl ->
                    currentPage = safeUrl.ifBlank { "NIMS" }
                    // Navigating away from the CR-wise report list (e.g. a
                    // session-expired redirect to the NIMS login page, or back/
                    // forward navigation) used to leave mapping/mappingValidated
                    // pointing at the OLD page's now-dead template and tokens. A
                    // subsequent Test One/Fast tap would then try to fetch with
                    // state from a page that's no longer there, which looks like
                    // a generic failure rather than what it actually is. Clear it
                    // whenever the WebView leaves the known report-list path, so
                    // the next action correctly asks to re-discover.
                    if (!safeUrl.contains("viewcrnowisereportprocess.cnt", ignoreCase = true) &&
                        !safeUrl.contains("invresultreportprintingcrnowise.cnt", ignoreCase = true)
                    ) {
                        if (mapping != null || mappingValidated) {
                            log("Left the CR result list ($safeUrl); clearing stale mapping state")
                        }
                        mapping = null
                        mappingValidated = false
                    }
                },
                onBlockedInternalNavigation = { setState(AppState.ERROR, "Blocked internal NIMS navigation.") },
                onResourceError = { detail -> log(detail) }
            )
            // All-frames bridge (mirrors the extension's all_frames model). The
            // top frame cannot read a cross-origin result iframe, so inject the
            // core + bridge into every frame and let the frame that owns the rows
            // post them back via nimsAndroidBridge. Feature-gated; if unsupported,
            // the existing same-origin top-frame path still runs.
            val webMessageSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
            log("DIAG: WEB_MESSAGE_LISTENER supported=$webMessageSupported")
            if (webMessageSupported) {
                val listenerResult = runCatching {
                    WebViewCompat.addWebMessageListener(this, "nimsAndroidBridge", setOf("*")) { _, message, _, _, _ ->
                        message.data?.let { data -> post { onFrameReport(data) } }
                    }
                }
                log("DIAG: addWebMessageListener installed=${listenerResult.isSuccess} error=${listenerResult.exceptionOrNull()?.message}")
            }
            val documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
            log("DIAG: DOCUMENT_START_SCRIPT supported=$documentStartSupported")
            if (documentStartSupported) {
                // The shim is the render fix and must run first, before NIMS's
                // own scripts, and even if the reader assets failed to load.
                val readerJs = if (coreJs != null && utilsJs != null && bridgeJs != null) {
                    "\n$coreJs\n$utilsJs\n$bridgeJs"
                } else {
                    ""
                }
                log("DIAG: asset load coreJs=${coreJs != null} utilsJs=${utilsJs != null} bridgeJs=${bridgeJs != null} shimJs=${shimJs != null}")
                val payload = (shimJs ?: "") + readerJs
                if (payload.isNotBlank()) {
                    val injectResult = runCatching {
                        val injected = "try{\nwindow.__nimsInjectedAt=Date.now();\n$payload\n}catch(e){if(window.console&&console.error)console.error('NIMS inject failed',e);}"
                        WebViewCompat.addDocumentStartJavaScript(this, injected, setOf("*"))
                    }
                    log("DIAG: addDocumentStartJavaScript installed=${injectResult.isSuccess} error=${injectResult.exceptionOrNull()?.message}")
                } else {
                    log("DIAG: injection payload was BLANK (no shim/core text available)")
                }
            } else {
                log("DIAG: DOCUMENT_START_SCRIPT NOT supported on this WebView provider — shim/reader never run")
            }
        }
    }

    private fun saveHelperSettings() {
        runCatching {
            val helperUrlProvided = helperUrlInput.isNotBlank()
            val helperKeyProvided = helperKeyInput.isNotBlank()
            if (processingMode == ProcessingMode.REMOTE_ONLY || helperUrlProvided || helperKeyProvided) {
                settings.saveHelperUrl(helperUrlInput)
                if (helperKeyInput.isBlank() && !settings.hasApiKey()) throw IllegalArgumentException("Configure Railway helper URL and API key.")
                settings.saveApiKey(helperKeyInput)
            }
        }.onFailure {
            setState(AppState.ERROR, it.message ?: "Helper settings invalid")
            return
        }
        helperKeyInput = ""
        showSettings = false
        setState(AppState.HELPER_READY, "Settings saved. Login to NIMS manually.")
    }

    private fun clearHelperSettings() {
        settings.clearHelperSettings()
        helperUrlInput = ""
        helperKeyInput = ""
        val initial = InitialStatePolicy.derive(processingMode, hasHelperUrl = false, hasApiKey = false)
        setState(initial.state, initial.message)
    }

    private fun clearNimsSession() {
        cancelActiveProcessing()
        cancelNavigation()
        mapping = null
        mappingValidated = false
        clearWebViewSession(coldStartOnly = false) {
            webView.loadUrl(NIMS_LOGIN_URL)
            setState(AppState.HELPER_READY, "NIMS session cleared. Login manually.")
        }
    }

    private fun clearWebViewSession(coldStartOnly: Boolean, onComplete: () -> Unit = {}) {
        if (coldStartOnly && webViewSessionCleaned) {
            onComplete()
            return
        }
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()
            webViewSessionCleaned = true
            runOnUiThread { onComplete() }
        }
    }

    private fun testHelper() {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val health = publicHelper().health()
                val version = publicHelper().version()
                "Helper ok: version=${version.optString("version")} remote_mode=${health.optBoolean("remote_mode")} cache_enabled=${health.optBoolean("cache_enabled")} api_key_configured=${health.optBoolean("api_key_configured")}"
            }
                .onSuccess { setState(AppState.HELPER_READY, it) }
                .onFailure { setState(AppState.ERROR, "Helper connection failed: ${it.message}") }
        }
    }


    // Reach the CR-wise result page WITHOUT the EasyUI tab that blanks in the
    // WebView. Prefer the page-faithful ticketed top-level nav; if the menu
    // anchor is not present, load the leaf endpoint directly. Either way the
    // page renders top-level and the existing reader picks up the rows.
    private fun openCrSearchDirect() {
        cancelNavigation()
        mapping = null
        mappingValidated = false
        setState(AppState.HELPER_READY, "Opening CR-wise result page directly…")
        evaluateCore("JSON.stringify(NimsReportCore.openCrWiseResultsDirect(document))") { result ->
            when {
                result.optBoolean("ok") -> {
                    val action = result.optString("action")
                    setState(AppState.HELPER_READY, "CR-wise page opening ($action). Enter the CR number, then tap Go.")
                }
                else -> {
                    log("Direct CR menu not found (${result.optString("errorCode")}); loading leaf endpoint")
                    webView.loadUrl(CR_SEARCH_URL)
                    setState(AppState.HELPER_READY, "Loading CR-wise result page… If a login screen appears, sign in and retry.")
                }
            }
        }
    }

    private fun cancelNavigation() {
        navigationGeneration += 1
        navigationJob?.cancel()
        navigationJob = null
        navigationInProgress = false
    }

    private fun diagnosePage() {
        evaluateCore("JSON.stringify(NimsReportCore.diagnosePage(document))") { json ->
            val rows = DiagnosePageContract.viewReportRows(json)
            val blocked = DiagnosePageContract.blockedFrames(json)
            val reachable = DiagnosePageContract.reachableDocuments(json)
            if (rows > 0 && appStateValue.ordinal < AppState.REPORT_PAGE_READY.ordinal) {
                setState(AppState.REPORT_PAGE_READY, "Report list detected. Discover mapping.")
            } else if (rows == 0 && blocked > 0) {
                setState(AppState.ERROR, "Report rows are inside a different-origin frame this app cannot read from the top frame ($blocked blocked, $reachable reachable). This needs the all-frames build, not a navigation retry.")
            }
            log("Page diagnostics rows=$rows reachable=$reachable blockedFrames=$blocked mappingReady=${rows > 0}")
        }
        probeFrameRendering()
    }

    // Diagnostic-only: no clicks, no navigation, no form submission. Reports,
    // per reachable frame, whether script injection actually ran in that
    // window (window.__nimsInjectedAt), whether the body has any content,
    // whether that content is visible, and the full uncaught-error text for
    // that window (untruncated, unlike the 220-char console callback).
    private fun probeFrameRendering() {
        evaluateCore("JSON.stringify(NimsReportCore.frameRenderProbe(document))") { json ->
            val frames = json.optJSONArray("frames") ?: JSONArray()
            log("PROBE: ${frames.length()} frame(s) reachable from top")
            for (i in 0 until frames.length()) {
                val f = frames.optJSONObject(i) ?: continue
                val err = f.optJSONObject("lastUncaughtError")
                val errText = if (err != null) " ERROR=\"${err.optString("message")}\" @${err.optString("source").substringAfterLast('/')}:${err.optInt("line")}" else ""
                log(
                    "PROBE[${f.optInt("depth")}] id=${f.optString("frameId").ifBlank { "?" }} " +
                        "url=${f.optString("url")} ready=${f.optString("readyState")} " +
                        "visible=${f.optBoolean("visibleThroughAncestors")}/${f.optBoolean("elementVisible")} " +
                        "children=${f.optInt("bodyChildCount")} textLen=${f.optInt("bodyTextLength")} " +
                        "injected=${f.optBoolean("injectionRan")}$errText"
                )
                val sample = f.optString("bodyTextSample")
                if (sample.isNotBlank()) log("PROBE[${f.optInt("depth")}] sample: ${sample.take(120)}")
            }
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
                    // Always close the popup discoverSetPdfTemplate's click just opened,
                    // success or failure, before anything else touches the WebView.
                    evaluateCore("JSON.stringify(NimsReportCore.closeReportPopup(document))") { closeResult ->
                        log("Closed report-discovery popup: ${closeResult.optString("action")}")
                    }
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

    // SIMPLIFIED: nimsAndroidFrameBridge.js still runs and posts messages (it
    // remains useful diagnostic signal, and removing the JS layer is a larger,
    // separate change), but the Kotlin side no longer ACTS on nims_report_frame
    // announcements -- no crossFrameReport state, no auto REPORT_PAGE_READY,
    // no normalizer call (FrameReportNormalizer.kt was removed along with the
    // rest of the dual-path architecture it only served). That decision-making
    // moved entirely to runMode/discoverMapping/runModeInternal reading the
    // top document directly (see runMode's comment for why). This function is
    // now pure logging, so a flaky bridge poll can never again silently steer
    // which code path a button tap takes.
    private fun onFrameReport(data: String) {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return
        when (json.optString("type")) {
            "nims_frame_debug" -> {
                val errs = json.optJSONArray("errors")
                val errStr = if (errs != null && errs.length() > 0) {
                    " err=" + (0 until errs.length()).joinToString("; ") { errs.optString(it) }
                } else ""
                log(
                    "FRAME ${json.optString("url")}: children=${json.optInt("children")} " +
                        "text=${json.optInt("textLen")} h=${json.optInt("height")}$errStr"
                )
            }
            "nims_report_frame" -> {
                val rowCount = json.optJSONArray("rows")?.length() ?: 0
                log("Frame bridge (diagnostic only): rows=$rowCount from=${json.optString("href")}")
            }
        }
    }

    // SIMPLIFIED (single path): now that "Open CR Results" always navigates the
    // CR-wise list to the TOP-LEVEL document (no EasyUI tab, no iframe), there
    // is no cross-origin boundary left to cross, and the old cross-frame-
    // bridge path (which raced this one, selected by whichever happened to
    // have fresher data) has been removed entirely. One reader
    // (rowsFromBestFrame(document)), one template (discoverMapping), one
    // validation flag (mappingValidated).
    private fun runMode(mode: String) {
        val bulkModes = setOf("bulk_fast", "bulk_cultures_only", "bulk_full")
        // Already discovered and (for bulk modes) already validated with one
        // report: run directly, no re-discovery, no re-validation.
        if (mapping != null && (mode !in bulkModes || mappingValidated)) {
            runModeInternal(mode)
            return
        }
        if (navigationInProgress) {
            setState(AppState.HELPER_READY, "Analysis startup is already running.")
            return
        }
        cancelNavigation()
        cancelActiveProcessing()
        mapping = null
        mappingValidated = false
        navigationInProgress = true
        setState(AppState.HELPER_READY, "Checking the visible NIMS report-result list…")

        discoverMapping()
        navigationJob = lifecycleScope.launch {
            try {
                var checks = 0
                while (mapping == null && checks < 30) {
                    delay(500)
                    checks += 1
                }
                if (mapping == null) {
                    setState(AppState.ERROR, "No usable visible report rows were found. Navigate manually to the submitted CR report list and retry.")
                    return@launch
                }

                if (mode !in bulkModes) {
                    setState(AppState.FETCHING, "Running Test One Report…")
                    runModeInternal(mode)
                    return@launch
                }

                setState(AppState.FETCHING, "Testing one visible report before bulk analysis…")
                runModeInternal("test_direct")
                checks = 0
                while (!mappingValidated && checks < 90) {
                    delay(500)
                    checks += 1
                }
                if (!mappingValidated) {
                    setState(AppState.ERROR, "One-report validation did not succeed. Keep the result list visible and retry.")
                    return@launch
                }

                setState(AppState.FETCHING, "Mapping validated. Starting analysis…")
                runModeInternal(mode)
            } finally {
                navigationInProgress = false
            }
        }
    }

    private fun runModeInternal(mode: String) {
        val currentMapping = mapping
        if (currentMapping == null) {
            setState(AppState.ERROR, "Mapping not discovered. Tap Discover after opening the report list.")
            return
        }
        if (mode != "test_direct" && !mappingValidated) {
            setState(AppState.ERROR, "Run Test One Report successfully before bulk summary.")
            return
        }
        // Watchdog (ported from the removed cross-frame-bridge path): a NIMS
        // popup/modal left open from an earlier template-discovery click can
        // occupy the WebView's render/script thread for a long time, and a
        // killed renderer process drops evaluateJavascript's callback
        // entirely. Without this, the screen can sit on stale "Next action"
        // text indefinitely with no error. Surface a clear, retryable error
        // instead of silence.
        val watchdogToken = Any()
        activeEvaluateWatchdog = watchdogToken
        val watchdogMs = if (mode == "test_direct") 20_000L else 60_000L
        Handler(Looper.getMainLooper()).postDelayed({
            if (activeEvaluateWatchdog === watchdogToken) {
                activeEvaluateWatchdog = null
                setState(AppState.ERROR, "Timed out waiting for the WebView to respond. A leftover NIMS popup or a slow page can cause this — close any open report popup, keep the result list visible, and retry.")
            }
        }, watchdogMs)
        evaluateJson("JSON.stringify(NimsReportCore.rowsFromBestFrame(document))") { rowsText ->
            val rows = JSONArray(rowsText)
            evaluateJson("JSON.stringify(NimsReportCore.selectRowsForMode(${rows}, '$mode'))") { selectedText ->
                if (activeEvaluateWatchdog === watchdogToken) activeEvaluateWatchdog = null
                val selectedAll = JSONArray(selectedText)
                val selected = if (mode == "test_direct" && selectedAll.length() > 1) {
                    JSONArray().apply { selectedAll.optJSONObject(0)?.let { put(it) } }
                } else {
                    selectedAll
                }
                log("Selected ${selected.length()} reports")
                prepareReportRequests(selected, currentMapping) { prepared ->
                    startFetchParseSummarize(mode, prepared)
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

    private fun startFetchParseSummarize(mode: String, prepared: List<PreparedReportRequest>) {
        if (activeProcessingJob?.isActive == true) {
            setState(AppState.ERROR, "A report processing run is already active.")
            return
        }
        val job = lifecycleScope.launch {
            fetchParseSummarize(mode, prepared)
        }
        activeProcessingJob = job
    }

    private suspend fun fetchParseSummarize(mode: String, prepared: List<PreparedReportRequest>) {
        setState(AppState.FETCHING, "Fetching and parsing reports...")
        try {
            val parsedReports = if (mode == "test_direct") {
                val request = prepared.firstOrNull() ?: run {
                    setState(AppState.ERROR, "No report selected for Test One Report.")
                    return
                }
                val result = withContext(Dispatchers.IO) { fetchAndParseOne(request, 0, 1) }
                val valid = result.labs.isNotEmpty() || result.cultures.isNotEmpty()
                mappingValidated = valid
                if (!valid) {
                    mappingValidated = false
                    setState(AppState.ERROR, result.warnings.firstOrNull() ?: "Test One Report did not parse a report.")
                    return
                }
                listOf(result)
            } else {
                processBulk(prepared).map { it.getOrElse { error -> errorParsedReport(JSONObject(), error.message ?: "Report failed") } }
            }
            val summaryMode = when (mode) {
                "bulk_full" -> SummaryMode.FULL
                "bulk_cultures_only" -> SummaryMode.CULTURES_ONLY
                else -> SummaryMode.FAST
            }
            log("Summarizing ${parsedReports.size}/${prepared.size}")
            when (val summaryResult = withContext(Dispatchers.IO) { processingRouter.summarize(parsedReports, summaryMode) }) {
                is ProcessingResult.Success -> {
                    val json = summaryResult.value.helperJson ?: localSummaryJson(parsedReports, summaryResult.value.text)
                    sanitizedSummaryText = json.toString()
                    settings.saveLastSummaryJson(sanitizedSummaryText)
                    uiSummary = SummaryJsonMapper.parseSummaryJsonToUiSummary(json, physicianNote)
                    selectedTab = 4
                    setState(AppState.SUMMARY_READY, "Summary ready.")
                }
                is ProcessingResult.Unsupported -> setState(AppState.ERROR, summaryResult.reason)
                is ProcessingResult.Failure -> setState(AppState.ERROR, summaryResult.userMessage)
            }
        } catch (cancelled: CancellationException) {
            setState(AppState.HELPER_READY, "Processing stopped. Completed results were retained.")
            throw cancelled
        } catch (error: Exception) {
            setState(AppState.ERROR, error.message ?: "Report processing failed")
        } finally {
            if (activeProcessingJob == coroutineContext[Job]) activeProcessingJob = null
        }
    }

    private suspend fun processBulk(prepared: List<PreparedReportRequest>): List<Result<ParsedReport>> = coroutineScope {
        val semaphore = Semaphore(2)
        prepared.mapIndexed { index, request ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    ensureActive()
                    try {
                        Result.success(fetchAndParseOne(request, index, prepared.size))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun fetchAndParseOne(request: PreparedReportRequest, index: Int, total: Int): ParsedReport {
        val row = request.row
        log("Fetching selected report ${index + 1}/$total")
        val transient = request.transientArg
        val url = request.directUrl
        val response = fetchWithWebViewCookies(url)
        val classification = ReportResponseClassifier.classify(response.statusCode, response.contentType, response.bytes)
        if (classification == "html_login_or_session") throw IllegalStateException("NIMS session appears expired. Login again in the WebView.")
        if (classification !in setOf("pdf_report", "html_report_content")) throw IllegalStateException("Report fetch returned $classification")
        val input = ReportInput(
            reportId = safeReportKey(transient, row),
            reportName = row.optString("report_name"),
            dateSent = row.optString("date_sent"),
            reportType = row.optString("report_type", "other"),
            contentType = contentType(response.contentType),
            bytes = response.bytes,
            safeSource = NimsUrlPolicy.safeSourceForHelper(url)
        )
        if (input.contentType.contains("pdf", true) || input.bytes.take(4).toByteArray().contentEquals("%PDF".toByteArray())) setState(AppState.FETCHING, "Extracting PDF text on-device…")
        log("Parsing report ${index + 1}/$total")
        return when (val parsed = processingRouter.parse(input)) {
            is ProcessingResult.Success -> parsed.value.copy(warnings = parsed.value.warnings + parsed.warnings)
            is ProcessingResult.Unsupported -> errorParsedReport(row, parsed.reason)
            is ProcessingResult.Failure -> errorParsedReport(row, parsed.userMessage)
        }
    }

    private fun errorParsedReport(row: JSONObject, error: String): ParsedReport {
        return ParsedReport(
            reportId = row.optString("report_id", "error"),
            reportName = row.optString("report_name"),
            dateSent = row.optString("date_sent"),
            reportType = row.optString("report_type", "other"),
            warnings = listOf(error),
            processorName = "none"
        )
    }

    private fun localSummaryJson(reports: List<ParsedReport>, text: String): JSONObject = JSONObject()
        .put("source_reports", JSONArray().also { array -> reports.forEach { report -> array.put(JSONObject().put("date_sent", report.dateSent).put("report_name", report.reportName).put("type", report.reportType).put("status", if (report.labs.isEmpty() && report.cultures.isEmpty()) "unsupported" else "parsed").put("notes", report.warnings.joinToString("; ")).put("action", if (report.labs.isEmpty() && report.cultures.isEmpty()) "Open source report in NIMS" else "")) } })
        .put("interpretation", JSONArray(text.lines()))
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
        try {
            val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_FETCHED_REPORT_BYTES) throw IllegalStateException("Report response exceeded 25 MB")
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            } ?: ByteArray(0)
            if (connection.responseCode >= 400) {
                throw IllegalStateException("NIMS report fetch returned ${connection.responseCode} (${contentType(connection.contentType.orEmpty())})")
            }
            return ReportFetchResult(connection.contentType.orEmpty(), connection.responseCode, SafeUrl.hostPath(connection.url.toString()), bytes)
        } finally {
            connection.disconnect()
        }
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
        if (processingMode == ProcessingMode.LOCAL_ONLY) return
        HelperSettingsValidator.normalizeUrl(settings.helperUrl())
        if (settings.apiKey().isBlank()) throw IllegalStateException("Configure Railway helper for PDF and unsupported reports.")
    }

    private fun evaluateCore(expression: String, callback: (JSONObject) -> Unit) {
        evaluateJson(expression) { raw ->
            val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("ok", false).put("errorCode", "navigation_js_decode_failed") }
            callback(json)
        }
    }

    private fun evaluateJson(expression: String, callback: (String) -> Unit) {
        val core = assets.open("nimsReportCore.js").bufferedReader().use { it.readText() }
        webView.evaluateJavascript("$core\n(function(){ try { return $expression; } catch (error) { return JSON.stringify({ ok: false, stage: 'unknown', action: 'none', done: false, errorCode: 'navigation_js_exception' }); } })();") { value ->
            callback(runCatching { decodeJsString(value) }.getOrDefault(""))
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

    private fun updateProcessingMode(value: ProcessingMode) {
        processingMode = value
        settings.saveProcessingMode(value)
        val initial = InitialStatePolicy.derive(value, settings.helperUrl().isNotBlank(), settings.hasApiKey())
        setState(initial.state, initial.message)
    }

    private fun cleanSummaryText(): String {
        return ClinicalSummaryFormatter.cleanText((uiSummary ?: UiSummary()).copy(editableNote = physicianNote))
    }

    private fun copyCleanSummary() {
        copyText("NIMS Fast Summary", cleanSummaryText())
    }

    // The on-screen log panel only shows the last 1200 chars for layout
    // reasons, which routinely cuts off exactly the part that matters (a JS
    // exception's message, which often comes at the END of a log line). This
    // copies the COMPLETE retained log buffer to the clipboard so it can be
    // pasted in full, instead of relying on a screenshot of a panel that may
    // be showing a truncated view of a truncated entry.
    private fun copyFullLog() {
        val text = safeLogBuffer.fullText()
        if (text.isBlank()) {
            log("Nothing to copy yet.")
            return
        }
        copyText("NIMS Fast Summary Mobile log", text)
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
        mapping = null
        mappingValidated = false
        setState(AppState.HELPER_READY, "Results cleared.")
    }

    private fun log(message: String) {
        runOnUiThread {
            logText = safeLogBuffer.add(message)
        }
    }

    private fun setState(state: AppState, message: String) {
        runOnUiThread {
            appStateValue = state
            statusMessage = message
            logText = safeLogBuffer.add("${state.name}: $message")
        }
    }

    fun cancelActiveProcessing() {
        activeProcessingJob?.cancel()
    }

    override fun onDestroy() {
        cancelNavigation()
        activeProcessingJob?.cancel()
        runCatching {
            if (::webView.isInitialized) {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.webChromeClient = null
                webView.webViewClient = WebViewClient()
                webView.removeAllViews()
                webView.destroy()
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val NIMS_LOGIN_URL = "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action"
        // Leaf endpoint for the CR-wise result page (rendered top-level, no EasyUI tab).
        private const val CR_SEARCH_URL = "https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt"
        // Match a current desktop Chrome so NIMS serves the same desktop assets
        // and code paths the working browser extension relies on.
        private const val DESKTOP_CHROME_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val MAX_FETCHED_REPORT_BYTES = 25 * 1024 * 1024
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
    processingMode: ProcessingMode,
    onProcessingModeChange: (ProcessingMode) -> Unit,
    onHelperUrlChange: (String) -> Unit,
    onHelperKeyChange: (String) -> Unit,
    showSettings: Boolean,
    onShowSettings: () -> Unit,
    onDismissSettings: () -> Unit,
    onSaveHelper: () -> Unit,
    onTestHelper: () -> Unit,
    onClearHelper: () -> Unit,
    onNimsLogin: () -> Unit,
    onClearNimsSession: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onOpenCrSearchDirect: () -> Unit,
    onCopyFullLog: () -> Unit,
    navigationInProgress: Boolean,
    onDiagnose: () -> Unit,
    onDiscover: () -> Unit,
    onTestOne: () -> Unit,
    onFast: () -> Unit,
    onCulturesOnly: () -> Unit,
    onFull: () -> Unit,
    onCancelProcessing: () -> Unit,
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
                onClearNimsSession = onClearNimsSession,
                onBack = onBack,
                onForward = onForward,
                onReload = onReload,
                onZoomIn = onZoomIn,
                onZoomOut = onZoomOut,
                onOpenCrSearchDirect = onOpenCrSearchDirect,
                onCopyFullLog = onCopyFullLog,
                navigationInProgress = navigationInProgress,
                onDiagnose = onDiagnose,
                onDiscover = onDiscover,
                onTestOne = onTestOne,
                onFast = onFast,
                onCulturesOnly = onCulturesOnly,
                onFull = onFull,
                onCancelProcessing = onCancelProcessing,
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
            processingMode = processingMode,
            onProcessingModeChange = onProcessingModeChange,
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
    onClearNimsSession: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onOpenCrSearchDirect: () -> Unit,
    onCopyFullLog: () -> Unit,
    navigationInProgress: Boolean,
    onDiagnose: () -> Unit,
    onDiscover: () -> Unit,
    onTestOne: () -> Unit,
    onFast: () -> Unit,
    onCulturesOnly: () -> Unit,
    onFull: () -> Unit,
    onCancelProcessing: () -> Unit,
    logText: String
) {
    Column(modifier) {
        StatusCard(state)
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedButton(onClick = onBack) { Text("Back") } }
            item { OutlinedButton(onClick = onForward) { Text("Forward") } }
            item { OutlinedButton(onClick = onReload) { Text("Reload") } }
            item { OutlinedButton(onClick = onNimsLogin) { Text("NIMS Login") } }
            item { OutlinedButton(onClick = onClearNimsSession) { Text("Clear NIMS Session") } }
            item { OutlinedButton(onClick = onZoomOut) { Text("Zoom -") } }
            item { OutlinedButton(onClick = onZoomIn) { Text("Zoom +") } }
            item { Button(onClick = onOpenCrSearchDirect, enabled = !navigationInProgress) { Text("Open CR Results") } }
            item { Button(onClick = onDiagnose) { Text("Diagnose") } }
            item { Button(onClick = onDiscover) { Text("Discover") } }
            item { Button(onClick = onTestOne) { Text("Test One") } }
            item { Button(onClick = onFast) { Text("Fast") } }
            item { Button(onClick = onCulturesOnly) { Text("Cultures") } }
            item { Button(onClick = onFull) { Text("Full") } }
            item { OutlinedButton(onClick = onCopyFullLog) { Text("Copy Log") } }
            if (state == AppState.FETCHING) item { OutlinedButton(onClick = onCancelProcessing) { Text("Stop") } }
        }
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
        if (logText.isNotBlank()) {
            // Was takeLast(1200) at 96.dp -- routinely cut off the exact part
            // of a crash message that matters. Copy Log (above) gives the
            // full untruncated buffer; this panel is widened and made
            // scrollable to reduce how often that button is even needed.
            Text(
                logText.takeLast(4000),
                Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .verticalScroll(rememberScrollState())
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
    processingMode: ProcessingMode,
    onProcessingModeChange: (ProcessingMode) -> Unit,
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
                Text("Railway settings (optional / advanced)", fontWeight = FontWeight.Bold)
                Text("Leave blank for fully local supported text/HTML and text-based PDF processing.")
                OutlinedTextField(helperUrl, onHelperUrlChange, label = { Text("Optional Railway helper URL") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(helperKey, onHelperKeyChange, label = { Text(if (helperKey.isBlank()) "Optional API key" else "API key entered") }, modifier = Modifier.fillMaxWidth())
                Text("Processing mode", fontWeight = FontWeight.Bold)
                ProcessingMode.values().forEach { mode ->
                    OutlinedButton(onClick = { onProcessingModeChange(mode) }) {
                        Text(
                            (if (mode == processingMode) "✓ " else "") + when (mode) {
                                ProcessingMode.AUTO -> "Automatic with Railway fallback"
                                ProcessingMode.LOCAL_ONLY -> "On-device only"
                                ProcessingMode.REMOTE_ONLY -> "Railway only"
                            }
                        )
                    }
                }
                Text(
                    when (processingMode) {
                        ProcessingMode.AUTO -> "Processes supported text/HTML/PDF reports on-device first; optional Railway fallback is legacy advanced behavior."
                        ProcessingMode.LOCAL_ONLY -> "Reports are fetched and processed on this device. NIMS credentials and cookies are not uploaded."
                        ProcessingMode.REMOTE_ONLY -> "Uses the configured Railway helper for all report parsing and summaries."
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onTest) { Text("Test helper") }
                    OutlinedButton(onClick = onClear) { Text("Clear helper") }
                }
                Text("On-device processing")
                Text("Reports are fetched and processed on this device. NIMS credentials and cookies are not uploaded.")
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
                AppState.NEED_HELPER_SETTINGS -> "Configure Railway helper URL and API key for Railway-only mode."
                AppState.HELPER_READY -> "Login to NIMS manually."
                AppState.NIMS_LOGIN -> "Open the report page after login."
                AppState.REPORT_PAGE_READY -> "Report list detected. Tap Test One, Fast, Cultures, or Full."
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

private var webViewSessionCleaned = false

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
