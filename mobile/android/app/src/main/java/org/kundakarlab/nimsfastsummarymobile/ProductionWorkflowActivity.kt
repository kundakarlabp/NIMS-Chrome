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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
import org.kundakarlab.nimsfastsummarymobile.domain.recovery.ReportIssueController
import org.kundakarlab.nimsfastsummarymobile.security.SafeLogBuffer
import org.kundakarlab.nimsfastsummarymobile.ui.mappers.SummaryJsonMapper
import org.kundakarlab.nimsfastsummarymobile.ui.mappers.UiCorrectionOverlay
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal enum class ProductionPhase {
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

internal data class ProductionPdfState(
    val sourceKey: String,
    val title: String,
    val loading: Boolean = false,
    val bitmap: Bitmap? = null,
    val pageIndex: Int = 0,
    val pageCount: Int = 0,
    val error: String? = null
)

/**
 * Native-first launcher. The NIMS WebView is visible only for manual login and
 * captcha; after authentication it remains attached as a hidden transport.
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
    private var resultListBaseline: String = ""
    private var lastCompletedCr: String = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().setAcceptCookie(true)

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
            ProductionWorkflowTheme {
                ProductionWorkflowApp(
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
                    onUndoCorrection = ::undoCorrection,
                    onIgnoreIssue = ::ignoreIssue
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
        if (runtimePayload.isNotBlank()) webView.evaluateJavascript(runtimePayload, null)
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
            applyPortalProbe(probe, manual)
            callback?.invoke(probe)
        }
    }

    private fun applyPortalProbe(probe: PortalProbe, manual: Boolean) {
        when {
            probe.sessionExpired -> {
                authenticated = false
                crModuleReady = false
                phase = ProductionPhase.SESSION_EXPIRED
                status = "NIMS session expired. Login again to resume."
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
            probe.loginVisible -> {
                authenticated = false
                crModuleReady = false
                if (phase != ProductionPhase.SESSION_EXPIRED) phase = ProductionPhase.LOGIN
                status = if (manual) {
                    "Complete user ID, password and captcha, then continue."
                } else {
                    "Enter user ID, password and captcha"
                }
            }
            manual -> status = "NIMS login is not yet verified. Complete login and captcha."
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
                probe.crReady || probe.loginVisible || probe.sessionExpired -> Unit
                attempt >= CR_READY_MAX_ATTEMPTS -> {
                    phase = ProductionPhase.OPENING_CR
                    status = "The NIMS CR module did not become ready. Retry or login again."
                }
                attempt == CR_LEAF_FALLBACK_ATTEMPT && !usedLeafFallback -> {
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
        status = "Preparing CR submission…"
        webView.evaluateJavascript(NimsPortalBridge.resultListProbeScript(crNumber)) { raw ->
            resultListBaseline = decodeObject(raw).optString("signature")
            status = "Submitting CR number…"
            submitCrAttempt(0)
        }
    }

    private fun submitCrAttempt(attempt: Int) {
        webView.evaluateJavascript(NimsPortalBridge.submitCrScript(crNumber)) { raw ->
            val result = decodeObject(raw)
            if (result.optBoolean("ok")) {
                activeCrNumber = crNumber
                phase = ProductionPhase.WAITING_RESULTS
                status = "Waiting for the report list…"
                waitForReportList(0)
            } else if (attempt < CR_SUBMIT_MAX_ATTEMPTS) {
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
        webView.evaluateJavascript(NimsPortalBridge.resultListProbeScript(crNumber)) { raw ->
            val result = decodeObject(raw)
            val ready = result.optBoolean("ready")
            val signature = result.optString("signature")
            val expectedCrVisible = result.optBoolean("crMatch")
            val sameKnownCr = crNumber.isNotBlank() && crNumber == lastCompletedCr
            val listConfirmed = ready && (
                resultListBaseline.isBlank() ||
                    signature != resultListBaseline ||
                    expectedCrVisible ||
                    sameKnownCr
                )
            when {
                listConfirmed -> {
                    lastCompletedCr = crNumber
                    discoverAndFetch(refresh = false)
                }
                attempt >= REPORT_LIST_MAX_ATTEMPTS -> {
                    phase = ProductionPhase.CR_READY
                    status = "No matching report list appeared. Check the CR number and retry."
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
                phase = if (summary == null) ProductionPhase.CR_READY else ProductionPhase.REVIEW
                status = if (refresh) {
                    "The report list is not ready. Refresh the NIMS session and retry."
                } else {
                    "No reports were found for this CR number."
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
            } else if (attempt < MAPPING_MAX_ATTEMPTS) {
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

            if (prepared.isEmpty()) {
                phase = if (summary == null) ProductionPhase.CR_READY else ProductionPhase.REVIEW
                status = "No downloadable reports were found."
                return@evaluateJavascript
            }

            if (!refresh) sourceUrls.clear()
            sourceUrls.putAll(prepared.associate { it.reportId to it.directUrl })
            val queue = if (refresh) {
                prepared.filter { !successfulReports.containsKey(it.reportId) || failedRequests.containsKey(it.reportId) }
            } else {
                prepared
            }
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
                            if (sessionExpired.get()) {
                                recordFailure(request, "NIMS session expired. Login again.")
                                onRequestFinished(completed.incrementAndGet(), requests.size, false)
                                continue
                            }
                            try {
                                val response = reportClient.fetch(request.directUrl, MAX_REPORT_BYTES)
                                val classification = ReportResponseClassifier.classify(
                                    response.statusCode,
                                    response.contentType,
                                    response.bytes
                                )
                                if (classification == "html_login_or_session") {
                                    sessionExpired.set(true)
                                    recordFailure(request, "NIMS session expired. Login again.")
                                    onRequestFinished(completed.incrementAndGet(), requests.size, false)
                                    continue
                                }
                                if (classification == "pdf_report") {
                                    reportByteCache.put(request.reportId, response.bytes)
                                }
                                parseQueue.send(
                                    FetchedProductionReport(
                                        request,
                                        response.contentType,
                                        response.finalUrlSafe,
                                        response.bytes
                                    )
                                )
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

            summaryJob?.cancelAndJoin()
            publishSummaryNow()

            withContext(Dispatchers.Main) {
                isProcessing = false
                val durationMs = SystemClock.elapsedRealtime() - processingStartedAt
                if (sessionExpired.get()) {
                    resumeFailedAfterLogin = true
                    authenticated = false
                    crModuleReady = false
                    phase = ProductionPhase.SESSION_EXPIRED
                    status = "Session expired. Login again to resume failed reports."
                } else {
                    phase = ProductionPhase.REVIEW
                    status = if (failedRequests.isEmpty()) {
                        "Results ready · ${"%.1f".format(durationMs / 1000.0)} s"
                    } else {
                        "Results ready · ${failedRequests.size} report(s) can be retried"
                    }
                }
                addLog(
                    "PROCESS_COMPLETE total=${requests.size} success=${successfulReports.size} " +
                        "failed=${failedRequests.size} durationMs=$durationMs"
                )
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
            val due = (useful && baseSummary == null) ||
                count == 1 ||
                count == 6 ||
                count % 15 == 0 ||
                now - lastSummaryAt >= SUMMARY_INTERVAL_MS ||
                count == total
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
        if (isProcessing) {
            status = "Wait for the current processing cycle to finish."
            return
        }
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
        if (isProcessing) {
            status = "Wait for the current processing cycle to finish."
            return
        }
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
        val resultDate = failedRequests[reportId]?.row?.optString("date_sent")
            ?: successfulReports[reportId]?.dateSent
            ?: ""
        issueController.addCorrection(
            ClinicianCorrection(
                reportId = reportId,
                field = field.trim(),
                value = value.trim(),
                unit = unit.trim(),
                resultDate = resultDate
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

    private fun ignoreIssue(reportId: String) {
        issueController.resolve(reportId)
        status = "Issue hidden from the review list."
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
            status = if (result.contains("clicked")) {
                "Other-session logout requested."
            } else {
                "The NIMS page did not offer an other-session logout action."
            }
        }
    }

    private fun changePatient() {
        activeJob?.cancel()
        summaryJob?.cancel()
        clearPatientState(clearLastCompletedCr = false)
        if (authenticated) openCrModule() else loginAgain()
    }

    private fun clearPatientState(clearLastCompletedCr: Boolean) {
        crNumber = ""
        activeCrNumber = ""
        resultListBaseline = ""
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
        if (clearLastCompletedCr) lastCompletedCr = ""
        closePdf()
    }

    private fun logout() {
        activeJob?.cancel()
        summaryJob?.cancel()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            clearPatientState(clearLastCompletedCr = true)
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
        reportByteCache.get(sourceKey)?.let { cached ->
            openPdfBytes(sourceKey, title, cached)
            return
        }
        val url = sourceUrls[sourceKey]
        if (url == null) {
            pdfState = ProductionPdfState(
                sourceKey,
                title,
                error = "Source reference unavailable. Refresh the patient results."
            )
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
                    pdfState = ProductionPdfState(
                        sourceKey,
                        title,
                        error = error.message ?: "PDF could not be opened"
                    )
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
                pdfState = current.copy(
                    loading = false,
                    bitmap = page.bitmap,
                    pageIndex = page.pageIndex,
                    pageCount = page.pageCount,
                    error = null
                )
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
        pageBitmaps.keys.filterNot(keep::contains).toList().forEach { key ->
            pageBitmaps.remove(key)?.recycle()
        }
    }

    private fun closePdf() {
        recyclePageBitmaps()
        currentPdfBytes = null
        pdfState = null
    }

    private fun recyclePageBitmaps() {
        pageBitmaps.values.distinct().forEach { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
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
        private const val SUMMARY_INTERVAL_MS = 1_500L
        private const val CR_READY_MAX_ATTEMPTS = 36
        private const val CR_LEAF_FALLBACK_ATTEMPT = 12
        private const val CR_SUBMIT_MAX_ATTEMPTS = 14
        private const val REPORT_LIST_MAX_ATTEMPTS = 48
        private const val MAPPING_MAX_ATTEMPTS = 28
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
