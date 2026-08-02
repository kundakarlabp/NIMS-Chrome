package org.kundakarlab.nimsfastsummarymobile

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import org.kundakarlab.nimsfastsummarymobile.data.pdf.InMemoryPdfPageRenderer
import org.kundakarlab.nimsfastsummarymobile.data.pdf.PdfBoxAndroidTextExtractor
import org.kundakarlab.nimsfastsummarymobile.data.processing.LocalTextReportProcessor
import org.kundakarlab.nimsfastsummarymobile.data.processing.OnDeviceReportProcessor
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedReport
import org.kundakarlab.nimsfastsummarymobile.domain.model.ProcessingMode
import org.kundakarlab.nimsfastsummarymobile.domain.model.ReportInput
import org.kundakarlab.nimsfastsummarymobile.domain.model.SummaryMode
import org.kundakarlab.nimsfastsummarymobile.domain.processing.ProcessingResult
import org.kundakarlab.nimsfastsummarymobile.domain.recovery.ClinicianCorrection
import org.kundakarlab.nimsfastsummarymobile.domain.recovery.ReportIssue
import org.kundakarlab.nimsfastsummarymobile.domain.recovery.ReportIssueController
import org.kundakarlab.nimsfastsummarymobile.security.SafeLogBuffer
import org.kundakarlab.nimsfastsummarymobile.ui.mappers.SummaryJsonMapper
import org.kundakarlab.nimsfastsummarymobile.ui.models.Abnormality
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiCultureRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiLabTrendRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSourceReport
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private enum class StreamlinedPhase { LOGIN, CR_ENTRY, PROCESSING, REVIEW }

/**
 * Streamlined local-first activity. The original portal remains an internal
 * authenticated transport; normal review happens in native Compose screens.
 */
class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private val logs = SafeLogBuffer()
    private val issueController = ReportIssueController()
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
    private val failedRequests = ConcurrentHashMap<String, PreparedReportRequestV2>()

    private var phase by mutableStateOf(StreamlinedPhase.LOGIN)
    private var status by mutableStateOf("Login to NIMS")
    private var crNumber by mutableStateOf("")
    private var summary by mutableStateOf<UiSummary?>(null)
    private var selectedTab by mutableIntStateOf(0)
    private var progressDone by mutableIntStateOf(0)
    private var progressTotal by mutableIntStateOf(0)
    private var activeJob: Job? = null
    private var mapping: ReportTemplate? = null
    private var exactPdf by mutableStateOf<ExactPdfStateV2?>(null)
    private var exactPdfBytes: ByteArray? = null

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
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
                override fun onPageFinished(view: WebView, url: String) {
                    val lower = url.lowercase()
                    when {
                        lower.contains("loginlogin.action") -> {
                            phase = StreamlinedPhase.LOGIN
                            status = "Enter user ID, password and captcha"
                        }
                        lower.contains("viewcrnowisereportprocess.cnt") -> {
                            phase = StreamlinedPhase.CR_ENTRY
                            status = "Enter CR number"
                        }
                        phase == StreamlinedPhase.LOGIN -> {
                            status = "Login successful"
                            openCrSearch()
                        }
                    }
                }
            }
        }
        webView.loadUrl(NIMS_LOGIN_URL)
        addLog("BUILD: versionName=${BuildConfig.VERSION_NAME} versionCode=${BuildConfig.VERSION_CODE}")

        setContent {
            StreamlinedTheme {
                StreamlinedApp(
                    phase = phase,
                    status = status,
                    crNumber = crNumber,
                    onCrChange = { crNumber = it.filter(Char::isDigit).take(20) },
                    webView = webView,
                    summary = summary,
                    selectedTab = selectedTab,
                    onTab = { selectedTab = it },
                    done = progressDone,
                    total = progressTotal,
                    issues = issueController.allIssues(),
                    onContinueAfterLogin = ::openCrSearch,
                    onLogoutOtherSessions = ::logoutOtherSessions,
                    onFetch = ::submitCrAndFetch,
                    onRefresh = ::refreshPatient,
                    onRetryAll = ::retryAllFailed,
                    onRetryOne = ::retryOne,
                    onOpenPdf = { openExactPdf(it.sourceKey, it.reportName) },
                    onLoginAgain = ::loginAgain,
                    onLogout = ::logout,
                    onCopyLogs = ::copyLogs,
                    onChangePatient = ::changePatient,
                    onManualCorrection = ::addManualCorrection
                )
                exactPdf?.let { state ->
                    ExactPdfDialogV2(state, ::closePdf, { renderPdfPage(state.pageIndex - 1) }, { renderPdfPage(state.pageIndex + 1) })
                }
            }
        }
    }

    private fun openCrSearch() {
        phase = StreamlinedPhase.CR_ENTRY
        status = "Loading CR search…"
        webView.loadUrl(CR_SEARCH_URL)
    }

    private fun submitCrAndFetch() {
        if (crNumber.length < 6) {
            status = "Enter a valid CR number"
            return
        }
        status = "Submitting CR number…"
        val escaped = JSONObject.quote(crNumber)
        val script = """
            (function(){
              const docs=[document];
              for(const f of document.querySelectorAll('iframe,frame')){try{if(f.contentDocument)docs.push(f.contentDocument);}catch(e){}}
              for(const d of docs){
                const inputs=[...d.querySelectorAll('input')];
                const cr=inputs.find(x=>/cr.?no|cr.?number|crnum/i.test((x.id||'')+' '+(x.name||'')+' '+(x.placeholder||''))) || inputs.find(x=>x.type==='text');
                if(!cr) continue;
                cr.focus(); cr.value=$escaped; cr.dispatchEvent(new Event('input',{bubbles:true})); cr.dispatchEvent(new Event('change',{bubbles:true}));
                const actions=[...d.querySelectorAll('button,input[type=button],input[type=submit],a')];
                const go=actions.find(x=>/^(go|search|view|submit)$/i.test((x.innerText||x.value||'').trim())) || actions.find(x=>/go|search|view/i.test((x.innerText||x.value||'')));
                if(go){go.click(); return 'submitted';}
              }
              return 'cr_input_not_found';
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            if (!result.contains("submitted")) {
                status = "CR field not ready. Refresh and retry."
                return@evaluateJavascript
            }
            Handler(Looper.getMainLooper()).postDelayed({ discoverAndFetch() }, 1_200L)
        }
    }

    private fun discoverAndFetch() {
        phase = StreamlinedPhase.PROCESSING
        status = "Preparing report list…"
        val core = assetText("nimsReportCore.js")
        webView.evaluateJavascript("$core\nJSON.stringify(NimsReportCore.clickFirstReportForMode('test_direct', document));") {
            Handler(Looper.getMainLooper()).postDelayed({
                webView.evaluateJavascript("$core\nJSON.stringify(NimsReportCore.discoverSetPdfTemplate(document));") { raw ->
                    val templateJson = decodeObject(raw)
                    if (!templateJson.optBoolean("discovered")) {
                        phase = StreamlinedPhase.CR_ENTRY
                        status = "No report list found. Check the CR number and retry."
                        return@evaluateJavascript
                    }
                    mapping = ReportTemplate(
                        origin = templateJson.optString("origin"),
                        pathname = templateJson.optString("pathname"),
                        modeParamName = templateJson.optString("modeParamName", "hmode"),
                        modeParamValue = templateJson.optString("modeParamValue", "PRINTREPORT"),
                        argumentParameterName = templateJson.optString("argumentParameterName", "fileName")
                    )
                    selectRowsAndProcess()
                }
            }, 900L)
        }
    }

    private fun selectRowsAndProcess() {
        val template = mapping ?: return
        val core = assetText("nimsReportCore.js")
        webView.evaluateJavascript("$core\nJSON.stringify(NimsReportCore.selectRowsForModeFromDoc('bulk_fast', document));") { raw ->
            val rows = decodeArray(raw)
            val prepared = buildList {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val token = row.optString("transientPrintReportArg")
                    val url = NimsReportTemplate.directReportUrlOrNull(template, token) ?: continue
                    val id = reportKey(token, row)
                    add(PreparedReportRequestV2(row, token, url, id))
                }
            }.distinctBy { it.reportId }
                .sortedBy(ReportPriorityV2::rank)
            sourceUrls.clear()
            prepared.forEach { sourceUrls[it.reportId] = it.directUrl }
            startWorkerPipeline(prepared, replaceExisting = true)
        }
    }

    private fun startWorkerPipeline(requests: List<PreparedReportRequestV2>, replaceExisting: Boolean) {
        activeJob?.cancel()
        if (replaceExisting) {
            successfulReports.clear()
            failedRequests.clear()
            issueController.clear()
            summary = null
        }
        progressDone = 0
        progressTotal = requests.size
        phase = StreamlinedPhase.PROCESSING
        status = "Fetching priority reports…"
        activeJob = lifecycleScope.launch {
            val channel = Channel<PreparedReportRequestV2>(capacity = WORKERS * 2)
            val done = AtomicInteger(0)
            coroutineScope {
                val workers = List(WORKERS) {
                    launch(Dispatchers.IO) {
                        for (request in channel) {
                            processRequest(request)
                            val count = done.incrementAndGet()
                            withContext(Dispatchers.Main) {
                                progressDone = count
                                if (count == 1 || count % 4 == 0 || count == requests.size) publishPartialSummary()
                                status = "Processed $count/${requests.size} reports"
                            }
                        }
                    }
                }
                launch {
                    requests.forEach { channel.send(it) }
                    channel.close()
                }
                workers.joinAll()
            }
            publishPartialSummary()
            phase = StreamlinedPhase.REVIEW
            selectedTab = if (summary?.positiveCultureCount ?: 0 > 0) 2 else 0
            status = if (failedRequests.isEmpty()) "Results ready" else "Results ready · ${failedRequests.size} report(s) can be retried"
        }
    }

    private suspend fun processRequest(request: PreparedReportRequestV2) {
        try {
            val response = reportClient.fetch(request.directUrl, MAX_REPORT_BYTES)
            val classification = ReportResponseClassifier.classify(response.statusCode, response.contentType, response.bytes)
            if (classification == "html_login_or_session") throw SessionExpiredV2()
            val input = ReportInput(
                reportId = request.reportId,
                reportName = request.row.optString("report_name"),
                dateSent = request.row.optString("date_sent"),
                reportType = request.row.optString("report_type", "other"),
                contentType = response.contentType.substringBefore(';').ifBlank { "application/octet-stream" },
                bytes = response.bytes,
                safeSource = response.finalUrlSafe
            )
            when (val parsed = processor.parseReport(input)) {
                is ProcessingResult.Success -> {
                    successfulReports[request.reportId] = parsed.value
                    failedRequests.remove(request.reportId)
                    issueController.resolve(request.reportId)
                }
                is ProcessingResult.Failure -> recordFailure(request, parsed.userMessage)
                is ProcessingResult.Unsupported -> recordFailure(request, parsed.reason)
            }
        } catch (_: SessionExpiredV2) {
            recordFailure(request, "NIMS session expired. Login again.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            recordFailure(request, error.message ?: "Report failed")
        }
    }

    private fun recordFailure(request: PreparedReportRequestV2, message: String) {
        failedRequests[request.reportId] = request
        issueController.recordFailure(
            request.reportId,
            request.row.optString("report_name", "Report"),
            request.row.optString("date_sent"),
            message
        )
    }

    private suspend fun publishPartialSummary() {
        val reports = successfulReports.values.toList()
        if (reports.isEmpty()) return
        when (val result = processor.summarize(reports, SummaryMode.FAST)) {
            is ProcessingResult.Success -> {
                val json = result.value.helperJson ?: localSummaryJson(reports, result.value.text)
                summary = SummaryJsonMapper.parseSummaryJsonToUiSummary(json)
            }
            else -> Unit
        }
    }

    private fun retryAllFailed() {
        val requests = failedRequests.values.toList().sortedBy(ReportPriorityV2::rank)
        if (requests.isEmpty()) {
            status = "No failed reports to retry"
            return
        }
        startWorkerPipeline(requests, replaceExisting = false)
    }

    private fun retryOne(reportId: String) {
        val request = failedRequests[reportId] ?: return
        startWorkerPipeline(listOf(request), replaceExisting = false)
    }

    private fun refreshPatient() {
        if (phase == StreamlinedPhase.PROCESSING) return
        discoverAndFetch()
    }

    private fun addManualCorrection(reportId: String, field: String, value: String, unit: String) {
        if (field.isBlank() || value.isBlank()) return
        issueController.addCorrection(ClinicianCorrection(reportId, field.trim(), value.trim(), unit.trim()))
        status = "Clinician correction added"
    }

    private fun loginAgain() {
        phase = StreamlinedPhase.LOGIN
        status = "Login again to resume failed reports"
        webView.loadUrl(NIMS_LOGIN_URL)
    }

    private fun logoutOtherSessions() {
        val script = """
            (function(){
              const all=[...document.querySelectorAll('a,button,input[type=button],input[type=submit]')];
              const target=all.find(x=>/logout.*other|terminate.*session|force.*login|close.*session/i.test((x.innerText||x.value||'')));
              if(target){target.click();return 'clicked';}
              return 'not_available';
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { result ->
            status = if (result.contains("clicked")) "Other-session logout requested" else "Other-session option is not available on this page"
        }
    }

    private fun changePatient() {
        activeJob?.cancel()
        crNumber = ""
        summary = null
        successfulReports.clear()
        failedRequests.clear()
        issueController.clear()
        openCrSearch()
    }

    private fun logout() {
        activeJob?.cancel()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            successfulReports.clear()
            failedRequests.clear()
            issueController.clear()
            summary = null
            crNumber = ""
            phase = StreamlinedPhase.LOGIN
            webView.loadUrl(NIMS_LOGIN_URL)
            status = "Logged out"
        }
    }

    private fun copyLogs() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NIMS Results diagnostic log", logs.fullText()))
        status = "Diagnostic log copied"
    }

    private fun addLog(message: String) {
        logs.add(message)
    }

    private fun openExactPdf(sourceKey: String, title: String) {
        val url = sourceUrls[sourceKey]
        if (url == null) {
            exactPdf = ExactPdfStateV2(title, error = "Source reference unavailable. Refresh the patient results.")
            return
        }
        exactPdf = ExactPdfStateV2(title, loading = true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val response = reportClient.fetch(url, MAX_REPORT_BYTES)
                if (ReportResponseClassifier.classify(response.statusCode, response.contentType, response.bytes) != "pdf_report") {
                    throw IllegalStateException("NIMS did not return a PDF")
                }
                exactPdfBytes = response.bytes
                withContext(Dispatchers.Main) { renderPdfPage(0) }
            } catch (error: Throwable) {
                withContext(Dispatchers.Main) { exactPdf = ExactPdfStateV2(title, error = error.message ?: "PDF could not be opened") }
            }
        }
    }

    private fun renderPdfPage(index: Int) {
        val bytes = exactPdfBytes ?: return
        lifecycleScope.launch {
            val current = exactPdf ?: return@launch
            if (current.pageCount > 0 && index !in 0 until current.pageCount) return@launch
            exactPdf = current.copy(loading = true)
            try {
                val page = withContext(Dispatchers.IO) { pdfRenderer.render(bytes, index) }
                exactPdf = ExactPdfStateV2(current.title, bitmap = page.bitmap, pageIndex = page.pageIndex, pageCount = page.pageCount)
            } catch (error: Throwable) {
                exactPdf = current.copy(loading = false, error = error.message ?: "Page could not be rendered")
            }
        }
    }

    private fun closePdf() {
        exactPdf?.bitmap?.recycle()
        exactPdf = null
        exactPdfBytes = null
    }

    private fun localSummaryJson(reports: List<ParsedReport>, text: String): JSONObject = JSONObject()
        .put("source_reports", JSONArray().also { array ->
            reports.forEach { report ->
                array.put(JSONObject()
                    .put("report_id", report.reportId)
                    .put("date_sent", report.dateSent)
                    .put("report_name", report.reportName)
                    .put("type", report.reportType)
                    .put("status", if (report.labs.isEmpty() && report.cultures.isEmpty()) "unsupported" else "parsed")
                    .put("notes", report.warnings.distinct().joinToString("; "))
                    .put("culture_count", report.cultures.size))
            }
        })
        .put("interpretation", JSONArray(text.lines()))

    private fun reportKey(token: String, row: JSONObject): String {
        val raw = "$token|${row.optString("date_sent")}|${row.optString("report_name")}|${row.optString("department")}"
        return "report_key:" + MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun assetText(name: String): String = assets.open(name).bufferedReader().use { it.readText() }
    private fun decodeObject(raw: String): JSONObject = runCatching { JSONObject(JSONArray("[$raw]").getString(0)) }.getOrDefault(JSONObject())
    private fun decodeArray(raw: String): JSONArray = runCatching { JSONArray(JSONArray("[$raw]").getString(0)) }.getOrDefault(JSONArray())

    override fun onDestroy() {
        activeJob?.cancel()
        closePdf()
        runCatching { webView.destroy() }
        super.onDestroy()
    }

    companion object {
        private const val NIMS_LOGIN_URL = "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action"
        private const val CR_SEARCH_URL = "https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt"
        private const val DESKTOP_CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val MAX_REPORT_BYTES = 25 * 1024 * 1024
        private const val WORKERS = 4
    }
}

data class ReportFetchResult(val contentType: String, val statusCode: Int, val finalUrlSafe: String, val bytes: ByteArray)
data class PreparedReportRequestV2(val row: JSONObject, val transientArg: String, val directUrl: String, val reportId: String)
private class SessionExpiredV2 : IllegalStateException("NIMS session expired")
private data class ExactPdfStateV2(val title: String, val loading: Boolean = false, val bitmap: Bitmap? = null, val pageIndex: Int = 0, val pageCount: Int = 0, val error: String? = null)

private object ReportPriorityV2 {
    fun rank(request: PreparedReportRequestV2): Int {
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
private fun StreamlinedTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF005A8D), secondary = Color(0xFF006B5F)), content = content)
}

@Composable
private fun StreamlinedApp(
    phase: StreamlinedPhase,
    status: String,
    crNumber: String,
    onCrChange: (String) -> Unit,
    webView: WebView,
    summary: UiSummary?,
    selectedTab: Int,
    onTab: (Int) -> Unit,
    done: Int,
    total: Int,
    issues: List<ReportIssue>,
    onContinueAfterLogin: () -> Unit,
    onLogoutOtherSessions: () -> Unit,
    onFetch: () -> Unit,
    onRefresh: () -> Unit,
    onRetryAll: () -> Unit,
    onRetryOne: (String) -> Unit,
    onOpenPdf: (UiSourceReport) -> Unit,
    onLoginAgain: () -> Unit,
    onLogout: () -> Unit,
    onCopyLogs: () -> Unit,
    onChangePatient: () -> Unit,
    onManualCorrection: (String, String, String, String) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("NIMS Results", color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menu = true }) { Text("Actions", color = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Refresh results") }, onClick = { menu = false; onRefresh() })
                        DropdownMenuItem(text = { Text("Retry failed reports") }, onClick = { menu = false; onRetryAll() })
                        DropdownMenuItem(text = { Text("Change CR number") }, onClick = { menu = false; onChangePatient() })
                        DropdownMenuItem(text = { Text("Login again") }, onClick = { menu = false; onLoginAgain() })
                        DropdownMenuItem(text = { Text("Copy diagnostic logs") }, onClick = { menu = false; onCopyLogs() })
                        DropdownMenuItem(text = { Text("Logout") }, onClick = { menu = false; onLogout() })
                    }
                }
            }
        },
        bottomBar = {
            if (phase == StreamlinedPhase.REVIEW) {
                NavigationBar {
                    listOf("Overview", "Labs", "Cultures", "Reports", "Issues").forEachIndexed { index, label ->
                        NavigationBarItem(selected = selectedTab == index, onClick = { onTab(index) }, icon = { Text(label.take(1)) }, label = { Text(label) })
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Text("NIMS", modifier = Modifier.align(Alignment.Center), color = Color(0x0D005A8D), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black)
            when (phase) {
                StreamlinedPhase.LOGIN -> LoginScreen(webView, status, onContinueAfterLogin, onLogoutOtherSessions)
                StreamlinedPhase.CR_ENTRY -> CrScreen(crNumber, onCrChange, status, onFetch, onLoginAgain, onCopyLogs)
                StreamlinedPhase.PROCESSING -> ProcessingScreen(done, total, status, issues.size, onRetryAll)
                StreamlinedPhase.REVIEW -> when (selectedTab) {
                    0 -> OverviewScreen(summary, status)
                    1 -> SimpleLabsScreen(summary?.labTrends.orEmpty())
                    2 -> SimpleCulturesScreen(summary?.cultures.orEmpty())
                    3 -> SimpleReportsScreen(summary?.sourceReports.orEmpty(), issues, onRetryOne, onOpenPdf, onManualCorrection)
                    else -> IssuesScreen(issues, onRetryOne, onRetryAll, onLoginAgain)
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(webView: WebView, status: String, onContinue: () -> Unit, onLogoutOthers: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Login", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Enter your NIMS user ID, password and captcha below.")
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
        Text(status, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onContinue) { Text("Continue") }
            OutlinedButton(onClick = onLogoutOthers) { Text("Logout other sessions") }
        }
    }
}

@Composable
private fun CrScreen(cr: String, onChange: (String) -> Unit, status: String, onFetch: () -> Unit, onLoginAgain: () -> Unit, onCopyLogs: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(30.dp))
        Text("Patient results", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(value = cr, onValueChange = onChange, label = { Text("CR number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = onFetch, modifier = Modifier.fillMaxWidth()) { Text("Fetch results") }
        Text(status, style = MaterialTheme.typography.bodySmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedButton(onClick = onLoginAgain) { Text("Login again") } }
            item { OutlinedButton(onClick = onCopyLogs) { Text("Copy logs") } }
        }
    }
}

@Composable
private fun ProcessingScreen(done: Int, total: Int, status: String, failed: Int, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(status, fontWeight = FontWeight.Bold)
        Text(if (total > 0) "$done of $total reports" else "Preparing reports")
        if (failed > 0) OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) { Text("Retry failed ($failed)") }
    }
}

@Composable
private fun OverviewScreen(summary: UiSummary?, status: String) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Overview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { SimpleCard { Text(summary?.patientSnapshot?.identityLine?.ifBlank { "Patient details unavailable" } ?: "Patient details unavailable", fontWeight = FontWeight.Bold); Text(status) } }
        item { SimpleCard { Text("Cultures", fontWeight = FontWeight.Bold); Text("Positive ${summary?.positiveCultureCount ?: 0} · Pending ${summary?.pendingCultureCount ?: 0} · No growth ${summary?.noGrowthCultureCount ?: 0}") } }
        item { SimpleCard { Text("Reports", fontWeight = FontWeight.Bold); Text("Available ${summary?.parsedReportCount ?: 0} · Needs attention ${summary?.failedReportCount ?: 0}") } }
        summary?.abnormalLabTrends.orEmpty().take(8).forEach { row -> item { CompactLab(row) } }
    }
}

@Composable
private fun SimpleLabsScreen(rows: List<UiLabTrendRow>) {
    var panel by remember { mutableStateOf("All") }
    val filtered = rows.filter { panel == "All" || clinicalPanel(it.parameter) == panel }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Lab results", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(listOf("All", "Hemogram", "Renal", "Liver", "Inflammatory", "Molecular", "Other")) { value -> OutlinedButton(onClick = { panel = value }) { Text((if (panel == value) "✓ " else "") + value) } } } }
        if (filtered.isEmpty()) item { SimpleCard { Text("No results in this panel") } }
        items(filtered) { CompactLab(it) }
    }
}

@Composable
private fun CompactLab(row: UiLabTrendRow) {
    SimpleCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.parameter, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(row.latestValue, fontWeight = FontWeight.Bold, color = if (row.abnormality in setOf(Abnormality.HIGH, Abnormality.LOW, Abnormality.CRITICAL)) MaterialTheme.colorScheme.error else Color.Unspecified)
        }
        Text(row.latestDate, style = MaterialTheme.typography.bodySmall)
        if (row.previousValue != null) Text("Previous ${row.previousValue} · ${row.trendText}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SimpleCulturesScreen(rows: List<UiCultureRow>) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Cultures", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        items(rows) { row ->
            SimpleCard {
                Row { Text(row.organism.ifBlank { row.growth.ifBlank { row.status.replace('_', ' ') } }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Text(row.status.replace('_', ' '), fontWeight = FontWeight.Bold) }
                Text(listOf(row.site.ifBlank { row.specimen }, row.collectionDate, row.reportStage).filter(String::isNotBlank).joinToString(" · "))
                if (row.sensitivitySummary.isNotBlank()) Text(row.sensitivitySummary, style = MaterialTheme.typography.bodySmall)
                if (row.comment.isNotBlank()) Text(row.comment, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SimpleReportsScreen(reports: List<UiSourceReport>, issues: List<ReportIssue>, onRetry: (String) -> Unit, onOpen: (UiSourceReport) -> Unit, onCorrection: (String, String, String, String) -> Unit) {
    val issueById = issues.associateBy { it.reportId }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Reports", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        items(reports) { report ->
            var correcting by remember(report.sourceKey) { mutableStateOf(false) }
            var field by remember(report.sourceKey) { mutableStateOf("") }
            var value by remember(report.sourceKey) { mutableStateOf("") }
            var unit by remember(report.sourceKey) { mutableStateOf("") }
            val issue = issueById[report.sourceKey]
            SimpleCard(container = if (issue != null) Color(0xFFFFF2F0) else MaterialTheme.colorScheme.surfaceVariant) {
                Row { Column(Modifier.weight(1f)) { Text(report.reportName, fontWeight = FontWeight.Bold); Text(report.dateSent, style = MaterialTheme.typography.bodySmall) }; Text(if (issue != null) "Needs attention" else report.type.uppercase()) }
                Text(if (issue != null) issue.userMessage else when { report.results.isNotEmpty() -> "${report.results.size} results"; report.cultureCount > 0 -> "${report.cultureCount} culture observation(s)"; else -> "Report available" })
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { OutlinedButton(onClick = { onOpen(report) }) { Text("Open report") } }
                    if (issue?.retryable == true) item { Button(onClick = { onRetry(report.sourceKey) }) { Text("Retry") } }
                    if (issue != null) item { OutlinedButton(onClick = { correcting = !correcting }) { Text("Enter result") } }
                }
                if (correcting) {
                    OutlinedTextField(field, { field = it }, label = { Text("Test / field") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value, { value = it }, label = { Text("Value") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(unit, { unit = it }, label = { Text("Unit (optional)") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { onCorrection(report.sourceKey, field, value, unit); correcting = false }) { Text("Save clinician-entered result") }
                }
            }
        }
    }
}

@Composable
private fun IssuesScreen(issues: List<ReportIssue>, onRetry: (String) -> Unit, onRetryAll: () -> Unit, onLoginAgain: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Issues", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); Button(onClick = onRetryAll) { Text("Retry all") } } }
        if (issues.isEmpty()) item { SimpleCard { Text("No unresolved issues") } }
        items(issues) { issue -> SimpleCard { Text(issue.reportName, fontWeight = FontWeight.Bold); Text(issue.userMessage); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { if (issue.retryable) Button(onClick = { onRetry(issue.reportId) }) { Text("Retry") }; if (issue.kind.name == "SESSION_EXPIRED") OutlinedButton(onClick = onLoginAgain) { Text("Login again") } } } }
    }
}

@Composable
private fun SimpleCard(container: Color = MaterialTheme.colorScheme.surfaceVariant, content: @Composable Column.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = container)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

private fun clinicalPanel(parameter: String): String {
    val p = parameter.uppercase()
    return when {
        listOf("HEMOGLOBIN", "WBC", "TLC", "PLATELET").any(p::contains) -> "Hemogram"
        listOf("CREATININE", "UREA", "SODIUM", "POTASSIUM", "CHLORIDE", "GLUCOSE").any(p::contains) -> "Renal"
        listOf("BILIRUBIN", "AST", "ALT", "SGOT", "SGPT", "ALP", "ALBUMIN", "PROTEIN").any(p::contains) -> "Liver"
        listOf("CRP", "ESR", "PROCALCITONIN", "GALACTOMANNAN", "GLUCAN", "BDG").any(p::contains) -> "Inflammatory"
        listOf("PCR", "MOLECULAR", "CBNAAT", "GENEXPERT", "VIRAL LOAD").any(p::contains) -> "Molecular"
        else -> "Other"
    }
}

@Composable
private fun ExactPdfDialogV2(state: ExactPdfStateV2, onClose: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
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
                if (state.pageCount > 1) Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(onClick = onPrevious, enabled = state.pageIndex > 0) { Text("Previous") }
                    OutlinedButton(onClick = onNext, enabled = state.pageIndex + 1 < state.pageCount) { Text("Next") }
                }
            }
        }
    }
}
