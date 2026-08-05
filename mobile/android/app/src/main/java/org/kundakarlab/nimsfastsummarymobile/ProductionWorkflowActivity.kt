package org.kundakarlab.nimsfastsummarymobile

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.data.cache.InMemoryReportByteCache
import org.kundakarlab.nimsfastsummarymobile.data.pdf.InMemoryPdfPageRenderer
import org.kundakarlab.nimsfastsummarymobile.data.pdf.PdfBoxAndroidTextExtractor
import org.kundakarlab.nimsfastsummarymobile.data.processing.CultureEpisodeReconciler
import org.kundakarlab.nimsfastsummarymobile.data.processing.LocalTextReportProcessor
import org.kundakarlab.nimsfastsummarymobile.data.processing.OnDeviceReportProcessor
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedReport
import org.kundakarlab.nimsfastsummarymobile.domain.model.ReportInput
import org.kundakarlab.nimsfastsummarymobile.domain.model.SummaryMode
import org.kundakarlab.nimsfastsummarymobile.domain.processing.ProcessingResult
import org.kundakarlab.nimsfastsummarymobile.domain.recovery.ClinicianCorrection
import org.kundakarlab.nimsfastsummarymobile.domain.recovery.ReportIssue
import org.kundakarlab.nimsfastsummarymobile.domain.recovery.ReportIssueController
import org.kundakarlab.nimsfastsummarymobile.domain.recovery.ReportIssueKind
import org.kundakarlab.nimsfastsummarymobile.security.SafeLogBuffer
import org.kundakarlab.nimsfastsummarymobile.ui.mappers.SummaryJsonMapper
import org.kundakarlab.nimsfastsummarymobile.ui.mappers.UiCorrectionOverlay
import org.kundakarlab.nimsfastsummarymobile.ui.models.Abnormality
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiCultureRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiLabTrendRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSourceReport
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private enum class ProductionPhase {
    LOGIN,
    OPENING_CR,
    CR_READY,
    SUBMITTING_CR,
    WAITING_RESULTS,
    PROCESSING,
    REVIEW,
    SESSION_EXPIRED
}

private data class PortalProbe(
    val loginVisible: Boolean = false,
    val crReady: Boolean = false,
    val reportRows: Int = 0,
    val authenticated: Boolean = false,
    val sessionExpired: Boolean = false,
    val documentCount: Int = 0
)

private data class ProductionReportRequest(
    val row: JSONObject,
    val transientArg: String,
    val directUrl: String,
    val reportId: String
)

private data class FetchedProductionReport(
    val request: ProductionReportRequest,
    val contentType: String,
    val finalUrlSafe: String,
    val bytes: ByteArray
)

private data class ProductionPdfState(
    val sourceKey: String,
    val title: String,
    val loading: Boolean = false,
    val bitmap: Bitmap? = null,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val error: String? = null
)

/**
 * Production launcher for the native-first NIMS workflow.
 * The portal WebView remains visible only for manual login/captcha and is kept
 * as a 1 dp hidden transport after authentication.
 */
class ProductionWorkflowActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private val logs = SafeLogBuffer()
    private val issueController = ReportIssueController()
    private val reportByteCache = InMemoryReportByteCache()
    private val processor by lazy {
        OnDeviceReportProcessor(LocalTextReportProcessor(), PdfBoxAndroidTextExtractor(applicationContext))
    }
    private val reportClient by lazy {
        NimsReportHttpClient(
            cookieProvider = { CookieManager.getInstance().getCookie(it).orEmpty() },
            userAgentProvider = { webView.settings.userAgentString }
        )
    }
    private val pdfRenderer by lazy { InMemoryPdfPageRenderer(applicationContext) }

    private val sourceUrls = ConcurrentHashMap<String, String>()
    private val successfulReports = ConcurrentHashMap<String, ParsedReport>()
    private val failedRequests = ConcurrentHashMap<String, ProductionReportRequest>()
    private val pageBitmaps = linkedMapOf<Int, Bitmap>()

    private var phase by mutableStateOf(ProductionPhase.LOGIN)
    private var status by mutableStateOf("Login to NIMS")
    private var crNumber by mutableStateOf("")
    private var activeCrNumber by mutableStateOf("")
    private var crModuleReady by mutableStateOf(false)
    private var authenticated by mutableStateOf(false)
    private var baseSummary by mutableStateOf<UiSummary?>(null)
    private var summary by mutableStateOf<UiSummary?>(null)
    private var selectedTab by mutableIntStateOf(0)
    private var progressDone by mutableIntStateOf(0)
    private var progressTotal by mutableIntStateOf(0)
    private var isProcessing by mutableStateOf(false)
    private var mapping: ReportTemplate? = null
    private var activeJob: Job? = null
    private var summaryJob: Job? = null
    private var resumeFailedAfterLogin = false
    private var pdfState by mutableStateOf<ProductionPdfState?>(null)
    private var currentPdfBytes: ByteArray? = null
    private var processingStartedAt = 0L
    private var lastSummaryAt = 0L
    private var runtimePayload: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(createTemporaryWebView(), true)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = DESKTOP_CHROME_UA
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

                override fun onPageFinished(view: WebView, url: String) {
                    injectRuntimeFallback()
                    probePortalSoon(120L)
                }
            }
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        installRuntimeAtDocumentStart()
        addLog("BUILD versionName=${BuildConfig.VERSION_NAME} versionCode=${BuildConfig.VERSION_CODE}")
        webView.loadUrl(NIMS_LOGIN_URL)

        setContent {
            ProductionTheme {
                ProductionApp(
                    phase = phase,
                    status = status,
                    crNumber = crNumber,
                    activeCrNumber = activeCrNumber,
                    crReady = crModuleReady,
                    webView = webView,
                    summary = summary,
                    selectedTab = selectedTab,
                    onTab = { selectedTab = it },
                    done = progressDone,
                    total = progressTotal,
                    processing = isProcessing,
                    issues = issueController.allIssues(),
                    corrections = issueController.allCorrections(),
                    onCrChange = { crNumber = it.filter(Char::isDigit).take(20) },
                    onContinueLogin = { probePortal(manual = true) },
                    onLogoutOtherSessions = ::logoutOtherSessions,
                    onFetch = ::submitCrAndFetch,
                    onRefresh = ::refreshPatient,
                    onRetryAll = ::retryAllFailed,
                    onRetryOne = ::retryOne,
                    onOpenReport = ::openExactPdf,
                    onLoginAgain = ::loginAgain,
                    onLogout = ::logout,
                    onCopyLogs = ::copyLogs,
                    onChangePatient = ::changePatient,
                    onManualCorrection = ::addManualCorrection,
                    onUndoCorrection = ::undoCorrection
                )
                pdfState?.let { state ->
                    ProductionPdfDialog(
                        state = state,
                        onClose = ::closePdf,
                        onPrevious = { renderPdfPage(state.pageIndex - 1) },
                        onNext = { renderPdfPage(state.pageIndex + 1) }
                    )
                }
            }
        }
    }

    private fun createTemporaryWebView(): WebView = WebView(this).also { it.destroy() }

    private fun installRuntimeAtDocumentStart() {
        runtimePayload = buildString {
            appendLine(assetText("contentUtils.js"))
            appendLine(assetText("nimsReportCore.js"))
            appendLine(assetText("nimsAndroidFrameBridge.js"))
            appendLine(assetText("nimsWebviewShim.js"))
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    runtimePayload,
                    setOf("https://www.nimsts.edu.in", "https://nimsts.edu.in")
                )
            }.onSuccess { addLog("Portal runtime installed at document start") }
                .onFailure { addLog("Portal runtime document-start install failed") }
        }
    }

    private fun injectRuntimeFallback() {
        if (runtimePayload.isBlank()) return
        webView.evaluateJavascript(runtimePayload, null)
    }

    private fun probePortalSoon(delayMs: Long) {
        mainHandler.postDelayed({ probePortal(manual = false) }, delayMs)
    }

    private fun probePortal(manual: Boolean, callback: ((PortalProbe) -> Unit)? = null) {
        webView.evaluateJavascript(NimsPortalBridge.probeScript) { raw ->
            val json = decodeObject(raw)
            val probe = PortalProbe(
                loginVisible = json.optBoolean("loginVisible"),
                crReady = json.optBoolean("crReady"),
                reportRows = json.optInt("reportRows"),
                authenticated = json.optBoolean("authenticated"),
                sessionExpired = json.optBoolean("sessionExpired"),
                documentCount = json.optInt("documentCount")
            )
            callback?.invoke(probe)
            when {
                probe.sessionExpired -> {
                    authenticated = false
                    crModuleReady = false
                    phase = ProductionPhase.SESSION_EXPIRED
                    status = "NIMS session expired. Login again to resume."
                }
                probe.loginVisible -> {
                    authenticated = false
                    crModuleReady = false
                    if (phase != ProductionPhase.SESSION_EXPIRED) phase = ProductionPhase.LOGIN
                    status = if (manual) "Complete user ID, password and captcha, then continue." else "Enter user ID, password and captcha"
                }
                probe.crReady -> {
                    authenticated = true
                    crModuleReady = true
                    if (resumeFailedAfterLogin && failedRequests.isNotEmpty()) {
                        resumeFailedAfterLogin = false
                        retryAllFailed()
                    } else if (phase !in setOf(ProductionPhase.PROCESSING, ProductionPhase.REVIEW)) {
                        phase = ProductionPhase.CR_READY
                        status = "Ready. Enter a CR number."
                    }
                }
                probe.authenticated -> {
                    authenticated = true
                    if (phase in setOf(ProductionPhase.LOGIN, ProductionPhase.SESSION_EXPIRED)) {
                        if (resumeFailedAfterLogin && failedRequests.isNotEmpty()) {
                            resumeFailedAfterLogin = false
                            retryAllFailed()
                        } else {
                            openCrModule()
                        }
                    }
                }
                manual -> status = "NIMS login is not yet verified. Complete login and captcha."
            }
        }
    }

    private fun openCrModule() {
        phase = ProductionPhase.OPENING_CR
        crModuleReady = false
        status = "Opening the CR results module…"
        val script = """
            (function(){
              try{
                if(window.NimsReportCore&&typeof window.NimsReportCore.openCrWiseResultsDirect==='function'){
                  return JSON.stringify(window.NimsReportCore.openCrWiseResultsDirect(document));
                }
              }catch(e){}
              return JSON.stringify({ok:false,errorCode:'core_not_ready'});
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { waitForCrReady(0, false) }
    }

    private fun waitForCrReady(attempt: Int, usedLeafFallback: Boolean) {
        probePortal(manual = false) { probe ->
            when {
                probe.crReady -> Unit
                probe.loginVisible || probe.sessionExpired -> Unit
                attempt >= 36 -> {
                    phase = ProductionPhase.OPENING_CR
                    status = "The NIMS CR module did not become ready. Retry or login again."
                }
                attempt == 12 && !usedLeafFallback -> {
                    webView.loadUrl(CR_SEARCH_URL)
                    mainHandler.postDelayed({ waitForCrReady(attempt + 1, true) }, 500L)
                }
                else -> mainHandler.postDelayed({ waitForCrReady(attempt + 1, usedLeafFallback) }, 350L)
            }
        }
    }

    private fun submitCrAndFetch() {
        if (crNumber.length < 6) {
            status = "Enter a valid CR number."
            return
        }
        if (!crModuleReady) {
            status = "Preparing the CR module. Please wait."
            waitForCrReady(0, false)
            return
        }
        phase = ProductionPhase.SUBMITTING_CR
        status = "Submitting CR number…"
        submitCrAttempt(0)
    }

    private fun submitCrAttempt(attempt: Int) {
        webView.evaluateJavascript(NimsPortalBridge.submitCrScript(crNumber)) { raw ->
            val result = decodeObject(raw)
            if (result.optBoolean("ok")) {
                activeCrNumber = crNumber
                phase = ProductionPhase.WAITING_RESULTS
                status = "Waiting for the report list…"
                waitForReportList(0)
            } else if (attempt < 14) {
                mainHandler.postDelayed({ submitCrAttempt(attempt + 1) }, 350L)
            } else {
                crModuleReady = false
                phase = ProductionPhase.OPENING_CR
                status = "The CR field is not ready. Reopening the NIMS CR module…"
                openCrModule()
            }
        }
    }

    private fun waitForReportList(attempt: Int) {
        webView.evaluateJavascript(NimsPortalBridge.resultListProbeScript) { raw ->
            val result = decodeObject(raw)
            when {
                result.optBoolean("ready") -> discoverAndFetch(refresh = false)
                attempt >= 48 -> {
                    phase = ProductionPhase.CR_READY
                    status = "No report list appeared. Check the CR number and retry."
                }
                else -> mainHandler.postDelayed({ waitForReportList(attempt + 1) }, 350L)
            }
        }
    }

    private fun discoverAndFetch(refresh: Boolean) {
        phase = if (summary == null) ProductionPhase.WAITING_RESULTS else ProductionPhase.REVIEW
        status = if (refresh) "Checking for new or failed reports…" else "Preparing report links…"
        webView.evaluateJavascript(NimsPortalBridge.prepareMappingScript) { raw ->
            val result = decodeObject(raw)
            if (!result.optBoolean("ok")) {
                if (refresh) {
                    status = "The report list is not ready. Refresh the NIMS session and retry."
                } else {
                    phase = ProductionPhase.CR_READY
                    status = "No reports were found for this CR number."
                }
                return@evaluateJavascript
            }
            pollMapping(refresh, 0)
        }
    }

    private fun pollMapping(refresh: Boolean, attempt: Int) {
        webView.evaluateJavascript(NimsPortalBridge.discoverMappingScript) { raw ->
            val templateJson = decodeObject(raw)
            if (templateJson.optBoolean("discovered")) {
                mapping = ReportTemplate(
                    origin = templateJson.optString("origin"),
                    pathname = templateJson.optString("pathname"),
                    modeParamName = templateJson.optString("modeParamName", "hmode"),
                    modeParamValue = templateJson.optString("modeParamValue", "PRINTREPORT"),
                    argumentParameterName = templateJson.optString("argumentParameterName", "fileName")
                )
                selectRowsAndProcess(refresh)
            } else if (attempt < 28) {
                mainHandler.postDelayed({ pollMapping(refresh, attempt + 1) }, 250L)
            } else {
                phase = if (summary == null) ProductionPhase.CR_READY else ProductionPhase.REVIEW
                status = "Report mapping did not become ready. Refresh and retry."
            }
        }
    }

    private fun selectRowsAndProcess(refresh: Boolean) {
        val template = mapping ?: return
        webView.evaluateJavascript(NimsPortalBridge.selectRowsScript) { raw ->
            val rows = decodeArray(raw)
            val prepared = buildList {
                for (index in 0 until rows.length()) {
                    val source = rows.optJSONObject(index) ?: continue
                    val token = firstNonBlank(source, "transientPrintReportArg", "fileName", "reportArg")
                    val url = NimsReportTemplate.directReportUrlOrNull(template, token) ?: continue
                    val normalized = JSONObject(source.toString())
                        .put("report_name", firstNonBlank(source, "report_name", "reportName", "investigation", "testName"))
                        .put("date_sent", firstNonBlank(source, "date_sent", "dateSent", "requisitionDate", "reportDate"))
                        .put("report_type", firstNonBlank(source, "report_type", "reportType", "department", "type"))
                    add(
                        ProductionReportRequest(
                            row = normalized,
                            transientArg = token,
                            directUrl = url,
                            reportId = reportKey(token)
                        )
                    )
                }
            }.distinctBy { it.transientArg }
                .sortedBy(ProductionReportPriority::rank)

            sourceUrls.putAll(prepared.associate { it.reportId to it.directUrl })
            val queue = if (refresh) prepared.filter { !successfulReports.containsKey(it.reportId) || failedRequests.containsKey(it.reportId) } else prepared
            if (queue.isEmpty()) {
                phase = ProductionPhase.REVIEW
                status = "Results are up to date."
                return@evaluateJavascript
            }
            startPipeline(queue, replaceExisting = !refresh)
        }
    }

    private fun startPipeline(requests: List<ProductionReportRequest>, replaceExisting: Boolean) {
        activeJob?.cancel()
        summaryJob?.cancel()
        if (replaceExisting) {
            successfulReports.clear()
            failedRequests.clear()
            issueController.clear()
            reportByteCache.clear()
            baseSummary = null
            summary = null
        }
        progressDone = 0
        progressTotal = requests.size
        isProcessing = true
        phase = if (summary == null) ProductionPhase.PROCESSING else ProductionPhase.REVIEW
        status = "Fetching priority reports…"
        processingStartedAt = SystemClock.elapsedRealtime()
        lastSummaryAt = 0L
        val sessionExpired = AtomicBoolean(false)

        activeJob = lifecycleScope.launch {
            val fetchQueue = Channel<ProductionReportRequest>(FETCH_WORKERS * 2)
            val parseQueue = Channel<FetchedProductionReport>(PARSE_WORKERS * 2)
            val completed = AtomicInteger(0)

            coroutineScope {
                val producer = launch {
                    requests.forEach { fetchQueue.send(it) }
                    fetchQueue.close()
                }
                val fetchers = List(FETCH_WORKERS) {
                    launch(Dispatchers.IO) {
                        for (request in fetchQueue) {
                            if (sessionExpired.get()) break
                            try {
                                val response = reportClient.fetch(request.directUrl, MAX_REPORT_BYTES)
                                val classification = ReportResponseClassifier.classify(response.statusCode, response.contentType, response.bytes)
                                if (classification == "html_login_or_session") {
                                    sessionExpired.set(true)
                                    recordFailure(request, "NIMS session expired. Login again.")
                                    onRequestFinished(completed.incrementAndGet(), requests.size, false)
                                    continue
                                }
                                if (classification == "pdf_report") reportByteCache.put(request.reportId, response.bytes)
                                parseQueue.send(FetchedProductionReport(request, response.contentType, response.finalUrlSafe, response.bytes))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                recordFailure(request, error.message ?: "Report failed")
                                onRequestFinished(completed.incrementAndGet(), requests.size, false)
                            }
                        }
                    }
                }
                val closeParser = launch {
                    fetchers.joinAll()
                    parseQueue.close()
                }
                val parsers = List(PARSE_WORKERS) {
                    launch(Dispatchers.Default) {
                        for (fetched in parseQueue) {
                            val useful = parseFetched(fetched)
                            onRequestFinished(completed.incrementAndGet(), requests.size, useful)
                        }
                    }
                }
                producer.join()
                closeParser.join()
                parsers.joinAll()
            }

            if (sessionExpired.get()) {
                withContext(Dispatchers.Main) {
                    isProcessing = false
                    resumeFailedAfterLogin = true
                    phase = ProductionPhase.SESSION_EXPIRED
                    status = "Session expired. Login again to resume failed reports."
                }
                return@launch
            }

            summaryJob?.cancel()
            publishSummaryNow()
            withContext(Dispatchers.Main) {
                isProcessing = false
                phase = ProductionPhase.REVIEW
                val elapsed = (SystemClock.elapsedRealtime() - processingStartedAt) / 1000.0
                status = if (failedRequests.isEmpty()) {
                    "Results ready · ${"%.1f".format(elapsed)} s"
                } else {
                    "Results ready · ${failedRequests.size} report(s) can be retried"
                }
                addLog("PROCESS_COMPLETE total=${requests.size} success=${successfulReports.size} failed=${failedRequests.size} durationMs=${(elapsed * 1000).toLong()}")
            }
        }
    }

    private suspend fun parseFetched(fetched: FetchedProductionReport): Boolean {
        val request = fetched.request
        val input = ReportInput(
            reportId = request.reportId,
            reportName = request.row.optString("report_name"),
            dateSent = request.row.optString("date_sent"),
            reportType = request.row.optString("report_type", "other"),
            contentType = fetched.contentType.substringBefore(';').ifBlank { "application/octet-stream" },
            bytes = fetched.bytes,
            safeSource = fetched.finalUrlSafe
        )
        return when (val parsed = processor.parseReport(input)) {
            is ProcessingResult.Success -> {
                successfulReports[request.reportId] = parsed.value
                failedRequests.remove(request.reportId)
                issueController.resolve(request.reportId)
                parsed.value.structuredValueCount > 0
            }
            is ProcessingResult.Failure -> {
                recordFailure(request, parsed.userMessage)
                false
            }
            is ProcessingResult.Unsupported -> {
                recordFailure(request, parsed.reason)
                false
            }
        }
    }

    private suspend fun onRequestFinished(count: Int, total: Int, useful: Boolean) {
        withContext(Dispatchers.Main) {
            progressDone = count
            progressTotal = total
            status = "Processed $count/$total reports"
            val now = SystemClock.elapsedRealtime()
            val due = useful && baseSummary == null || count == 1 || count == 6 || count % 15 == 0 || now - lastSummaryAt >= 1_500L || count == total
            if (due) {
                lastSummaryAt = now
                scheduleSummary()
            }
        }
    }

    private fun scheduleSummary() {
        if (summaryJob?.isActive == true) return
        summaryJob = lifecycleScope.launch {
            delay(120L)
            publishSummaryNow()
        }
    }

    private suspend fun publishSummaryNow() {
        val snapshot = successfulReports.values.toList()
        if (snapshot.isEmpty()) return
        val reconciled = withContext(Dispatchers.Default) { CultureEpisodeReconciler.reconcile(snapshot) }
        val result = withContext(Dispatchers.Default) { processor.summarize(reconciled, SummaryMode.FAST) }
        if (result !is ProcessingResult.Success) return
        val json = result.value.helperJson ?: localSummaryJson(reconciled, result.value.text)
        val ui = SummaryJsonMapper.parseSummaryJsonToUiSummary(json)
        withContext(Dispatchers.Main) {
            baseSummary = ui
            summary = UiCorrectionOverlay.apply(ui, issueController.allCorrections())
            if (phase == ProductionPhase.PROCESSING) {
                phase = ProductionPhase.REVIEW
                selectedTab = if (ui.positiveCultureCount > 0) 2 else 0
            }
        }
    }

    private fun recordFailure(request: ProductionReportRequest, message: String) {
        failedRequests[request.reportId] = request
        issueController.recordFailure(
            request.reportId,
            request.row.optString("report_name", "Report"),
            request.row.optString("date_sent"),
            message
        )
    }

    private fun retryAllFailed() {
        val requests = failedRequests.values.toList().sortedBy(ProductionReportPriority::rank)
        if (requests.isEmpty()) {
            status = "No failed reports to retry."
            return
        }
        if (!authenticated) {
            resumeFailedAfterLogin = true
            loginAgain()
            return
        }
        startPipeline(requests, replaceExisting = false)
    }

    private fun retryOne(reportId: String) {
        val request = failedRequests[reportId] ?: return
        if (!authenticated) {
            resumeFailedAfterLogin = true
            loginAgain()
            return
        }
        startPipeline(listOf(request), replaceExisting = false)
    }

    private fun refreshPatient() {
        if (isProcessing) {
            status = "Processing is already in progress."
            return
        }
        if (!authenticated) {
            loginAgain()
            return
        }
        discoverAndFetch(refresh = true)
    }

    private fun addManualCorrection(reportId: String, field: String, value: String, unit: String) {
        if (field.isBlank() || value.isBlank()) return
        issueController.addCorrection(
            ClinicianCorrection(
                reportId = reportId,
                field = field.trim(),
                value = value.trim(),
                unit = unit.trim()
            )
        )
        summary = baseSummary?.let { UiCorrectionOverlay.apply(it, issueController.allCorrections()) }
        status = "Clinician-entered result added."
    }

    private fun undoCorrection(reportId: String) {
        if (issueController.undoLastCorrection(reportId) != null) {
            summary = baseSummary?.let { UiCorrectionOverlay.apply(it, issueController.allCorrections()) }
            status = "Last clinician correction removed."
        }
    }

    private fun loginAgain() {
        authenticated = false
        crModuleReady = false
        phase = ProductionPhase.LOGIN
        status = "Login to NIMS to continue."
        webView.loadUrl(NIMS_LOGIN_URL)
    }

    private fun logoutOtherSessions() {
        webView.evaluateJavascript(NimsPortalBridge.logoutOtherSessionsScript) { result ->
            status = if (result.contains("clicked")) "Other-session logout requested." else "The NIMS page did not offer an other-session logout action."
        }
    }

    private fun changePatient() {
        activeJob?.cancel()
        summaryJob?.cancel()
        clearPatientState()
        if (authenticated) openCrModule() else loginAgain()
    }

    private fun clearPatientState() {
        crNumber = ""
        activeCrNumber = ""
        mapping = null
        successfulReports.clear()
        failedRequests.clear()
        sourceUrls.clear()
        issueController.clear()
        reportByteCache.clear()
        baseSummary = null
        summary = null
        progressDone = 0
        progressTotal = 0
        isProcessing = false
        closePdf()
    }

    private fun logout() {
        activeJob?.cancel()
        summaryJob?.cancel()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            clearPatientState()
            authenticated = false
            crModuleReady = false
            phase = ProductionPhase.LOGIN
            status = "Logged out."
            webView.loadUrl(NIMS_LOGIN_URL)
        }
    }

    private fun copyLogs() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NIMS Results diagnostic log", logs.fullText()))
        status = "Diagnostic log copied."
    }

    private fun addLog(message: String) {
        logs.add(message)
    }

    private fun openExactPdf(sourceKey: String, title: String) {
        val cached = reportByteCache.get(sourceKey)
        if (cached != null) {
            openPdfBytes(sourceKey, title, cached)
            return
        }
        val url = sourceUrls[sourceKey]
        if (url == null) {
            pdfState = ProductionPdfState(sourceKey, title, error = "Source reference unavailable. Refresh the patient results.")
            return
        }
        pdfState = ProductionPdfState(sourceKey, title, loading = true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = reportClient.fetch(url, MAX_REPORT_BYTES)
                if (ReportResponseClassifier.classify(response.statusCode, response.contentType, response.bytes) != "pdf_report") {
                    throw IllegalStateException("NIMS did not return a PDF")
                }
                reportByteCache.put(sourceKey, response.bytes)
                withContext(Dispatchers.Main) { openPdfBytes(sourceKey, title, response.bytes) }
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) {
                    pdfState = ProductionPdfState(sourceKey, title, error = error.message ?: "PDF could not be opened")
                }
            }
        }
    }

    private fun openPdfBytes(sourceKey: String, title: String, bytes: ByteArray) {
        recyclePageBitmaps()
        currentPdfBytes = bytes
        pdfState = ProductionPdfState(sourceKey, title, loading = true)
        renderPdfPage(0)
    }

    private fun renderPdfPage(index: Int) {
        val bytes = currentPdfBytes ?: return
        val current = pdfState ?: return
        if (current.pageCount > 0 && index !in 0 until current.pageCount) return
        pageBitmaps[index]?.let { bitmap ->
            pdfState = current.copy(loading = false, bitmap = bitmap, pageIndex = index, error = null)
            preRenderAdjacent(index)
            return
        }
        pdfState = current.copy(loading = true, error = null)
        lifecycleScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { pdfRenderer.render(bytes, index) }
                pageBitmaps[index] = page.bitmap
                trimPageCache(index)
                pdfState = current.copy(loading = false, bitmap = page.bitmap, pageIndex = page.pageIndex, pageCount = page.pageCount, error = null)
                preRenderAdjacent(index)
            } catch (error: Throwable) {
                pdfState = current.copy(loading = false, error = error.message ?: "Page could not be rendered")
            }
        }
    }

    private fun preRenderAdjacent(index: Int) {
        val bytes = currentPdfBytes ?: return
        val state = pdfState ?: return
        val next = index + 1
        if (next >= state.pageCount || pageBitmaps.containsKey(next)) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { pdfRenderer.render(bytes, next) }.getOrNull()?.let { page ->
                withContext(Dispatchers.Main) {
                    if (pdfState?.sourceKey == state.sourceKey) {
                        pageBitmaps[next] = page.bitmap
                        trimPageCache(index)
                    } else {
                        page.bitmap.recycle()
                    }
                }
            }
        }
    }

    private fun trimPageCache(currentIndex: Int) {
        val keep = setOf(currentIndex, currentIndex - 1, currentIndex + 1)
        pageBitmaps.keys.filterNot(keep::contains).toList().forEach { key -> pageBitmaps.remove(key)?.recycle() }
    }

    private fun closePdf() {
        recyclePageBitmaps()
        currentPdfBytes = null
        pdfState = null
    }

    private fun recyclePageBitmaps() {
        pageBitmaps.values.distinct().forEach { if (!it.isRecycled) it.recycle() }
        pageBitmaps.clear()
    }

    private fun localSummaryJson(reports: List<ParsedReport>, text: String): JSONObject = JSONObject()
        .put("source_reports", JSONArray().also { array ->
            reports.forEach { report ->
                array.put(
                    JSONObject()
                        .put("report_id", report.reportId)
                        .put("date_sent", report.dateSent)
                        .put("report_name", report.reportName)
                        .put("type", report.reportType)
                        .put("status", if (report.rawText.isNotBlank() || report.structuredValueCount > 0) "parsed" else "unsupported")
                        .put("notes", report.warnings.distinct().joinToString("; "))
                        .put("culture_count", report.cultures.size)
                )
            }
        })
        .put("interpretation", JSONArray(text.lines()))

    private fun reportKey(token: String): String = "report_key:" + MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun firstNonBlank(source: JSONObject, vararg keys: String): String {
        keys.forEach { key ->
            val value = source.optString(key).trim()
            if (value.isNotBlank() && !value.equals("null", true)) return value
        }
        return ""
    }

    private fun assetText(name: String): String = runCatching {
        assets.open(name).bufferedReader().use { it.readText() }
    }.getOrDefault("")

    private fun decodeObject(raw: String): JSONObject = runCatching {
        JSONObject(JSONArray("[$raw]").getString(0))
    }.getOrDefault(JSONObject())

    private fun decodeArray(raw: String): JSONArray = runCatching {
        JSONArray(JSONArray("[$raw]").getString(0))
    }.getOrDefault(JSONArray())

    override fun onDestroy() {
        activeJob?.cancel()
        summaryJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        closePdf()
        reportByteCache.clear()
        runCatching { webView.destroy() }
        super.onDestroy()
    }

    companion object {
        private const val NIMS_LOGIN_URL = "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action"
        private const val CR_SEARCH_URL = "https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt"
        private const val DESKTOP_CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val MAX_REPORT_BYTES = 25 * 1024 * 1024
        private const val FETCH_WORKERS = 4
        private const val PARSE_WORKERS = 2
    }
}

private object ProductionReportPriority {
    fun rank(request: ProductionReportRequest): Int {
        val text = (request.row.optString("report_name") + " " + request.row.optString("report_type")).lowercase()
        return when {
            "culture" in text || "sensitivity" in text -> 0
            "cbc" in text || "hemogram" in text || "haemogram" in text -> 1
            listOf("crp", "procalcitonin", "esr", "galactomannan", "glucan").any(text::contains) -> 2
            listOf("creatinine", "urea", "electrolyte", "renal").any(text::contains) -> 3
            listOf("liver", "bilirubin", "albumin", "sgot", "sgpt").any(text::contains) -> 4
            listOf("rbs", "glucose", "sugar").any(text::contains) -> 5
            listOf("pcr", "genexpert", "cbnaat", "viral load").any(text::contains) -> 6
            else -> 10
        }
    }
}

@Composable
private fun ProductionTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF005A8D),
            secondary = Color(0xFF006B5F),
            error = Color(0xFFB3261E)
        ),
        content = content
    )
}

@Composable
private fun ProductionApp(
    phase: ProductionPhase,
    status: String,
    crNumber: String,
    activeCrNumber: String,
    crReady: Boolean,
    webView: WebView,
    summary: UiSummary?,
    selectedTab: Int,
    onTab: (Int) -> Unit,
    done: Int,
    total: Int,
    processing: Boolean,
    issues: List<ReportIssue>,
    corrections: List<ClinicianCorrection>,
    onCrChange: (String) -> Unit,
    onContinueLogin: () -> Unit,
    onLogoutOtherSessions: () -> Unit,
    onFetch: () -> Unit,
    onRefresh: () -> Unit,
    onRetryAll: () -> Unit,
    onRetryOne: (String) -> Unit,
    onOpenReport: (String, String) -> Unit,
    onLoginAgain: () -> Unit,
    onLogout: () -> Unit,
    onCopyLogs: () -> Unit,
    onChangePatient: () -> Unit,
    onManualCorrection: (String, String, String, String) -> Unit,
    onUndoCorrection: (String) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val reviewVisible = summary != null && phase in setOf(ProductionPhase.REVIEW, ProductionPhase.PROCESSING)

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF171912)).padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("NIMS Results", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menuOpen = true }) { Text("Actions", color = Color.White) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Refresh results") }, onClick = { menuOpen = false; onRefresh() })
                        DropdownMenuItem(text = { Text("Retry failed reports") }, onClick = { menuOpen = false; onRetryAll() })
                        DropdownMenuItem(text = { Text("Change CR number") }, onClick = { menuOpen = false; onChangePatient() })
                        DropdownMenuItem(text = { Text("Login again") }, onClick = { menuOpen = false; onLoginAgain() })
                        DropdownMenuItem(text = { Text("Copy diagnostic logs") }, onClick = { menuOpen = false; onCopyLogs() })
                        DropdownMenuItem(text = { Text("Logout") }, onClick = { menuOpen = false; onLogout() })
                    }
                }
            }
        },
        bottomBar = {
            if (reviewVisible) {
                NavigationBar {
                    listOf("Overview", "Labs", "Cultures", "Reports", "Issues").forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { onTab(index) },
                            icon = { Text(label.take(1)) },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "NIMS",
                modifier = Modifier.align(Alignment.Center),
                color = Color(0x0D005A8D),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Black
            )
            when {
                phase in setOf(ProductionPhase.LOGIN, ProductionPhase.SESSION_EXPIRED) -> ProductionLoginScreen(webView, status, onContinueLogin, onLogoutOtherSessions)
                reviewVisible -> {
                    Column(Modifier.fillMaxSize()) {
                        ReviewStatusHeader(activeCrNumber, status, done, total, processing, issues.size)
                        Box(Modifier.weight(1f)) {
                            when (selectedTab) {
                                0 -> ProductionOverview(summary, status)
                                1 -> ProductionLabs(summary.labTrends)
                                2 -> ProductionCultures(summary.cultures, onOpenReport)
                                3 -> ProductionReports(summary.sourceReports, issues, corrections, onRetryOne, onOpenReport, onManualCorrection, onUndoCorrection)
                                else -> ProductionIssues(issues, corrections, onRetryOne, onRetryAll, onLoginAgain, onOpenReport, onUndoCorrection)
                            }
                        }
                        HiddenTransport(webView)
                    }
                }
                phase == ProductionPhase.PROCESSING -> ProductionProcessing(done, total, status, issues.size, onRetryAll, webView)
                else -> ProductionCrScreen(crNumber, onCrChange, status, crReady, onFetch, onLoginAgain, onCopyLogs, webView)
            }
        }
    }
}

@Composable
private fun ProductionLoginScreen(webView: WebView, status: String, onContinue: () -> Unit, onLogoutOthers: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("NIMS login", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Enter user ID, password and captcha. Credentials are not stored by this app.")
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
        Text(status, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onContinue, modifier = Modifier.weight(1f)) { Text("Continue") }
            OutlinedButton(onClick = onLogoutOthers, modifier = Modifier.weight(1f)) { Text("Logout other sessions") }
        }
    }
}

@Composable
private fun ProductionCrScreen(
    cr: String,
    onChange: (String) -> Unit,
    status: String,
    ready: Boolean,
    onFetch: () -> Unit,
    onLoginAgain: () -> Unit,
    onCopyLogs: () -> Unit,
    webView: WebView
) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(28.dp))
        Text("Patient results", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(if (ready) "NIMS session ready" else "Preparing the authenticated CR module…", color = if (ready) MaterialTheme.colorScheme.secondary else Color.DarkGray)
        if (!ready) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedTextField(value = cr, onValueChange = onChange, label = { Text("CR number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = onFetch, enabled = ready && cr.length >= 6, modifier = Modifier.fillMaxWidth()) { Text("Fetch results") }
        Text(status, style = MaterialTheme.typography.bodySmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedButton(onClick = onLoginAgain) { Text("Login again") } }
            item { OutlinedButton(onClick = onCopyLogs) { Text("Copy logs") } }
        }
        HiddenTransport(webView)
    }
}

@Composable
private fun ProductionProcessing(done: Int, total: Int, status: String, failed: Int, onRetry: () -> Unit, webView: WebView) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(status, fontWeight = FontWeight.Bold)
        Text(if (total > 0) "$done of $total reports" else "Preparing reports")
        if (total > 0) LinearProgressIndicator(progress = { done.toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
        if (failed > 0) OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text("Retry failed ($failed)") }
        HiddenTransport(webView)
    }
}

@Composable
private fun HiddenTransport(webView: WebView) {
    AndroidView(factory = { webView }, modifier = Modifier.size(1.dp).alpha(0.01f))
}

@Composable
private fun ReviewStatusHeader(activeCr: String, status: String, done: Int, total: Int, processing: Boolean, issueCount: Int) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFF3F6FA)).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (activeCr.isBlank()) "Patient review" else "CR $activeCr", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (issueCount > 0) Text("$issueCount issue(s)", color = MaterialTheme.colorScheme.error)
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (processing && total > 0) {
            LinearProgressIndicator(progress = { done.toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
        }
    }
}

@Composable
private fun ProductionOverview(summary: UiSummary, status: String) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Overview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            ProductionCard {
                Text(summary.patientSnapshot.identityLine.ifBlank { "Patient identity will appear when available" }, fontWeight = FontWeight.Bold)
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricCard("Positive cultures", summary.positiveCultureCount.toString(), Modifier.weight(1f))
                MetricCard("Abnormal labs", summary.abnormalLabTrends.size.toString(), Modifier.weight(1f))
                MetricCard("Reports", summary.parsedReportCount.toString(), Modifier.weight(1f))
            }
        }
        summary.actionableCultures.take(4).forEach { culture -> item { CompactCultureCard(culture, null) } }
        summary.abnormalLabTrends.take(8).forEach { lab -> item { CompactLabCard(lab) } }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(10.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProductionLabs(rows: List<UiLabTrendRow>) {
    var panel by remember { mutableStateOf("All") }
    val filtered = rows.filter { panel == "All" || productionPanel(it.parameter) == panel }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Laboratory results", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf("All", "Hemogram", "Renal", "Liver", "Inflammatory", "Molecular", "Other")) { value ->
                    OutlinedButton(onClick = { panel = value }) { Text((if (panel == value) "✓ " else "") + value) }
                }
            }
        }
        if (filtered.isEmpty()) item { ProductionCard { Text("No results in this panel") } }
        items(filtered) { CompactLabCard(it) }
    }
}

@Composable
private fun CompactLabCard(row: UiLabTrendRow) {
    ProductionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.parameter, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                row.latestValue,
                fontWeight = FontWeight.Bold,
                color = if (row.abnormality in setOf(Abnormality.HIGH, Abnormality.LOW, Abnormality.CRITICAL)) MaterialTheme.colorScheme.error else Color.Unspecified
            )
        }
        Text(row.latestDate, style = MaterialTheme.typography.bodySmall)
        if (row.previousValue != null) Text("Previous ${row.previousValue} · ${row.trendText}", style = MaterialTheme.typography.bodySmall)
        if (row.history.size > 2) Text("${row.history.size} dated values available", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ProductionCultures(rows: List<UiCultureRow>, onOpenReport: (String, String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Cultures", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        if (rows.isEmpty()) item { ProductionCard { Text("No culture observations were parsed") } }
        items(rows) { row -> CompactCultureCard(row) { onOpenReport(row.sourceKey, row.sourceReportName.ifBlank { row.organism.ifBlank { "Culture report" } }) } }
    }
}

@Composable
private fun CompactCultureCard(row: UiCultureRow, onOpen: (() -> Unit)?) {
    ProductionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.organism.ifBlank { row.growth.ifBlank { row.status.replace('_', ' ') } }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(row.status.replace('_', ' '), fontWeight = FontWeight.Bold, color = if (row.status == "growth_detected") MaterialTheme.colorScheme.error else Color.Unspecified)
        }
        Text(listOf(row.site.ifBlank { row.specimen }, row.collectionDate, row.reportStage).filter(String::isNotBlank).joinToString(" · "))
        sensitivityGroups(row.sensitivitySummary).forEach { (label, value) ->
            Text("$label: $value", style = MaterialTheme.typography.bodySmall, color = when (label) { "R" -> MaterialTheme.colorScheme.error; "S" -> Color(0xFF1B6E2C); else -> Color(0xFF9A5A00) })
        }
        if (row.comment.isNotBlank()) Text(row.comment, style = MaterialTheme.typography.bodySmall)
        if (onOpen != null && row.sourceKey.isNotBlank()) OutlinedButton(onClick = onOpen) { Text("Open report") }
    }
}

@Composable
private fun ProductionReports(
    reports: List<UiSourceReport>,
    issues: List<ReportIssue>,
    corrections: List<ClinicianCorrection>,
    onRetry: (String) -> Unit,
    onOpen: (String, String) -> Unit,
    onCorrection: (String, String, String, String) -> Unit,
    onUndo: (String) -> Unit
) {
    val issueById = issues.associateBy { it.reportId }
    val reportIds = reports.map { it.sourceKey }.toSet()
    val issueOnly = issues.filterNot { it.reportId in reportIds }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reports", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${reports.size} available")
            }
        }
        items(reports) { report ->
            ReportCard(report, issueById[report.sourceKey], corrections.filter { it.reportId == report.sourceKey }, onRetry, onOpen, onCorrection, onUndo)
        }
        items(issueOnly) { issue ->
            IssueOnlyReportCard(issue, corrections.filter { it.reportId == issue.reportId }, onRetry, onOpen, onCorrection, onUndo)
        }
    }
}

@Composable
private fun ReportCard(
    report: UiSourceReport,
    issue: ReportIssue?,
    corrections: List<ClinicianCorrection>,
    onRetry: (String) -> Unit,
    onOpen: (String, String) -> Unit,
    onCorrection: (String, String, String, String) -> Unit,
    onUndo: (String) -> Unit
) {
    var correcting by remember(report.sourceKey) { mutableStateOf(false) }
    var field by remember(report.sourceKey) { mutableStateOf("") }
    var value by remember(report.sourceKey) { mutableStateOf("") }
    var unit by remember(report.sourceKey) { mutableStateOf("") }
    ProductionCard(container = if (issue != null) Color(0xFFFFF2F0) else MaterialTheme.colorScheme.surfaceVariant) {
        Row {
            Column(Modifier.weight(1f)) {
                Text(report.reportName, fontWeight = FontWeight.Bold)
                Text(report.dateSent, style = MaterialTheme.typography.bodySmall)
            }
            Text(if (issue != null) "Needs attention" else report.type.uppercase(), style = MaterialTheme.typography.labelMedium)
        }
        Text(issue?.userMessage ?: when {
            report.results.isNotEmpty() -> "${report.results.size} structured result(s)"
            report.cultureCount > 0 -> "${report.cultureCount} culture observation(s)"
            else -> "Report available"
        })
        corrections.forEach { Text("Clinician entered · ${it.field}: ${it.value} ${it.unit}".trim(), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { OutlinedButton(onClick = { onOpen(report.sourceKey, report.reportName) }) { Text("Open report") } }
            if (issue?.retryable == true) item { Button(onClick = { onRetry(report.sourceKey) }) { Text("Retry") } }
            if (issue != null) item { OutlinedButton(onClick = { correcting = !correcting }) { Text("Enter result") } }
            if (corrections.isNotEmpty()) item { TextButton(onClick = { onUndo(report.sourceKey) }) { Text("Undo correction") } }
        }
        if (correcting) CorrectionForm(field, value, unit, { field = it }, { value = it }, { unit = it }) {
            onCorrection(report.sourceKey, field, value, unit)
            correcting = false
        }
    }
}

@Composable
private fun IssueOnlyReportCard(
    issue: ReportIssue,
    corrections: List<ClinicianCorrection>,
    onRetry: (String) -> Unit,
    onOpen: (String, String) -> Unit,
    onCorrection: (String, String, String, String) -> Unit,
    onUndo: (String) -> Unit
) {
    var correcting by remember(issue.reportId) { mutableStateOf(false) }
    var field by remember(issue.reportId) { mutableStateOf("") }
    var value by remember(issue.reportId) { mutableStateOf("") }
    var unit by remember(issue.reportId) { mutableStateOf("") }
    ProductionCard(container = Color(0xFFFFF2F0)) {
        Text(issue.reportName, fontWeight = FontWeight.Bold)
        Text(issue.dateSent, style = MaterialTheme.typography.bodySmall)
        Text(issue.userMessage)
        corrections.forEach { Text("Clinician entered · ${it.field}: ${it.value} ${it.unit}".trim(), color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (issue.retryable) item { Button(onClick = { onRetry(issue.reportId) }) { Text("Retry") } }
            item { OutlinedButton(onClick = { onOpen(issue.reportId, issue.reportName) }) { Text("Open report") } }
            item { OutlinedButton(onClick = { correcting = !correcting }) { Text("Enter result") } }
            if (corrections.isNotEmpty()) item { TextButton(onClick = { onUndo(issue.reportId) }) { Text("Undo correction") } }
        }
        if (correcting) CorrectionForm(field, value, unit, { field = it }, { value = it }, { unit = it }) {
            onCorrection(issue.reportId, field, value, unit)
            correcting = false
        }
    }
}

@Composable
private fun CorrectionForm(
    field: String,
    value: String,
    unit: String,
    onField: (String) -> Unit,
    onValue: (String) -> Unit,
    onUnit: (String) -> Unit,
    onSave: () -> Unit
) {
    HorizontalDivider()
    Text("Clinician-entered local correction", fontWeight = FontWeight.Bold)
    OutlinedTextField(field, onField, label = { Text("Test / field") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value, onValue, label = { Text("Value") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(unit, onUnit, label = { Text("Unit, optional") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = onSave, enabled = field.isNotBlank() && value.isNotBlank()) { Text("Save") }
}

@Composable
private fun ProductionIssues(
    issues: List<ReportIssue>,
    corrections: List<ClinicianCorrection>,
    onRetry: (String) -> Unit,
    onRetryAll: () -> Unit,
    onLoginAgain: () -> Unit,
    onOpen: (String, String) -> Unit,
    onUndo: (String) -> Unit
) {
    val grouped = issues.groupBy { it.kind }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Issues", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(onClick = onRetryAll, enabled = issues.any { it.retryable }) { Text("Retry all") }
            }
        }
        if (issues.isEmpty()) item { ProductionCard { Text("No unresolved report issues") } }
        grouped.forEach { (kind, entries) ->
            item { Text(issueHeading(kind), fontWeight = FontWeight.Bold) }
            items(entries) { issue ->
                ProductionCard(container = Color(0xFFFFF2F0)) {
                    Text(issue.reportName, fontWeight = FontWeight.Bold)
                    Text(issue.userMessage)
                    Text("Attempt ${issue.attempts}", style = MaterialTheme.typography.bodySmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (issue.retryable) item { Button(onClick = { onRetry(issue.reportId) }) { Text("Retry") } }
                        item { OutlinedButton(onClick = { onOpen(issue.reportId, issue.reportName) }) { Text("Open report") } }
                        if (issue.kind == ReportIssueKind.SESSION_EXPIRED) item { OutlinedButton(onClick = onLoginAgain) { Text("Login again") } }
                        if (corrections.any { it.reportId == issue.reportId }) item { TextButton(onClick = { onUndo(issue.reportId) }) { Text("Undo correction") } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductionCard(container: Color = MaterialTheme.colorScheme.surfaceVariant, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

private fun productionPanel(parameter: String): String {
    val value = parameter.uppercase()
    return when {
        listOf("HEMOGLOBIN", "WBC", "TLC", "PLATELET", "NEUTROPHIL", "LYMPHOCYTE").any(value::contains) -> "Hemogram"
        listOf("CREATININE", "UREA", "SODIUM", "POTASSIUM", "CHLORIDE", "BICARBONATE", "GLUCOSE").any(value::contains) -> "Renal"
        listOf("BILIRUBIN", "AST", "ALT", "SGOT", "SGPT", "ALP", "GGT", "ALBUMIN", "PROTEIN").any(value::contains) -> "Liver"
        listOf("CRP", "ESR", "PROCALCITONIN", "GALACTOMANNAN", "GLUCAN", "BDG", "FERRITIN").any(value::contains) -> "Inflammatory"
        listOf("PCR", "MOLECULAR", "CBNAAT", "GENEXPERT", "VIRAL LOAD").any(value::contains) -> "Molecular"
        else -> "Other"
    }
}

private fun sensitivityGroups(summary: String): List<Pair<String, String>> {
    if (summary.isBlank()) return emptyList()
    val groups = linkedMapOf("S" to mutableListOf<String>(), "I" to mutableListOf(), "R" to mutableListOf())
    summary.split(';').map(String::trim).filter(String::isNotBlank).forEach { entry ->
        when {
            entry.startsWith("S:", true) -> groups.getValue("S") += entry.substringAfter(':').trim()
            entry.startsWith("I:", true) -> groups.getValue("I") += entry.substringAfter(':').trim()
            entry.startsWith("R:", true) -> groups.getValue("R") += entry.substringAfter(':').trim()
            entry.contains("susceptible", true) || entry.contains("sensitive", true) -> groups.getValue("S") += entry.replace(Regex("(?i)\\s+(susceptible|sensitive)$"), "")
            entry.contains("intermediate", true) -> groups.getValue("I") += entry.replace(Regex("(?i)\\s+intermediate$"), "")
            entry.contains("resistant", true) -> groups.getValue("R") += entry.replace(Regex("(?i)\\s+resistant$"), "")
        }
    }
    return groups.mapNotNull { (label, values) -> values.distinct().takeIf { it.isNotEmpty() }?.let { label to it.joinToString(", ") } }
}

private fun issueHeading(kind: ReportIssueKind): String = when (kind) {
    ReportIssueKind.TRANSIENT_NETWORK -> "Could not retrieve"
    ReportIssueKind.SESSION_EXPIRED -> "Session expired"
    ReportIssueKind.PARSE_INCOMPLETE -> "Needs interpretation"
    ReportIssueKind.UNSUPPORTED -> "Unsupported or scanned"
    ReportIssueKind.DUPLICATE -> "Duplicate"
    ReportIssueKind.UNKNOWN -> "Other"
}

@Composable
private fun ProductionPdfDialog(
    state: ProductionPdfState,
    onClose: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF111111)) {
            Column {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(state.title, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.pageCount > 0) Text("${state.pageIndex + 1}/${state.pageCount}", color = Color.White)
                    TextButton(onClick = onClose) { Text("Close", color = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    state.bitmap?.let { Image(it.asImageBitmap(), "PDF page", Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit) }
                    if (state.loading) CircularProgressIndicator()
                    state.error?.let { Text(it, color = Color.White, modifier = Modifier.padding(20.dp)) }
                }
                if (state.pageCount > 1) {
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        OutlinedButton(onClick = onPrevious, enabled = state.pageIndex > 0) { Text("Previous") }
                        OutlinedButton(onClick = onNext, enabled = state.pageIndex + 1 < state.pageCount) { Text("Next") }
                    }
                }
            }
        }
    }
}
