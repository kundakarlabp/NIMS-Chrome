package org.kundakarlab.nimsresults

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.UUID
import java.time.LocalDate
import java.util.concurrent.Executors

private data class RecentEntry(
    val key: String,
    val title: String,
    val date: LocalDate,
    val dateLabel: String,
    val report: ReportRef? = null,
    val rows: List<LabResult> = emptyList()
)

class MainActivity : ComponentActivity(), NimsEngine.Listener {
    private lateinit var root: FrameLayout
    private lateinit var engine: NimsEngine
    private lateinit var vault: SessionVault
    private lateinit var patientShelf: PatientShelf
    private val snapshotCache by lazy { ClinicalSnapshotCache(this) }
    private var shelfEntries by mutableStateOf<List<PatientBookmark>>(emptyList())
    private var shelfOpen by mutableStateOf(false)
    private val reportRepository by lazy { ReportRepository() }
    private val worker = Executors.newSingleThreadExecutor()
    private val inventoryWorker = Executors.newSingleThreadExecutor()
    private val diagnosticsWorker = Executors.newSingleThreadExecutor()
     @Volatile private var pdfIndexSelection = ""
    private var pdfInspectedCount by mutableStateOf(0)
    private var pdfConfirmedCount by mutableStateOf(0)
    private var pdfTotalCount by mutableStateOf(0)
    private val handler = Handler(Looper.getMainLooper())

    private var sessionState by mutableStateOf(SessionState.UNKNOWN)
    private var loadStage by mutableStateOf(LoadStage.IDLE)
    private var statusText by mutableStateOf("Checking saved NIMS session…")
    private var bundle by mutableStateOf<ClinicalBundle?>(null)
    private var selectedTab by mutableStateOf(ResultsTab.OVERVIEW)
    private var reportQueryPrefill by mutableStateOf("")
    private var allResultsSourceFilter by mutableStateOf("All")
    private var reportCategoryPrefill by mutableStateOf("All")
    private var reportTitleEpoch by mutableStateOf(0)
    private var microFilter by mutableStateOf("All")
    private var crInput by mutableStateOf("")
    private var appLockEnabled by mutableStateOf(false)
    private var appUnlocked by mutableStateOf(true)
    private var leftAt = 0L
    private var unlockPromptActive = false
    @Volatile private var activeRequestId = ""
    @Volatile private var activeCr = ""
    @Volatile private var pendingPatient = PatientInfo()
    @Volatile private var patientHeaderRequestId = ""
    @Volatile private var selectedBulkCounts: List<Int>? = null
    private var pendingCrAfterLogin = ""
    private var verifiedRestReports: List<ReportRef> = emptyList()
    @Volatile private var accumulatedBulk: ClinicalBundle? = null
    @Volatile private var bulkBatchCount = 0
    private var indexRequested = false
    private var historyStatus by mutableStateOf("")
    private var historyMode by mutableStateOf("unknown")
    private var apiStage by mutableStateOf("not_checked")
    private var apiReportCount by mutableStateOf(0)
    private var apiAttemptMs by mutableStateOf(0L)
    private var currentTimeout: Runnable? = null

    private val deviceUnlockLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            unlockPromptActive = false
            if (result.resultCode == RESULT_OK) {
                appUnlocked = true
                leftAt = 0L
            } else {
                finish()
            }
        }

    private val loginLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_FIRST_USER) {
            logout()
            statusText = "NIMS sign-out confirmed. The hospital server may still limit concurrent sessions."
            return@registerForActivityResult
        }
        if (result.resultCode != RESULT_OK) {
            if (loadStage == LoadStage.CHECKING_SESSION) loadStage = LoadStage.IDLE
            statusText = "Login was not completed."
            return@registerForActivityResult
        }
        sessionState = SessionState.CHECKING
        loadStage = LoadStage.CHECKING_SESSION
        statusText = "Verifying the saved NIMS session…"
        engine.reloadSession()
        engine.checkSession { state ->
            runOnUiThread {
                if (state == SessionState.CONNECTED) {
                    val cr = pendingCrAfterLogin
                    pendingCrAfterLogin = ""
                    if (cr.matches(Regex("""\d{15}"""))) {
                        crInput = cr
                        requestFetch()
                    }
                    else {
                        loadStage = LoadStage.IDLE
                        statusText = "NIMS connected."
                    }
                } else {
                    loadStage = LoadStage.IDLE
                    statusText = "NIMS login could not be verified."
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashTrace.install(this)
        ProcessHealth.onLaunch(this)
        vault = SessionVault(this)
        patientShelf = PatientShelf(this)
        shelfEntries = patientShelf.read()
        appLockEnabled = getSharedPreferences("nims_app_settings", Context.MODE_PRIVATE)
            .getBoolean("device_lock_enabled", false)
        appUnlocked = !appLockEnabled

        root = FrameLayout(this)
        setContentView(root)

        engine = NimsEngine(this, root, vault, this)

        val composeHost = androidx.compose.ui.platform.ComposeView(this).apply {
            setContent { if (appUnlocked) NimsApp() else LockedScreen() }
        }
        root.addView(
            composeHost,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // Restore encrypted results without network access; visibly mark as cached.
        worker.execute {
            val previous = snapshotCache.load()
            if (previous != null) runOnUiThread {
                if (bundle == null && activeRequestId.isBlank()) {
                    bundle = previous
                    crInput = previous.patient.crNo
                    selectedTab = ResultsTab.OVERVIEW
                    loadStage = LoadStage.READY
                    statusText = "Encrypted local snapshot · refresh before clinical use."
                }
            }
        }
        if (appLockEnabled) handler.post { requestDeviceUnlock() }
    }

    private fun toggleAppLock() {
        if (appLockEnabled && shelfEntries.isNotEmpty()) {
            Toast.makeText(this,
                "Saved patient CRs require device lock. Remove saved patients first.",
                Toast.LENGTH_LONG).show()
            return
        }
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!appLockEnabled && !keyguard.isDeviceSecure) {
            Toast.makeText(this, "Set a device PIN or password before enabling app lock.", Toast.LENGTH_LONG).show()
            return
        }
        appLockEnabled = !appLockEnabled
        getSharedPreferences("nims_app_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("device_lock_enabled", appLockEnabled).apply()
    }

    private fun requestDeviceUnlock() {
        if (!appLockEnabled || unlockPromptActive) return
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            "Unlock NIMS Results", "Confirm your phone's PIN, pattern or password."
        )
        if (intent == null) {
            Toast.makeText(this, "Device credential unavailable.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        unlockPromptActive = true
        deviceUnlockLauncher.launch(intent)
    }

    override fun onResume() {
        super.onResume()
        // Refresh source-confirmed titles after a PDF is read locally.
        reportTitleEpoch++
    }

    override fun onStop() {
        ProcessHealth.checkpoint(this, loadStage.name, selectedTab.name,
            bundle?.results?.size ?: 0, bundle?.reports?.size ?: 0)
        if (appLockEnabled && appUnlocked && !unlockPromptActive) {
            leftAt = SystemClock.elapsedRealtime()
        }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (appLockEnabled && appUnlocked && leftAt > 0 &&
            SystemClock.elapsedRealtime() - leftAt >= 120_000) {
            appUnlocked = false
            leftAt = 0L
            handler.post { requestDeviceUnlock() }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ProcessHealth.onTrim(this, level)
        // Release optional parsed PDF text when Android reports pressure.
        // The signed originals remain accessible via the source inventory.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            ReportTitleCache.clear()
            ReportLinkResolver.clear()
            reportTitleEpoch++
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ProcessHealth.onTrim(this, 100)
        ReportTitleCache.clear()
        ReportLinkResolver.clear()
        reportTitleEpoch++
    }

    private fun updateShelf(operation: () -> List<PatientBookmark>) {
        runCatching { operation() }.onSuccess { shelfEntries = it }
            .onFailure {
                Toast.makeText(this, "Could not save patient shortcut securely.",
                    Toast.LENGTH_LONG).show()
            }
    }

    private fun rememberCrLocally(cr: String) {
        if (!PatientShelfRules.validCr(cr)) return
        val guard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!guard.isDeviceSecure) return  // no PHI history on an unlocked phone
        if (!appLockEnabled) {
            appLockEnabled = true
            getSharedPreferences("nims_app_settings", Context.MODE_PRIVATE)
                .edit().putBoolean("device_lock_enabled", true).apply()
            Toast.makeText(this, "Device PIN protection enabled for saved patient history.",
                Toast.LENGTH_LONG).show()
        }
        updateShelf { patientShelf.rememberCr(cr) }
    }

    private fun togglePatientList(cr: String, list: String) {
        val guard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!guard.isDeviceSecure) {
            Toast.makeText(this, "Set a device PIN before saving patient identifiers.",
                Toast.LENGTH_LONG).show()
            return
        }
        if (!appLockEnabled) {
            appLockEnabled = true
            getSharedPreferences("nims_app_settings", Context.MODE_PRIVATE)
                .edit().putBoolean("device_lock_enabled", true).apply()
        }
        val enabled = shelfEntries.firstOrNull { it.cr == cr }?.lists?.contains(list) != true
        updateShelf { patientShelf.setList(cr, list, enabled) }
    }

    private fun openSavedCr(cr: String) {
        if (!PatientShelfRules.validCr(cr)) return
        crInput = cr
        shelfOpen = false
        requestFetch()
    }

    private fun requestFetch() {
        val cr = crInput.filter(Char::isDigit)
        if (!cr.matches(Regex("""\d{15}"""))) {
            statusText = "Enter the 15-digit NIMS CR number."
            return
        }
        cancelTimeout()
        activeCr = cr
        rememberCrLocally(cr)
        shelfOpen = false
        activeRequestId = "native-" + UUID.randomUUID()
        val requestId = activeRequestId
        if (bundle?.patient?.crNo != cr) {
            ReportTitleCache.clear()
            ReportLinkResolver.clear()
            bundle = null
        }
        pendingPatient = PatientInfo(crNo = cr)
        patientHeaderRequestId = ""
        selectedBulkCounts = null
        verifiedRestReports = emptyList()
        accumulatedBulk = null
        bulkBatchCount = 0
        indexRequested = false
        historyStatus = ""
        historyMode = "single_bulk"
        pdfIndexSelection = ""
        pdfInspectedCount = 0
        pdfConfirmedCount = 0
        pdfTotalCount = 0
        apiStage = "structured_checking"
        loadStage = LoadStage.CHECKING_SESSION
        statusText = "Checking NIMS session for complete Custom Sheet retrieval…"
        if (sessionState == SessionState.CONNECTED) startStructuredFetch(cr, requestId)
        else engine.checkSession { state ->
            runOnUiThread {
                if (requestId != activeRequestId) return@runOnUiThread
                if (state == SessionState.CONNECTED) startStructuredFetch(cr, requestId)
                else if (state == SessionState.ERROR) {
                    loadStage = if (bundle?.results?.isNotEmpty() == true)
                        LoadStage.READY else LoadStage.ERROR
                    statusText = "Cannot contact the NIMS hospital website. " +
                        "Check Wi-Fi/mobile data or NIMS server DNS. Saved results stay available."
                } else {
                    loadStage = LoadStage.IDLE
                    pendingCrAfterLogin = cr
                    statusText = "NIMS login required for structured results."
                    openLogin()
                }
            }
        }
    }

    private fun startStructuredFetch(cr: String, requestId: String) {
        if (requestId != activeRequestId) return
        loadStage = LoadStage.OPENING_TRENDS
        statusText = "Structured API · capturing GETMETABOLICDATA…"
        engine.run(cr, requestId)
        val timeout = Runnable {
            if (requestId != activeRequestId ||
                loadStage in setOf(LoadStage.READY, LoadStage.ERROR, LoadStage.IDLE)) return@Runnable
            loadStage = if (verifiedRestReports.isNotEmpty()) LoadStage.READY else LoadStage.ERROR
            statusText = if (verifiedRestReports.isNotEmpty()) {
                "Structured API timed out. Verified REST reports remain available."
            } else {
                "NIMS structured API timed out. Retry when the HIS session is responsive."
            }
        }
        currentTimeout = timeout
        handler.postDelayed(timeout, 120_000L)
    }

    private fun refreshCurrent() {
        val cr = bundle?.patient?.crNo?.takeIf { it.matches(Regex("""\d{15}""")) } ?: crInput
        crInput = cr
        if (cr.matches(Regex("""\d{15}"""))) requestFetch()
    }

    private fun openLogin(manageSession: Boolean = false) {
        loginLauncher.launch(Intent(this, LoginActivity::class.java)
            .putExtra(LoginActivity.EXTRA_MANAGE_SESSION, manageSession))
    }

    private fun logout() {
        cancelTimeout()
        vault.clear()
        snapshotCache.clear()
        pdfIndexSelection = ""
        cacheDir.listFiles { file ->
            file.name.startsWith("nims-verified-") && file.name.endsWith(".pdf")
        }?.forEach { it.delete() }
        bundle = null
        verifiedRestReports = emptyList()
        accumulatedBulk = null
        bulkBatchCount = 0
        historyStatus = ""
        historyMode = "unknown"
        ReportTitleCache.clear()
        ReportLinkResolver.clear()
        apiReportCount = 0
        apiStage = "not_checked"
        activeRequestId = ""
        sessionState = SessionState.LOGIN_REQUIRED
        loadStage = LoadStage.IDLE
        statusText = "Logged out from NIMS on this app."
        engine.reloadSession()
    }

    private fun cancelTimeout() {
        currentTimeout?.let { handler.removeCallbacks(it) }
        currentTimeout = null
    }

    override fun onRendererGone(didCrash: Boolean) {
        handler.post {
            cancelTimeout()
            activeRequestId = ""
            patientHeaderRequestId = ""
            selectedBulkCounts = null
            DiagnosticEvents.record(if (didCrash) "WEBVIEW_RENDERER_CRASH" else "WEBVIEW_RENDERER_GONE")
            loadStage = if (bundle?.results?.isNotEmpty() == true) LoadStage.READY else LoadStage.ERROR
            statusText = if (bundle?.results?.isNotEmpty() == true)
                "NIMS browser renderer restarted. Loaded clinical results remain visible."
            else "NIMS browser renderer restarted. Retry the patient request."
            engine = NimsEngine(this, root, vault, this)
        }
    }

    override fun onSession(state: SessionState) {
        runOnUiThread {
            sessionState = state
            if (loadStage == LoadStage.IDLE || loadStage == LoadStage.CHECKING_SESSION) {
                statusText = when (state) {
                    SessionState.CONNECTED -> "NIMS connected. Session will be reused until the server expires it."
                    SessionState.LOGIN_REQUIRED -> "NIMS login required."
                    SessionState.EXPIRED -> "NIMS session expired. Log in again."
                    SessionState.CHECKING -> "Checking NIMS session…"
                    SessionState.ERROR -> "NIMS portal unreachable or session check failed. Use NIMS login to retry."
                    SessionState.UNKNOWN -> "NIMS session not checked yet."
                }
                if (loadStage == LoadStage.CHECKING_SESSION && state != SessionState.CHECKING) loadStage = LoadStage.IDLE
            }
        }
    }

    override fun onStage(requestId: String, stage: String) {
        if (stage.startsWith("bulk_selection:")) {
            val counts = stage.substringAfter(":").split("/").mapNotNull(String::toIntOrNull)
            selectedBulkCounts = counts.takeIf { it.size == 4 }
            return
        }
        if (requestId != activeRequestId) return
        runOnUiThread {
            if (stage == "bulk_mode_single") {
                historyMode = "single_bulk"
                historyStatus = "All available Custom Sheet tests and dates requested together."
                return@runOnUiThread
            }
            if (stage.startsWith("bulk_mode_staged:")) {
                historyMode = "staged"
                historyStatus = "Fetching the newest two-week results first; older dates will follow."
                return@runOnUiThread
            }
            if (stage == "bulk_partial_timeout") {
                historyStatus = "Older history fetch stopped after a server timeout; recent results remain available."
                return@runOnUiThread
            }
            if (stage.startsWith("fetching_older_results:")) {
                historyStatus = "Recent results ready · Fetching older dates (" +
                    stage.substringAfter(':') + ")…"
                return@runOnUiThread
            }
            if (stage == "bulk_complete") {
                historyStatus = "Available Custom Sheet dates loaded (" +
                    bulkBatchCount + " batch(es))."
                statusText = historyStatus
                return@runOnUiThread
            }
            when (stage) {
                "opening_trends" -> {
                    loadStage = LoadStage.OPENING_TRENDS
                    statusText = "Opening Investigation Trends…"
                }
                "loading_cr" -> {
                    loadStage = LoadStage.LOADING_CR
                    statusText = "Loading CR $activeCr…"
                }
                "preparing_custom_sheet" -> {
                    loadStage = LoadStage.CAPTURING_RESULTS
                    statusText = "Selecting all investigations and dates…"
                }
                "capturing_results" -> {
                    loadStage = LoadStage.CAPTURING_RESULTS
                    statusText = "Receiving structured NIMS results…"
                }
            }
        }
    }

    override fun onPatient(requestId: String, patient: PatientInfo) {
        if (requestId != activeRequestId) return
        if (patient.crNo != activeCr) return
        pendingPatient = patient
        patientHeaderRequestId = requestId
    }

    override fun onBulk(requestId: String, rawJson: String) {
        if (requestId != activeRequestId) return
        val cr = activeCr
        val patient = pendingPatient
        val selection = selectedBulkCounts
        // A request CR typed in the app is not proof the HIS actually displayed
        // that patient. Require the independently observed patient header.
        if (patientHeaderRequestId != requestId || patient.crNo != cr ||
            selection == null || selection.size != 4 ||
            selection[0] <= 0 || selection[0] != selection[1] ||
            selection[2] != selection[3]) {
            cancelTimeout()
            loadStage = LoadStage.ERROR
            statusText = "Patient identity or complete Custom Sheet selection not verified."
            return
        }
        worker.execute {
            try {
                if (requestId != activeRequestId) return@execute
                val parsed = BulkParser.parse(rawJson, cr, patient, emptyList())
                if (requestId != activeRequestId) return@execute
                runOnUiThread {
                    if (requestId != activeRequestId) return@runOnUiThread
                    cancelTimeout()
                    bundle = parsed
                    worker.execute { if (requestId == activeRequestId) snapshotCache.save(parsed) }
                    bulkBatchCount = 1
                    accumulatedBulk = null
                    crInput = cr
                    selectedTab = ResultsTab.OVERVIEW
                    loadStage = LoadStage.READY
                    apiStage = "structured_captured_not_reconciled"
                    historyStatus = "Complete Custom Sheet request accepted · " +
                        parsed.results.size + " populated result fields · Enquiry not reconciled"
                    statusText = historyStatus
                    if (parsed.patient.name.isNotBlank())
                        updateShelf { patientShelf.confirmedPatient(cr, parsed.patient.name) }
                    ProcessHealth.checkpoint(this@MainActivity, loadStage.name,
                        selectedTab.name, parsed.results.size, 0)
                    if (!indexRequested) {
                        indexRequested = true
                        loadSourceInventory(requestId, cr, emptyList())
                    }
                }
            } catch (_: Exception) {
                DiagnosticEvents.record("STRUCTURED_FAILURE")
                runOnUiThread {
                    if (requestId != activeRequestId) return@runOnUiThread
                    cancelTimeout()
                    loadStage = LoadStage.ERROR
                    apiStage = "structured_rejected"
                    statusText = "NIMS structured response could not be safely normalized."
                }
            }
        }
    }

    private fun loadSourceInventory(requestId: String, cr: String, seed: List<ReportRef>) {
        // A slow PDF index must not block new structured laboratory batches.
        inventoryWorker.execute {
            val inventory = try {
                reportRepository.load(cr, seed, restAlreadyAttempted = false)
            } catch (_: IdentityMismatchException) {
                DiagnosticEvents.record("PDF_IDENTITY_MISMATCH")
                runOnUiThread {
                    if (requestId == activeRequestId)
                        statusText = "Report identity mismatch; PDF links withheld."
                }
                return@execute
            } catch (_: Exception) {
                DiagnosticEvents.record("REPORT_INDEX_FAILED")
                null
            }
            runOnUiThread {
                if (requestId != activeRequestId || bundle?.patient?.crNo != cr)
                    return@runOnUiThread
                val sources = inventory?.reports ?: seed
                val current = bundle ?: return@runOnUiThread
                val withInventory = current.copy(reports = sources)
                bundle = withInventory
                worker.execute {
                    if (requestId == activeRequestId) snapshotCache.save(withInventory)
                }
                apiReportCount = sources.size
                if (sources.isNotEmpty()) historyStatus =
                    current.results.size.toString() + " structured fields · " +
                        sources.size + " NIMS source-index rows (separate identifiers)"
                if (sources.none { it.url.isNotBlank() }) {
                    ReportInventoryTrace.event("webview_attempt")
                    engine.fetchReportIndexHtml(cr, requestId)
                } else ReportInventoryTrace.event("inventory_only", sources.size)
            }
        }
    }

    override fun onReportIndexHtml(requestId: String, html: String, code: String) {
        if (requestId != activeRequestId) return
        if (code != "ok" || html.isBlank()) {
            ReportInventoryTrace.event("webview_unavailable")
            runOnUiThread {
                if (requestId == activeRequestId) statusText =
                    "Results ready · original PDF inventory unavailable (" + code + ")"
            }
            return
        }
        val cr = activeCr
        worker.execute {
            val reports = try {
                ReportPayloadParser.parseHtml(html, NimsUrls.CR_REPORTS, cr)
            } catch (_: IdentityMismatchException) {
                ReportInventoryTrace.event("webview_identity_mismatch")
                emptyList()
            } catch (_: Exception) {
                ReportInventoryTrace.event("webview_parse_error")
                emptyList()
            }
            if (reports.isEmpty()) ReportInventoryTrace.event("webview_empty")
            else ReportInventoryTrace.event("webview_verified_rows", reports.size)
            runOnUiThread {
                if (requestId != activeRequestId || bundle?.patient?.crNo != cr)
                    return@runOnUiThread
                if (reports.isNotEmpty()) {
                    val prior = bundle ?: return@runOnUiThread
                    val reconciled = (reports + prior.reports).distinctBy {
                        if (it.url.isNotBlank()) listOf("pdf", it.url,
                            it.reportNumber, it.reportStage, it.labNumber,
                            it.sampleNumber).joinToString("|")
                        else "index|" + it.id + "|" + it.title + "|" + it.date
                    }
                    val withInventory = prior.copy(reports = reconciled)
                    bundle = withInventory
                    worker.execute {
                        if (requestId == activeRequestId) snapshotCache.save(withInventory)
                    }
                    apiReportCount = reconciled.size
                    historyStatus = prior.results.size.toString() +
                        " structured fields · " + reconciled.size +
                        " source-index rows (not yet event-linked)"
                    statusText = prior.results.size.toString() +
                        " structured values · " + reconciled.count { it.url.isNotBlank() } +
                        " PDF links (" + reconciled.size + " index entries)"
                    startPdfIndex(requestId, cr, reconciled)
                } else statusText =
                    "Results ready · unable to reconcile original PDF inventory"
            }
        }
    }

    override fun onError(requestId: String, message: String) {
        if (requestId.isNotBlank() && requestId != activeRequestId) return
        cancelTimeout()
        runOnUiThread {
            val authLike = Regex("(?i)auth|login|session").containsMatchIn(message)
            DiagnosticEvents.record(if (authLike) "AUTH_REQUIRED" else "STRUCTURED_FAILURE")
            if (bulkBatchCount > 0 && bundle?.results?.isNotEmpty() == true) {
                loadStage = LoadStage.READY
                historyStatus = "Older history interrupted; " + bulkBatchCount +
                    " accepted batch(es) remain available."
                if (authLike) {
                    sessionState = SessionState.LOGIN_REQUIRED
                    pendingCrAfterLogin = activeCr
                }
                statusText = historyStatus
                return@runOnUiThread
            }
            if (verifiedRestReports.isNotEmpty()) {
                loadStage = LoadStage.READY
                if (authLike) pendingCrAfterLogin = activeCr
                statusText = if (authLike) {
                    "REST reports available. Log in for structured laboratory values."
                } else {
                    "REST reports available; structured retrieval failed. " +
                        message.replace(Regex("""\b\d{15}\b"""), "[CR redacted]").take(110)
                }
                return@runOnUiThread
            }
            if (authLike) {
                sessionState = SessionState.LOGIN_REQUIRED
                pendingCrAfterLogin = activeCr
                loadStage = LoadStage.IDLE
                statusText = "NIMS session needs login. Complete CAPTCHA once; this CR will resume."
                openLogin()
            } else {
                loadStage = LoadStage.ERROR
                statusText = message.replace(Regex("""\b\d{15}\b"""), "[CR redacted]").take(180)
            }
        }
    }

    private fun diagnosticsText(includeAudit: Boolean = true): String {
        val current = bundle
        return buildString {
            appendLine("NIMS Results diagnostics · entire app")
            appendLine("diagnostics.format=v3")
            appendLine("app.version=${BuildConfig.VERSION_NAME}")
            appendLine("app.sourceCommit=${BuildConfig.SOURCE_COMMIT}")
            appendLine("app.lastCrash=" + CrashTrace.last(this@MainActivity))
            appendLine(ProcessHealth.snapshot(this@MainActivity))
            appendLine("app.sessionState=$sessionState")
            appendLine("app.loadStage=$loadStage")
            appendLine("app.crInputLength=${crInput.length}")
            appendLine("app.activeCrPresent=${activeCr.isNotBlank()}")
            appendLine("app.pendingCrAfterLogin=${pendingCrAfterLogin.isNotBlank()}")
            appendLine("app.hasBundle=${current != null}")
            appendLine("app.resultCount=${current?.results?.size ?: 0}")
            appendLine("app.reportCount=${current?.reports?.size ?: 0}")
            appendLine("api.stage=$apiStage")
            appendLine("api.verifiedReportCount=$apiReportCount")
            appendLine("api.restElapsedMs=$apiAttemptMs")
            appendLine("bulk.acceptedBatches=$bulkBatchCount")
            appendLine("bulk.fetchMode=$historyMode")
            appendLine("bulk.historyStatus=" + historyStatus.take(100))
            appendLine("report_index.events=" + ReportInventoryTrace.snapshot())
            appendLine("pdf.performance=" + PdfPerformanceTrace.describe())
            appendLine("engine.sessionProbeAvailable=" + (::engine.isInitialized))
            if (::engine.isInitialized) {
                // Engine details are restricted to boolean lifecycle evidence.
                val safe = engine.safeDiagnostics().lineSequence()
                    .filterNot { it.startsWith("engine.currentPath=") }
                    .joinToString("\n")
                appendLine(safe)
            }
            if (includeAudit) append(AppDiagnostics.summarize(current))
        }
    }

    private fun copyDiagnostics() {
        // WebView/Activity diagnostics must be read on the UI thread; full
        // investigation-to-PDF analysis must NOT block it.
        val header = diagnosticsText(includeAudit = false)
        val current = bundle
        Toast.makeText(this, "Compiling redacted diagnostics…", Toast.LENGTH_SHORT).show()
        diagnosticsWorker.execute {
            val audit = runCatching { AppDiagnostics.summarize(current) }
                .getOrDefault("audit.error=analysis_unavailable\n")
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("NIMS diagnostics", header + audit)
                )
                Toast.makeText(this, "App-wide diagnostics copied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun browseSourceReports(test: String) {
        // NIMS often uses accession or generic department titles in its index.
        // Do not apply an exact-name search that hides the actual source PDF.
        reportQueryPrefill = ""

        reportCategoryPrefill = when {
            Regex("""(?i)blood\s*group|antibody\s*screen|transfusion|cross\s*match""")
                .containsMatchIn(test) -> "Blood bank"
            ClinicalReportRenderer.isMicrobiologyInvestigation(test) -> "Microbiology"
            else -> "All"
        }
        allResultsSourceFilter = reportCategoryPrefill
        selectedTab = ResultsTab.ALL
    }

    private fun startPdfIndex(requestId: String, cr: String, reports: List<ReportRef>) {
        // A report list can contain hundreds of multi-page PDFs. Fetching
        // and parsing them automatically competes with clinical navigation
        // and can trigger Android low-memory process termination. Keep the
        // verified inventory; inspect PDF text only when its source is opened.
        pdfIndexSelection = requestId
        pdfInspectedCount = 0
        pdfConfirmedCount = 0
        pdfTotalCount = reports.map { it.url }.filter(String::isNotBlank).distinct().size
    }

    private fun openReport(url: String) {
        if (ReportPayloadParser.safeReportUrl(url).isBlank()) return
        startActivity(Intent(this, ReportActivity::class.java)
            .putExtra(ReportActivity.EXTRA_URL, url)
            .putExtra(ReportActivity.EXTRA_EXPECTED_CR, bundle?.patient?.crNo.orEmpty()))
    }

    @Composable
    private fun LockedScreen() {
        MaterialTheme {
            Column(
                modifier = Modifier.fillMaxSize().background(Color(0xFF0C2D4C)).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("NIMS Results", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Text("Clinical results are locked on this device.", color = Color.White)
                Spacer(Modifier.height(20.dp))
                Button(onClick = { requestDeviceUnlock() }) { Text("Unlock with device PIN") }
            }
        }
    }

    @Composable
    private fun NimsApp() {
        val colors = lightColorScheme(
            primary = Color(0xFF0B5CAB),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD8E9FF),
            surface = Color(0xFFF7F9FC),
            surfaceVariant = Color(0xFFEAF0F7),
            error = Color(0xFFB3261E)
        )
        val current = bundle
        var toolsOpen by remember(current?.patient?.crNo) { mutableStateOf(false) }
        val scrollByTab = remember(current?.patient?.crNo) {
            ResultsTab.entries.associateWith { LazyListState() }
        }
        MaterialTheme(colorScheme = colors) {
            Scaffold(
                topBar = { AppHeader() },
                containerColor = MaterialTheme.colorScheme.surface
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .navigationBarsPadding()
                ) {
                    // Keep CR entry and connection tools accessible without permanently
                    // occupying the clinical results viewport.
                    if (current == null || toolsOpen) {
                        ConnectionPanel()
                        CrPanel()
                    }
                    if (loadStage !in setOf(LoadStage.IDLE, LoadStage.READY, LoadStage.ERROR)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(statusText, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                            color = Color(0xFF486581), fontSize = 11.sp, maxLines = 2)
                    } else if (loadStage == LoadStage.ERROR) {
                        Text(statusText, modifier = Modifier.padding(horizontal = 12.dp, vertical = 3.dp),
                            color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    if (shelfOpen) {
                        PatientShelfScreen()
                    } else if (current == null) {
                        EmptyHome()
                    } else {
                        PatientStrip(current, onToggleTools = { toolsOpen = !toolsOpen })
                        NativeTabs()
                        if (historyStatus.isNotBlank()) {
                            Text(historyStatus,
                                modifier = Modifier.fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 2.dp),
                                fontSize = 10.sp, maxLines = 1,
                                color = Color(0xFF486581))
                        }
                        if (current.source == "nims_rest_report_list" && current.results.isEmpty()) {
                            Surface(color = Color(0xFFEAF3FF)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "REST report index verified · full labs need structured NIMS session",
                                        modifier = Modifier.weight(1f),
                                        fontSize = 11.sp,
                                        color = Color(0xFF24496A)
                                    )
                                    if (loadStage == LoadStage.READY) {
                                        TextButton(onClick = { openLogin() }) {
                                            Text("Load labs", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                        Box(modifier = Modifier.fillMaxSize()) {
                            when (selectedTab) {
                                ResultsTab.OVERVIEW -> OverviewScreen(current, scrollByTab.getValue(ResultsTab.OVERVIEW))
                                ResultsTab.RECENT -> RecentScreen(current, scrollByTab.getValue(ResultsTab.RECENT))
                                ResultsTab.ID -> IdFocusScreen(current, scrollByTab.getValue(ResultsTab.ID))
                                ResultsTab.LABS -> LabsScreen(current, scrollByTab.getValue(ResultsTab.LABS))
                                ResultsTab.MICROBIOLOGY -> MicroScreen(current, scrollByTab.getValue(ResultsTab.MICROBIOLOGY))
                                ResultsTab.TRENDS -> TrendsScreen(current, scrollByTab.getValue(ResultsTab.TRENDS))
                                ResultsTab.ALL -> AllResultsScreen(current, scrollByTab.getValue(ResultsTab.ALL))
                            }
                            DraggableResultsScrollbar(
                                scrollByTab.getValue(selectedTab),
                                Modifier.align(Alignment.CenterEnd)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AppHeader() {
        Surface(
            color = Color(0xFF0C2D4C),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(color = Color.White, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(40.dp)) {
                    Image(
                        painter = painterResource(id = R.drawable.nims_logo),
                        contentDescription = "NIMS official logo",
                        modifier = Modifier.size(40.dp)
                    )
                }
                Spacer(Modifier.width(7.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("NIMS Results", color = Color.White,
                        fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("Nizam's Institute of Medical Sciences · Punjagutta",
                        color = Color(0xFFBDCAD6), fontSize = 9.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                SessionBadge(sessionState)
            }
        }
    }

    @Composable
    private fun SessionBadge(state: SessionState) {
        val (label, color) = when (state) {
            SessionState.CONNECTED -> "Connected" to Color(0xFF2E7D32)
            SessionState.CHECKING -> "Checking" to Color(0xFFEF6C00)
            SessionState.EXPIRED -> "Expired" to Color(0xFFC62828)
            SessionState.LOGIN_REQUIRED -> "Login" to Color(0xFFC62828)
            SessionState.ERROR -> "Error" to Color(0xFFC62828)
            SessionState.UNKNOWN -> "Session" to Color(0xFF607D8B)
        }
        Surface(color = color.copy(alpha = 0.18f), shape = RoundedCornerShape(20.dp)) {
            Text(label, color = if (state == SessionState.CONNECTED) Color(0xFFB9F6CA) else Color.White, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun ConnectionPanel() {
        Column(modifier = Modifier.fillMaxWidth()
            .background(Color.White).padding(horizontal = 12.dp, vertical = 5.dp)) {
            Text(statusText, fontSize = 11.sp, color = Color(0xFF486581),
                maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Row(modifier = Modifier.fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (sessionState != SessionState.CONNECTED) {
                    TextButton(onClick = { openLogin() }) { Text("NIMS login", fontSize = 12.sp) }
                } else {
                    TextButton(onClick = { engine.checkSession() }) { Text("Check", fontSize = 12.sp) }
                    TextButton(onClick = { openLogin(manageSession = true) }) {
                        Text("Session / Logout", fontSize = 12.sp)
                    }
                    TextButton(onClick = { logout() }) { Text("Clear local", fontSize = 12.sp) }
                }
                TextButton(onClick = { toggleAppLock() }) {
                    Text(if (appLockEnabled) "Lock On" else "Device lock", fontSize = 12.sp)
                }
                TextButton(onClick = { copyDiagnostics() }) {
                    Text("Copy app diagnostics", fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    private fun CrPanel() {
        var localCr by remember(crInput) { mutableStateOf(crInput) }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("CR NUMBER", fontSize = 11.sp, color = Color(0xFF66788A), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val compact = maxWidth < 560.dp
                    if (compact) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = localCr,
                                onValueChange = {
                                    val next = it.filter(Char::isDigit).take(15)
                                    localCr = next
                                    crInput = next
                                },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("15-digit CR number") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    crInput = localCr
                                    requestFetch()
                                },
                                enabled = localCr.length == 15 && loadStage in setOf(LoadStage.IDLE, LoadStage.READY, LoadStage.ERROR),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (loadStage !in setOf(LoadStage.IDLE, LoadStage.READY, LoadStage.ERROR)) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(if (bundle?.patient?.crNo == localCr) "Refresh results" else "Get results")
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = localCr,
                                onValueChange = {
                                    val next = it.filter(Char::isDigit).take(15)
                                    localCr = next
                                    crInput = next
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = { Text("15-digit CR number") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    crInput = localCr
                                    requestFetch()
                                },
                                enabled = localCr.length == 15 && loadStage in setOf(LoadStage.IDLE, LoadStage.READY, LoadStage.ERROR)
                            ) {
                                Text(if (bundle?.patient?.crNo == localCr) "Refresh" else "Get results")
                            }
                        }
                    }
                }
                val suggested = shelfEntries.filter {
                    localCr.isBlank() || it.cr.startsWith(localCr)
                }.take(4)
                if (suggested.isNotEmpty()) {
                    Text("Previously used CRs", fontSize = 10.sp,
                        color = Color(0xFF66788A))
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        suggested.forEach { patient ->
                            TextButton(onClick = { openSavedCr(patient.cr) }) {
                                Text(patient.name.ifBlank { patient.cr }.take(24),
                                    fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
                TextButton(onClick = { shelfOpen = !shelfOpen },
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (shelfOpen) "Close saved patients"
                         else "Recent CRs / Favorites / Follow-up / Research")
                }
            }
        }
    }

    @Composable
    private fun EmptyHome() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(22.dp))
            Text("Enter a 15-digit CR number above.", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Color(0xFF23384D))
            Spacer(Modifier.height(8.dp))
            Text(
                "Login is requested only when the NIMS server session is absent or expired. The CR field remains native and usable even while NIMS pages load in the background.",
                color = Color(0xFF66788A),
                fontSize = 13.sp
            )
        }
    }

    @Composable
    private fun PatientShelfScreen() {
        var filter by remember { mutableStateOf("Recent") }
        var editingCr by remember { mutableStateOf("") }
        var draftNote by remember { mutableStateOf("") }
        val shown = shelfEntries.filter { row ->
            filter == "Recent" || filter in row.lists
        }.sortedByDescending { it.lastUsed }
        LazyColumn(modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Saved patients", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { shelfOpen = false }) { Text("Close") }
                }
                Text("Encrypted on this phone · no laboratory data saved · unlock required on app return",
                    fontSize = 11.sp, color = Color(0xFF66788A))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    (listOf("Recent") + PatientShelfRules.LISTS).forEach { option ->
                        FilterChip(selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(option, fontSize = 11.sp) })
                    }
                }
            }
            if (shown.isEmpty()) {
                item { Text("No patients in this list yet. Enter a CR number or save a patient from the result header.",
                    color = Color(0xFF66788A), fontSize = 12.sp) }
            }
            items(shown, key = { it.cr }) { person ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(person.name.ifBlank { "CR " + person.cr },
                                    fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(person.cr, color = Color(0xFF66788A), fontSize = 11.sp)
                            }
                            Button(onClick = { openSavedCr(person.cr) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                                Text("Open", fontSize = 12.sp)
                            }
                        }
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            PatientShelfRules.LISTS.forEach { list ->
                                FilterChip(selected = list in person.lists,
                                    onClick = { togglePatientList(person.cr, list) },
                                    label = { Text(list, fontSize = 10.sp) })
                            }
                        }
                        if (person.note.isNotBlank()) {
                            Text(person.note, fontSize = 11.sp, color = Color(0xFF486581),
                                maxLines = 3)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                editingCr = person.cr
                                draftNote = person.note
                            }) { Text("Edit note", fontSize = 11.sp) }
                            TextButton(onClick = {
                                updateShelf { patientShelf.remove(person.cr) }
                            }) { Text("Remove", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
            if (filter == "Recent" && shelfEntries.any { it.lists.isEmpty() }) {
                item {
                    TextButton(onClick = {
                        updateShelf { patientShelf.clearRecent() }
                    }) { Text("Clear unsaved recent CRs", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (editingCr.isNotBlank()) {
            AlertDialog(
                onDismissRequest = { editingCr = "" },
                title = { Text("Private patient note") },
                text = {
                    OutlinedTextField(value = draftNote,
                        onValueChange = { draftNote = it.take(180) },
                        minLines = 2, maxLines = 4,
                        label = { Text("Follow-up note (optional)") })
                },
                confirmButton = {
                    TextButton(onClick = {
                        val cr = editingCr
                        updateShelf { patientShelf.setNote(cr, draftNote) }
                        editingCr = ""
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { editingCr = "" }) { Text("Cancel") }
                }
            )
        }
    }

    @Composable
    private fun PatientStrip(current: ClinicalBundle, onToggleTools: () -> Unit) {
        Surface(color = Color(0xFFEFF6FF)) {
            Column(modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(current.patient.name.ifBlank { "Patient · identity pending" },
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        color = Color(0xFF102A43), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("CR " + current.patient.crNo, fontSize = 10.sp,
                        color = Color(0xFF486581), maxLines = 1)
                }
                Row(modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically) {
                    val demographics = listOf(current.patient.age,
                        current.patient.sex.replace(Regex("(?i)age\\s*:.*"), "").trim())
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    Text(demographics,
                        color = Color(0xFF486581), fontSize = 11.sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        togglePatientList(current.patient.crNo, "Favorites")
                    }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                        val favorite = shelfEntries.firstOrNull { it.cr == current.patient.crNo }
                            ?.lists?.contains("Favorites") == true
                        Text(if (favorite) "★" else "☆", fontSize = 20.sp)
                    }
                    TextButton(onClick = { shelfOpen = true },
                        contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) {
                        Text("Patients", fontSize = 11.sp)
                    }
                    TextButton(onClick = { copyDiagnostics() },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                        Text("Diagnostics", fontSize = 10.sp)
                    }
                    TextButton(onClick = { refreshCurrent() },
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp)) {
                        Text("↻", fontSize = 19.sp)
                    }
                    TextButton(onClick = onToggleTools,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                        Text("CR ▾", fontSize = 11.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun NativeTabs() {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ResultsTab.entries.forEach { tab ->
                FilterChip(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    label = { Text(tabLabel(tab)) }
                )
            }
        }
    }


    @Composable
    private fun RecentScreen(current: ClinicalBundle, scroll: LazyListState) {
        // No PDF-to-result matching. Device-local seven-day selection.
        val today = LocalDate.now()
        var filter by remember(current.patient.crNo) { mutableStateOf("All") }
        var expandedKey by remember(current.patient.crNo) { mutableStateOf("") }
        val recent = remember(current.results, current.reports, today, reportTitleEpoch) {
            val group = current.results.groupBy {
                Triple(it.test, it.date, it.labNumber + "|" + it.sampleNumber + "|" + it.reportStage)
            }
            val covered = mutableSetOf<String>()
            val structured = group.mapNotNull { (identity, rows) ->
                val (date, type) = RecentReportDates.dateFor(rows)
                if (!RecentReportDates.isLastWeek(date, today)) null
                else {
                    val exact: ReportRef? = null // no automatic result-to-PDF linking
                    if (exact != null) covered.add(exact.url)
                    RecentEntry(
                        key = "structured|" + identity.toString(),
                        title = identity.first.ifBlank { "Unlabelled investigation" },
                        date = date!!,
                        dateLabel = type,
                        report = exact,
                        rows = rows
                    )
                }
            }
            val inventory = current.reports.mapNotNull { report ->
                val (date, type) = RecentReportDates.dateFor(report)
                if (!RecentReportDates.isLastWeek(date, today) ||
                    report.url in covered) null
                else RecentEntry(
                    key = "source|" + report.id + "|" + report.url,
                    title = ReportTitleCache.get(report.url) ?: report.title,
                    date = date!!,
                    dateLabel = type,
                    report = report
                )
            }
            (structured + inventory).sortedWith(
                compareByDescending<RecentEntry> { it.date }
                    .thenBy { it.title }
            )
        }
        val shown = remember(recent, filter) {
            recent.filter {
                when(filter) {
                    "Source PDFs" -> it.rows.isEmpty()
                    "Results" -> it.rows.isNotEmpty()
                    "Microbiology" ->
                        ResultSections.of(it.title) == ResultSection.MICROBIOLOGY
                    else -> true
                }
            }
        }
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Reports · last 7 days", fontWeight = FontWeight.Bold,
                    fontSize = 18.sp, color = Color(0xFF102A43))
                Text(today.minusDays(6).toString() + " to " + today.toString() +
                    " (device-local dates, inclusive)",
                    fontSize = 11.sp, color = Color(0xFF66788A))
                Text("Based on lab reported date when supplied; otherwise the " +
                    "source-index/result date is explicitly labelled. " +
                    "Opening or fetching an older report does not make it recent.",
                    fontSize = 10.sp, color = Color(0xFF66788A))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("All", "Source PDFs", "Results", "Microbiology").forEach { name ->
                        FilterChip(selected = filter == name,
                            onClick = { filter = name },
                            label = { Text(name, fontSize = 11.sp) })
                    }
                }
                Text(shown.size.toString() + " entries · " +
                    recent.count { it.rows.isNotEmpty() } + " structured groups · " +
                    recent.count { it.rows.isEmpty() } + " additional source-index entries",
                    fontSize = 11.sp, color = Color(0xFF486581))
            }
            if (shown.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Text("No investigations dated within the last seven calendar days " +
                            "match this filter. Older reports remain available in Reports " +
                            "and All results.",
                            modifier = Modifier.padding(14.dp),
                            color = Color(0xFF66788A), fontSize = 13.sp)
                    }
                }
            }
            items(shown, key = { it.key }) { entry ->
                val expanded = expandedKey == entry.key
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(11.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable {
                                expandedKey = if (expanded) "" else entry.key
                            }) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.title, fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp, color = Color(0xFF102A43))
                                Text(entry.dateLabel + ": " + entry.date.toString() +
                                    " · " + if (entry.rows.isNotEmpty())
                                        "Structured result" else "Source report index",
                                    fontSize = 10.sp, color = Color(0xFF66788A))
                            }
                            Text(if (expanded) "Collapse ↑" else "Expand ↓",
                                fontSize = 11.sp, color = Color(0xFF0B5CAB))
                        }
                        if (expanded) {
                            if (entry.rows.isNotEmpty()) {
                                val display = ClinicalReportRenderer.preferredRows(entry.rows)
                                if (display.isEmpty()) Text(
                                    "Only unclassified or technical source fields; verify original PDF.",
                                    fontSize = 11.sp, color = Color(0xFF875B22))
                                display.forEach { row ->
                                    Text(row.parameter.ifBlank { "Result" },
                                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    Text(row.valueText +
                                        if (row.unit.isNotBlank()) " " + row.unit else "",
                                        fontSize = 12.sp)
                                }
                            } else Text("Report-index entry; open the source PDF to " +
                                "read its complete signed content.", fontSize = 11.sp)
                            entry.report?.let { source ->
                                if (source.url.isNotBlank()) {
                                    TextButton(onClick = { openReport(source.url) }) {
                                        Text("Open source PDF", fontSize = 12.sp)
                                    }
                                }
                            }
                            if (entry.report == null) {
                                TextButton(onClick = { browseSourceReports(entry.title) }) {
                                    Text("Browse NIMS source index", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun OverviewScreen(current: ClinicalBundle, scroll: LazyListState) {
        val eligible = remember(current.results) {
            current.results.filter { ClinicalResultRules.isTrendEligible(it) }
        }
        val wanted = listOf(
            "Hemoglobin" to listOf("hemoglobin", "haemoglobin", "hemoglobinresult", "hb"),
            "WBC / TLC" to listOf("wbc", "tlc", "totalleukocytecount"),
            "Platelets" to listOf("platelet", "plateletcount"),
            "Creatinine" to listOf("creatinine", "serumcreatinine"),
            "CRP" to listOf("crp", "creactiveprotein"),
            "Procalcitonin" to listOf("procalcitonin", "pct"),
            "Sodium" to listOf("sodium", "serumsodium"),
            "Potassium" to listOf("potassium", "serumpotassium")
        )
        val latestCards = remember(eligible) {
            wanted.mapNotNull { (label, aliases) ->
                eligible.filter { row ->
                    val key = normalizeKey(row.parameter)
                    aliases.any { alias -> key == alias }
                }.maxByOrNull { BulkParser.dateKey(it.date) }?.let { label to it }
            }
        }
        val cultures = remember(current.results) {
            current.results.filter { isMicro(it) }.groupBy { it.test to it.date }
                .toList().sortedByDescending { BulkParser.dateKey(it.first.second) }
        }
        val positives = remember(cultures) {
            cultures.filter { CultureReview.status(it.second) == CultureStatus.POSITIVE }
        }
        val latestDate = remember(current.results) {
            current.results.maxByOrNull { BulkParser.dateKey(it.date) }?.date.orEmpty()
        }
        val recentLab = remember(eligible) {
            eligible.sortedByDescending { BulkParser.dateKey(it.date) }
                .distinctBy { normalizeKey(it.parameter) + "|" + it.unit }.take(12)
        }
        val flagged = remember(eligible) {
            eligible.filter { row ->
                val flag = row.abnormal.lowercase()
                flag == "h" || flag == "l" || "high" in flag ||
                    "low" in flag || "critical" in flag
            }.sortedByDescending { BulkParser.dateKey(it.date) }
                .distinctBy { normalizeKey(it.parameter) }.take(6)
        }
        LazyColumn(state = scroll,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 10.dp, top = 10.dp, end = 30.dp, bottom = 15.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            item {
                Text("Clinical review", fontWeight = FontWeight.Bold,
                    fontSize = 17.sp, color = Color(0xFF102A43))
                Text("Latest structured result: " + latestDate +
                    " · " + current.results.size + " source fields · " +
                    current.reports.size + " report-index entries",
                    fontSize = 11.sp, color = Color(0xFF66788A))
                Text("Original signed PDFs are accessible through All results.",
                    fontSize = 10.sp, color = Color(0xFF829AB1))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(modifier = Modifier.weight(1f),
                        onClick = { microFilter = "Positive"; selectedTab = ResultsTab.MICROBIOLOGY }) {
                        Text("Positive cultures (" + positives.size + ")", fontSize = 11.sp)
                    }
                    OutlinedButton(modifier = Modifier.weight(1f),
                        onClick = { allResultsSourceFilter = "Labs"; selectedTab = ResultsTab.ALL }) {
                        Text("Lab timeline", fontSize = 11.sp)
                    }
                }
            }
            if (flagged.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(10.dp)) {
                            Text("Reported abnormal flags · latest per parameter",
                                fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text("Flags are shown only when supplied by NIMS; absent flags are not interpreted.",
                                fontSize = 10.sp, color = Color(0xFF66788A))
                            flagged.forEach { row ->
                                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                    Text(row.parameter, modifier = Modifier.weight(1f),
                                        fontSize = 12.sp, maxLines = 1)
                                    Text(row.valueText + " " + row.unit + " · " + row.date,
                                        fontSize = 11.sp, color = abnormalColor(row.abnormal))
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text("Key investigations", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            if (latestCards.isEmpty()) {
                item {
                    Text("No verified quantitative key results. Open Labs for the full laboratory inventory.",
                        fontSize = 12.sp, color = Color(0xFF66788A))
                }
            } else {
                items(latestCards.chunked(2)) { pair ->
                    Row(modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        pair.forEach { (label, row) ->
                            MetricCard(label, row, modifier = Modifier.weight(1f))
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            if (positives.isNotEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(10.dp)) {
                            Text("Recent positive culture reports",
                                fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            positives.take(4).forEach { (key, rows) ->
                                val result = rows.firstOrNull { it.parameter.equals("Result", true) }
                                    ?: rows.first()
                                TextButton(onClick = {
                                    microFilter = "Positive"
                                    selectedTab = ResultsTab.MICROBIOLOGY
                                }) {
                                    Text(key.first + " · " + key.second + ": " +
                                        result.valueText.take(75),
                                        maxLines = 2, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text("Latest clinical measurements", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (recentLab.isEmpty()) {
                    Text("No numeric clinical measurements identified.",
                        fontSize = 12.sp, color = Color(0xFF66788A))
                }
            }
            items(recentLab, key = { it.id }) { row -> ResultRow(row) }
            item {
                OutlinedButton(onClick = { selectedTab = ResultsTab.ALL },
                    modifier = Modifier.fillMaxWidth()) {
                    Text("Explore all results and reports")
                }
            }
        }
    }

    @Composable
    private fun MetricCard(label: String, row: LabResult, modifier: Modifier = Modifier) {
        Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(label, fontSize = 12.sp, color = Color(0xFF66788A))
                Text(
                    row.valueText + if (row.unit.isNotBlank()) " ${row.unit}" else "",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = abnormalColor(row.abnormal),
                    maxLines = 1
                )
                Text(row.date, fontSize = 11.sp, color = Color(0xFF829AB1))
            }
        }
    }


    @Composable
    private fun ClinicalNarrativeCard(test: String, rows: List<LabResult>,
                                      layout: ClinicalLayout, patientCr: String,
                                      inventory: List<ReportRef>) {
        val byDate = remember(rows) {
            rows.groupBy { it.date }.toList()
                .sortedByDescending { BulkParser.dateKey(it.first) }
        }
        var showAll by remember(patientCr, test) { mutableStateOf(false) }
        val datesToShow = if (showAll) byDate else byDate.take(4)
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(test, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    color = Color(0xFF102A43))
                Text(
                    when (layout) {
                        ClinicalLayout.NARRATIVE -> "Narrative report"
                        ClinicalLayout.QUALITATIVE -> "Qualitative result"
                        ClinicalLayout.SOURCE_CODED -> "Coded source report · interpretation requires verification"
                        else -> "Clinical results"
                    } + " · " + byDate.size + " result dates",
                    fontSize = 10.sp, color = Color(0xFF66788A)
                )
                if (layout == ClinicalLayout.SOURCE_CODED) {
                    Text("Opaque HIS control codes are not diagnostic results. " +
                        "The signed source report is authoritative.",
                        fontSize = 11.sp, color = Color(0xFF875B22))
                    if (Regex("""(?i)blood\s*group|antibody\s*screen""").containsMatchIn(test)) {
                        // Only display a conclusion extracted from the actual
                        // CR-matched PDF. It is a source-document summary,
                        // NOT a value assigned to an unlinked bulk result date.
                        val verifiedGroups = inventory.mapNotNull { report ->
                            val header = ReportTitleCache.header(report.url)
                            if (header?.crNo != patientCr) null
                            else PdfClinicalSummaries.bloodGrouping(
                                ReportTitleCache.extracted(report.url).orEmpty()
                            )?.let { report to it }
                        }.distinctBy { it.first.url }
                        if (verifiedGroups.isNotEmpty()) {
                            Text("Blood group conclusions from opened, CR-checked source PDFs",
                                fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF102A43))
                            verifiedGroups.take(5).forEach { (report, group) ->
                                Text("ABO/Rh: " + group.group +
                                    if (group.antibodyScreen.isNotBlank())
                                        " · Antibody screen: " + group.antibodyScreen else "",
                                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                TextButton(onClick = { openReport(report.url) }) {
                                    Text("Verify source PDF · " +
                                        report.date.ifBlank { "date not supplied" })
                                }
                            }
                            Text("Verify against the original signed report before transfusion.",
                                fontSize = 10.sp, color = Color(0xFF875B22))
                        } else {
                            Text("Open the blood-bank source PDF to read the signed ABO/Rh " +
                                "conclusion; only CR-matched extracted text is displayed here.",
                                fontSize = 10.sp, color = Color(0xFF66788A))
                        }
                    }
                }
                datesToShow.forEach { (date, dateRows) ->
                    var showFullText by remember(patientCr, test, date) {
                        mutableStateOf(false)
                    }
                    var showSources by remember(patientCr, test, date) {
                        mutableStateOf(false)
                    }
                    val sourceLinks = ReportCandidates(null, emptyList())
                    HorizontalDivider()
                    Text("Result date: " + date, fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold, color = Color(0xFF486581))
                    val clinical = ClinicalReportRenderer.preferredRows(dateRows)
                    val shown = if (layout == ClinicalLayout.NARRATIVE &&
                        clinical.any { it.valueText.length > 180 }) {
                        // Unstructured HIS reports duplicate the beginning of a
                        // narrative in "Result" and the full text in "textarea".
                        val longest = clinical.maxByOrNull { it.valueText.length }
                        clinical.filter { item ->
                            item == longest || (item.parameter != "textarea" &&
                                !item.parameter.equals("Result", true) &&
                                item.valueText.length > 3 &&
                                !(longest?.valueText?.contains(item.valueText) ?: false))
                        }
                    } else clinical
                    if (shown.isEmpty()) {
                        Text("No interpretable clinical fields in the structured feed.",
                            fontSize = 11.sp, color = Color(0xFF66788A))
                    }
                    val hasLongText = shown.any { it.valueText.length > 280 }
                    if (hasLongText) {
                        TextButton(onClick = { showFullText = !showFullText }) {
                            Text(if (showFullText) "Collapse report" else "Read full report")
                        }
                    }
                    shown.forEach { result ->
                        if (result.parameter.isNotBlank() &&
                            !result.parameter.equals("Result", true) &&
                            !result.parameter.equals("textarea", true)) {
                            Text(result.parameter, fontSize = 11.sp,
                                color = Color(0xFF486581), fontWeight = FontWeight.Medium)
                        }
                        val sections = if (layout == ClinicalLayout.NARRATIVE)
                            ClinicalReportRenderer.sourceSections(result.valueText)
                        else listOf(SourceSection("", result.valueText))
                        sections.forEach { section ->
                            if (section.heading.isNotBlank() &&
                                section.heading !in listOf("Source result", "Source details")) {
                                Text(section.heading, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    color = Color(0xFF102A43))
                            }
                            Text(section.content, fontSize = 12.sp,
                                color = Color(0xFF243B53),
                                maxLines = if (showFullText || !hasLongText) Int.MAX_VALUE else 5,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        result.unit.takeIf(String::isNotBlank)?.let {
                            Text("Units: " + it, fontSize = 10.sp,
                                color = Color(0xFF66788A))
                        }
                    }
                    if (hasLongText && showFullText) {
                        TextButton(onClick = { showFullText = false }) {
                            Text("Collapse report")
                        }
                    }
                    if (sourceLinks.verified != null) {
                        TextButton(onClick = {
                            openReport(sourceLinks.verified.url)
                        }) { Text("Open uniquely matched source PDF") }
                    } else if (sourceLinks.candidates.isNotEmpty()) {
                        TextButton(onClick = { showSources = true }) {
                            Text("Review " + sourceLinks.candidates.size + " related PDFs (unverified)")
                        }
                    } else if (inventory.isNotEmpty()) {
                        TextButton(onClick = {
                            browseSourceReports(test)
                        }) { Text("Browse source reports · no verified PDF match") }
                    }
                    if (showSources) {
                        AlertDialog(
                            onDismissRequest = { showSources = false },
                            title = { Text("Find matching source report") },
                            text = {
                                Column {
                                    Text("Select using the laboratory accession. Date/title alone do not verify the specimen.",
                                        color = Color(0xFF875B22), fontSize = 11.sp)
                                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                                        items(sourceLinks.candidates, key = { it.url }) { report ->
                                            TextButton(onClick = {
                                                showSources = false
                                                openReport(report.url)
                                            }) {
                                                Text(report.title + " · " + report.date +
                                                    if (report.labNumber.isNotBlank())
                                                        " · " + report.labNumber else "",
                                                    fontSize = 11.sp)
                                            }
                                            HorizontalDivider()
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    showSources = false
                                    reportQueryPrefill = ""

                    selectedTab = ResultsTab.ALL
                                }) { Text("All reports") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showSources = false }) { Text("Close") }
                            }
                        )
                    }
                }
                if (byDate.size > 4) {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(if (showAll) "Show latest four dates"
                        else "Show all " + byDate.size + " dates")
                    }
                }
            }
        }
    }

    @Composable
    private fun IdFocusScreen(current: ClinicalBundle, scroll: LazyListState) {
        // The ID view is a filtered longitudinal index, not an AI diagnosis.
        val markers = listOf(
            "CRP / hsCRP" to setOf("crp", "creactiveprotein", "hscrp",
                "highsensitivitycrp", "highsensitivitycreactiveprotein"),
            "ESR" to setOf("esr", "erythrocytesedimentationrate"),
            "Procalcitonin" to setOf("procalcitonin", "pct"),
            "WBC / TLC" to setOf("wbc", "tlc", "totalleukocytecount",
                "totalleucocytecount", "whitebloodcellcount"),
            "ANC" to setOf("anc", "absoluteneutrophilcount")
        )
        val markerRows = remember(current.results) {
            markers.map { (name, aliases) ->
                name to current.results.filter { row ->
                    !isMicro(row) && normalizeKey(row.parameter) in aliases &&
                        row.numericValue?.isFinite() == true &&
                        !ClinicalReportRenderer.isUninterpretedCode(row)
                }.sortedByDescending { BulkParser.dateKey(it.date) }
            }
        }
        val cultures = remember(current.results) {
            current.results.filter { ClinicalPresentation.isCulture(it.test) }
                .groupBy { Triple(it.test, it.date, it.labNumber) }.toList()
                .sortedByDescending { BulkParser.dateKey(it.first.second) }
        }
        val positives = cultures.count { CultureReview.status(it.second) == CultureStatus.POSITIVE }
        val microbiologySourceCount = remember(current.reports, reportTitleEpoch) {
            current.reports.count { report ->
                ClinicalReportRenderer.isMicrobiologyInvestigation(report.title) ||
                    report.department.contains("microbiology", true) ||
                    ClinicalReportRenderer.isMicrobiologyInvestigation(
                        ReportTitleCache.get(report.url).orEmpty())
            }
        }
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Infectious diseases review", fontSize = 18.sp,
                    fontWeight = FontWeight.Bold, color = Color(0xFF102A43))
                Text("Inflammatory markers, leukocyte trends and microbiology. " +
                    "Results are source-derived; clinical interpretation requires context.",
                    fontSize = 11.sp, color = Color(0xFF66788A))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        microFilter = "Positive"
                        selectedTab = ResultsTab.MICROBIOLOGY
                    }) { Text("Positive cultures: " + positives, fontSize = 11.sp) }
                    OutlinedButton(onClick = {
                        microFilter = "All"
                        selectedTab = ResultsTab.MICROBIOLOGY
                    }) { Text("Micro PDFs: " + microbiologySourceCount, fontSize = 11.sp) }
                }
            }
            items(markerRows, key = { it.first }) { (name, rows) ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(11.dp)) {
                        Text(name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        if (rows.isEmpty()) {
                            Text("No verified quantitative value in the structured feed.",
                                fontSize = 11.sp, color = Color(0xFF66788A))
                        } else {
                            rows.take(4).forEach { value ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(value.date, fontSize = 11.sp,
                                        color = Color(0xFF486581))
                                    Text(value.valueText +
                                        if (value.unit.isNotBlank()) " " + value.unit else "",
                                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                        color = abnormalColor(value.abnormal))
                                }
                            }
                            if (rows.size > 4) TextButton(onClick = {
                                selectedTab = ResultsTab.TRENDS
                            }) { Text("View longitudinal trends (" + rows.size + ")") }
                        }
                    }
                }
            }
            item {
                Text("Culture chronology", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (cultures.isEmpty()) Text("No structured culture findings. " +
                    "Inspect Microbiology for unstructured source PDFs.",
                    fontSize = 11.sp, color = Color(0xFF66788A))
            }
            items(cultures.take(10), key = { it.first.toString() }) { (identity, rows) ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp)) {
                        Text(identity.first + " · " + identity.second,
                            fontWeight = FontWeight.Medium, fontSize = 12.sp)
                        Text(CultureReview.status(rows).name,
                            fontSize = 10.sp, color = Color(0xFF486581))
                        TextButton(onClick = {
                            microFilter = "All"
                            selectedTab = ResultsTab.MICROBIOLOGY
                        }) { Text("Review source findings") }
                    }
                }
            }
            if (cultures.size > 10) item {
                TextButton(onClick = {
                    microFilter = "All"
                    selectedTab = ResultsTab.MICROBIOLOGY
                }) { Text("Open all " + cultures.size + " culture episodes") }
            }
        }
    }

    @Composable
    private fun LabsScreen(current: ClinicalBundle, scroll: LazyListState) {
        var query by remember(current.patient.crNo) { mutableStateOf("") }
        var parameter by remember(current.patient.crNo) { mutableStateOf("All") }
        var kind by remember(current.patient.crNo) { mutableStateOf("Clinical") }
        var period by remember(current.patient.crNo) { mutableStateOf("90D") }
        var columns by remember(current.patient.crNo) { mutableStateOf("4") }
        val nonMicro = remember(current.results) { current.results.filterNot { isMicro(it) } }
        val clinicalRows = remember(nonMicro, kind) {
            nonMicro.filter { row ->
                when (kind) {
                    "Serology" -> ClinicalResultRules.isSerology(row) &&
                        !ClinicalPresentation.isTechnical(row) &&
                        !ClinicalPresentation.isOpaquePanelCode(row)
                    "Narrative" -> ClinicalResultRules.isNarrativeOrQualitative(row) &&
                        !ClinicalPresentation.isTechnical(row) &&
                        !ClinicalPresentation.isOpaquePanelCode(row)
                    "Raw" -> true
                    else -> !ClinicalPresentation.isTechnical(row)
                }
            }
        }
        val latest = remember(nonMicro) {
            nonMicro.maxByOrNull { BulkParser.dateKey(it.date) }?.date.orEmpty()
        }
        val periodDays: Long? = when (period) {
            "14D" -> 14L
            "30D" -> 30L
            "90D" -> 90L
            "1Y" -> 365L
            else -> null
        }
        val options = remember(clinicalRows) {
            clinicalRows.filter { it.parameter.isNotBlank() &&
                (kind == "Raw" || !ClinicalReportRenderer.isUninterpretedCode(it)) }
                .groupingBy { it.parameter }.eachCount()
                .entries.sortedByDescending { it.value }
                .take(20).map { it.key }
        }
        val filtered = remember(clinicalRows, query, parameter, period, latest) {
            clinicalRows.filter { row ->
                (kind == "Raw" || !ClinicalReportRenderer.isPlaceholder(row)) &&
                    (parameter == "All" || row.parameter == parameter) &&
                    ClinicalResultRules.inWindow(row.date, latest, periodDays) &&
                    (query.isBlank() || (row.test + " " + row.parameter + " " + row.date)
                        .contains(query, true))
            }
        }
        val groups = remember(filtered) {
            filtered.groupBy { it.test.ifBlank { "Other investigations" } }
                .toList().sortedBy { it.first }
        }
        LazyColumn(state = scroll, 
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            item {
                Text("Laboratory comparison", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Search investigation or parameter") }
                )
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("Clinical", "Serology", "Narrative", "Raw").forEach { category ->
                        FilterChip(selected = kind == category,
                            onClick = { kind = category; parameter = "All" },
                            label = { Text(category, fontSize = 11.sp) })
                    }
                }
                Text("Parameter", fontSize = 11.sp, color = Color(0xFF66788A))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    (listOf("All") + options).forEach { name ->
                        FilterChip(selected = parameter == name,
                            onClick = { parameter = name },
                            label = { Text(name, fontSize = 11.sp, maxLines = 1) })
                    }
                }
                Text("Date range · relative to latest result", fontSize = 11.sp,
                    color = Color(0xFF66788A))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("14D", "30D", "90D", "1Y", "All").forEach { span ->
                        FilterChip(selected = period == span, onClick = { period = span },
                            label = { Text(span, fontSize = 11.sp) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Dates shown:", fontSize = 11.sp, color = Color(0xFF66788A))
                    listOf("4", "8", "All").forEach { count ->
                        FilterChip(selected = columns == count,
                            onClick = { columns = count },
                            label = { Text(count, fontSize = 11.sp) })
                    }
                }
            }
            if (groups.isEmpty()) {
                item { Text("No matching values in this date range.", color = Color(0xFF66788A)) }
            }
            items(groups, key = { it.first }) { (test, sourceRows) ->
                val selectedLayout = ClinicalReportRenderer.layout(test, sourceRows)
                if (selectedLayout != ClinicalLayout.SERIES && kind != "Raw") {
                    ClinicalNarrativeCard(test, sourceRows, selectedLayout,
                        current.patient.crNo, current.reports)
                } else {
                val rows = if (kind == "Raw") sourceRows else sourceRows.filter {
                    !ClinicalReportRenderer.isUninterpretedCode(it) &&
                        !ClinicalReportRenderer.isPlaceholder(it)
                }
                val dates = remember(rows, columns) {
                    val allDates = rows.map { it.date }.distinct()
                        .sortedByDescending { BulkParser.dateKey(it) }
                    when (columns) {
                        "4" -> allDates.take(4)
                        "8" -> allDates.take(8)
                        else -> allDates
                    }
                }
                val parameters = remember(rows) {
                    rows.groupBy { it.parameter.ifBlank { it.test } }.toList()
                }
                var selectedValues by remember(current.patient.crNo, test) {
                    mutableStateOf<List<LabResult>>(emptyList())
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(test + " · " + rows.size + " values", fontWeight = FontWeight.Bold,
                            fontSize = 14.sp, color = Color(0xFF102A43))
                        if (Regex("(?i)biofire|filmarray|ana\\s*(?:profile|blot|panel)")
                                .containsMatchIn(test)) {
                            Text("Panel codes are uninterpreted without a validated target dictionary. Verify the original PDF.",
                                fontSize = 10.sp, color = Color(0xFF875B22))
                            TextButton(onClick = {
                                browseSourceReports(test)
                            }) { Text("Find source panel PDF") }
                        }
                        if (Regex("(?i)blood grouping|antibody screening").containsMatchIn(test)) {
                            Text("Source-coded result fields may not represent clinical values. Verify ABO/Rh and interpretation in the original PDF.",
                                fontSize = 10.sp, color = Color(0xFF875B22))
                        }
                        Text(dates.size.toString() + " dates · scroll horizontally for older values",
                            fontSize = 10.sp, color = Color(0xFF829AB1))
                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.width(117.dp)) {
                                Text("Parameter", fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.height(27.dp))
                                parameters.forEach { (name, values) ->
                                    Column(modifier = Modifier.height(44.dp).padding(end = 5.dp)) {
                                        Text(name, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                            maxLines = 2)
                                        values.firstOrNull()?.unit?.takeIf { it.isNotBlank() }?.let { unit ->
                                            Text(unit, fontSize = 9.sp,
                                                color = Color(0xFF829AB1), maxLines = 1)
                                        }
                                    }
                                }
                            }
                            Row(modifier = Modifier.weight(1f)
                                .horizontalScroll(rememberScrollState())) {
                                dates.forEach { date ->
                                    Column(modifier = Modifier.width(92.dp)) {
                                        Text(date.drop(2), fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.height(27.dp))
                                        parameters.forEach { (_, values) ->
                                            val matches = values.filter { it.date == date }
                                            val value = matches.singleOrNull()
                                            Box(
                                                modifier = Modifier.height(44.dp).fillMaxWidth()
                                                    .clickable(enabled = matches.isNotEmpty()) {
                                                        selectedValues = matches
                                                    }.padding(end = 6.dp)
                                            ) {
                                                val flag = value?.abnormal?.lowercase().orEmpty()
                                                val flagged = flag == "h" || flag == "l" ||
                                                    "high" in flag || "low" in flag ||
                                                    "critical" in flag
                                                Surface(
                                                    color = if (flagged) Color(0xFFFFF0E9) else Color.Transparent,
                                                    shape = RoundedCornerShape(4.dp)
                                                ) {
                                                    Text(when {
                                                        matches.size > 1 -> "${matches.size} results"
                                                        value != null -> ClinicalPresentation.displayValue(value)
                                                        else -> "—"
                                                    },
                                                        modifier = Modifier.padding(horizontal = 2.dp),
                                                        fontSize = 12.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = value?.let {
                                                            abnormalColor(it.abnormal)
                                                        } ?: Color(0xFF829AB1), maxLines = 2)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (selectedValues.isNotEmpty()) {
                            HorizontalDivider()
                            Text(selectedValues.first().parameter + " · " +
                                selectedValues.first().date +
                                if (selectedValues.size > 1) " · ${selectedValues.size} source entries" else "",
                                fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            selectedValues.forEach { detail ->
                                Text(ClinicalPresentation.displayValue(detail) +
                                    (if (detail.unit.isBlank()) "" else " " + detail.unit),
                                    fontSize = 13.sp)
                                if (detail.sentDate.isNotBlank()) {
                                    Text("Collected: " + detail.sentDate, fontSize = 10.sp,
                                        color = Color(0xFF66788A))
                                }
                                if (detail.reportedDate.isNotBlank()) {
                                    Text("Reported: " + detail.reportedDate, fontSize = 10.sp,
                                        color = Color(0xFF66788A))
                                }
                                if (detail.labNumber.isNotBlank()) {
                                    Text("Lab accession: " + detail.labNumber, fontSize = 10.sp,
                                        color = Color(0xFF486581))
                                }
                                if (detail.refRange.isNotBlank()) {
                                    Text("Reference: " + detail.refRange, fontSize = 11.sp,
                                        color = Color(0xFF66788A))
                                }
                            }
                            TextButton(onClick = { selectedValues = emptyList() }) { Text("Close detail") }
                        }
                    }
                }
                }
            }
        }
    }

    @Composable
    private fun MicroScreen(current: ClinicalBundle, scroll: LazyListState) {
        var query by remember(current.patient.crNo) { mutableStateOf("") }
        var showSourceInventory by remember(current.patient.crNo) { mutableStateOf(false) }
        // Source inventory is intentionally independent of structured rows:
        // many NIMS microscopy/molecular reports have no parseable values.
        val sourceMicro = remember(current.reports, reportTitleEpoch) {
            current.reports.filter { report ->
                ClinicalReportRenderer.isMicrobiologyInvestigation(report.title) ||
                    report.department.contains("microbiology", ignoreCase = true) ||
                    ClinicalReportRenderer.isMicrobiologyInvestigation(
                        ReportTitleCache.get(report.url).orEmpty())
            }.distinctBy { it.url.ifBlank { "id|" + it.id } }
        }
        val grouped = remember(current.results, query, microFilter) {
            // Filter complete culture reports rather than individual rows: this
            // preserves accompanying susceptibility, resistance and notes.
            current.results.filter { isMicro(it) }
                // Keep same-day cultures from different proven lab accessions
                // separate; unknown accessions remain a flagged ambiguous group.
                .groupBy { row -> Triple(row.test, row.date, row.labNumber.trim()) }.toList()
                .filter { (key, rows) ->
                    val category = CultureReview.status(rows).name
                    (microFilter == "All" || category.equals(microFilter, true)) &&
                        (query.isBlank() || (key.first + " " + key.second + " " +
                            rows.joinToString(" ") { it.parameter + " " + it.valueText })
                            .contains(query, ignoreCase = true))
                }
                .sortedByDescending { (key, values) ->
                    BulkParser.dateKey(BulkParser.isoDate(
                        ClinicalPresentation.sentDate(values).ifBlank { key.second }))
                }
        }
        LazyColumn(state = scroll, 
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("Microbiology reports", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(grouped.size.toString() + " investigations · " +
                    sourceMicro.size + " original microbiology PDFs available in All results.",
                    color = Color(0xFF66788A), fontSize = 11.sp)
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("All", "Positive", "Negative", "Pending", "Other").forEach { type ->
                        FilterChip(selected = microFilter == type,
                            onClick = { microFilter = type },
                            label = { Text(type, fontSize = 11.sp) })
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Search organism, specimen or date") }
                )
            }
            if (grouped.isEmpty()) {
                item { Text("No matching structured microbiology rows. Check the source PDF inventory below.",
                    color = Color(0xFF66788A)) }
            }
            items(grouped, key = { it.first.first + "|" + it.first.second + "|" + it.first.third }) { (key, rows) ->
                val (test, date) = key
                val clinical = ClinicalReportRenderer.preferredRows(rows)
                    .sortedWith(compareBy<LabResult> {
                        if (it.parameter.equals("Result", ignoreCase = true)) 0
                        else if (Regex("(?i)organism|sensitivity|susceptib|antibiotic|mic")
                                .containsMatchIn(it.parameter)) 1 else 2
                    })
                var expanded by remember(current.patient.crNo, key) { mutableStateOf(false) }
                var showOriginal by remember(current.patient.crNo, key) { mutableStateOf(false) }
                val isCulture = ClinicalPresentation.isCulture(test)
                val mapping = ReportCandidates(null, emptyList())
                val exact = mapping.verified
                var chooseSource by remember(current.patient.crNo, key) {
                    mutableStateOf(false)
                }
                val cacheVersion = reportTitleEpoch
                val verifiedPdf = if (cacheVersion >= 0) exact?.let {
                    ReportTitleCache.header(it.url)?.takeIf { head ->
                        head.crNo == current.patient.crNo
                    }
                } else null
                val accession = ClinicalPresentation.accession(rows)
                    .ifBlank { verifiedPdf?.labNumber.orEmpty() }
                val related = if (exact == null) mapping.candidates.singleOrNull()
                    else null
                val relatedHeader = related?.let {
                    ReportTitleCache.header(it.url)?.takeIf { header ->
                        header.crNo == current.patient.crNo
                    }
                }
                val sentDate = ClinicalPresentation.sentDate(rows)
                    .ifBlank { verifiedPdf?.collectionDate.orEmpty() }
                val reportedDate = ClinicalPresentation.reportedDate(rows)
                    .ifBlank { verifiedPdf?.reportedDate.orEmpty() }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(test, fontWeight = FontWeight.Bold, color = Color(0xFF102A43),
                            fontSize = 15.sp)
                        val status = CultureReview.status(rows)
                        val statusColor = when(status) {
                            CultureStatus.POSITIVE -> Color(0xFFB3261E)
                            CultureStatus.NEGATIVE -> Color(0xFF28744A)
                            CultureStatus.PENDING -> Color(0xFF996100)
                            else -> Color(0xFF66788A)
                        }
                        if (isCulture) Text(status.name,
                            color = statusColor, fontSize = 11.sp,
                            fontWeight = FontWeight.Bold)
                        Text("Lab/study number: " +
                            accession.ifBlank { "Not available in structured results" },
                            fontSize = 11.sp, color = Color(0xFF486581))
                        Text("Sent/collection date: " +
                            sentDate.ifBlank { "Not available" },
                            fontSize = 11.sp, color = Color(0xFF486581))
                        Text("Reported date: " +
                            reportedDate.ifBlank { "Not available (result date: " + date + ")" },
                            fontSize = 11.sp, color = Color(0xFF486581))
                        if (relatedHeader != null) {
                            val possible = listOfNotNull(
                                relatedHeader.labNumber.takeIf(String::isNotBlank)
                                    ?.let { "Lab " + it },
                                relatedHeader.collectionDate.takeIf(String::isNotBlank)
                                    ?.let { "sent " + it },
                                relatedHeader.reportedDate.takeIf(String::isNotBlank)
                                    ?.let { "reported " + it }
                            )
                            if (possible.isNotEmpty()) Text(
                                "Related source PDF (not specimen-verified): " +
                                    possible.joinToString(" · "),
                                fontSize = 10.sp, color = Color(0xFF875B22))
                        }
                        val reportRows = clinical.filter { row ->
                            row.parameter.equals("Result", true) ||
                            row.valueText.contains("CULTURE REPORT", true) ||
                            row.valueText.contains("SENSITIVITY REPORT", true)
                        }
                        val narrative = reportRows.joinToString("\n") { it.valueText }
                        val parts = CultureNarrative.parse(narrative)
                        if (parts.culture.isNotBlank()) {
                            Text(if (isCulture) "Culture finding" else "Laboratory finding",
                                color = Color(0xFF486581),
                                fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(parts.culture, fontSize = 13.sp,
                                maxLines = if (expanded) Int.MAX_VALUE else 5)
                            Spacer(Modifier.height(7.dp))
                        }
                        if (parts.susceptible.isNotBlank()) {
                            Text("Reported susceptibility", fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, color = Color(0xFF206A46))
                            Text(parts.susceptible, fontSize = 13.sp,
                                maxLines = if (expanded) Int.MAX_VALUE else 6)
                            Spacer(Modifier.height(7.dp))
                        }
                        if (parts.intermediate.isNotBlank()) {
                            Text("Source intermediate section", fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, color = Color(0xFF805400))
                            Text(parts.intermediate, fontSize = 13.sp,
                                maxLines = if (expanded) Int.MAX_VALUE else 6)
                            Spacer(Modifier.height(7.dp))
                        }
                        if (parts.resistant.isNotBlank()) {
                            Text("Reported resistance", fontSize = 12.sp,
                                fontWeight = FontWeight.Bold, color = Color(0xFF8B3131))
                            Text(parts.resistant, fontSize = 13.sp,
                                maxLines = if (expanded) Int.MAX_VALUE else 6)
                            Spacer(Modifier.height(7.dp))
                        }
                        if (parts.advice.isNotBlank()) {
                            Text("Laboratory comments", fontSize = 11.sp,
                                fontWeight = FontWeight.Bold, color = Color(0xFF486581))
                            Text(parts.advice, fontSize = 12.sp,
                                maxLines = if (expanded) Int.MAX_VALUE else 4)
                        }
                        clinical.filterNot { it in reportRows }.forEach { row ->
                            Text(row.parameter, fontSize = 11.sp,
                                color = Color(0xFF486581), fontWeight = FontWeight.SemiBold)
                            Text(row.valueText, fontSize = 13.sp,
                                maxLines = if (expanded) Int.MAX_VALUE else 5)
                        }
                        if (clinical.any { it.valueText.length > 120 }) {
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(if (expanded) "Condense details" else "Expand clinical details")
                            }
                        }
                        // Original prose stays available for audit, but is not
                        // redundantly displayed under every parsed finding.
                        if (narrative.isNotBlank() && (parts.susceptible.isNotBlank() ||
                                parts.resistant.isNotBlank() || parts.advice.isNotBlank())) {
                            TextButton(onClick = { showOriginal = !showOriginal }) {
                                Text(if (showOriginal) "Hide original wording" else "Verify original wording")
                            }
                            if (showOriginal) {
                                Text(narrative, fontSize = 11.sp, color = Color(0xFF486581))
                            }
                        }
                        if (exact != null) {
                            OutlinedButton(onClick = { openReport(exact.url) }) {
                                Text("Verified accession-linked PDF")
                            }
                        } else if (mapping.candidates.size == 1) {
                            TextButton(onClick = {
                                openReport(mapping.candidates.single().url)
                            }) { Text("Open related PDF · confirm identity") }
                        } else if (mapping.candidates.isNotEmpty()) {
                            TextButton(onClick = { chooseSource = true }) {
                                Text("Review " + mapping.candidates.size +
                                    " related source PDFs")
                            }
                        } else if (current.reports.isNotEmpty()) {
                            TextButton(onClick = { browseSourceReports(test) }) {
                                Text("Find original PDF in All results")
                            }
                        }
                        if (chooseSource) {
                            AlertDialog(
                                onDismissRequest = { chooseSource = false },
                                title = { Text("Match source PDF to specimen") },
                                text = {
                                    Column {
                                        Text(test + " · result date " + date +
                                            " · accession " + accession.ifBlank { "unknown" },
                                            fontSize = 12.sp)
                                        Text("Candidate reports are not automatically verified. Confirm the lab/study number in the source PDF before using clinical findings.",
                                            fontSize = 11.sp, color = Color(0xFF875B22))
                                        LazyColumn(Modifier.heightIn(max = 380.dp)) {
                                            items(mapping.candidates, key = { it.url }) { report ->
                                                Column {
                                                    TextButton(onClick = {
                                                        chooseSource = false
                                                        openReport(report.url)
                                                    }) {
                                                        Text(report.title + " · " +
                                                            report.date.ifBlank { "date unavailable" } +
                                                            if (report.labNumber.isNotBlank())
                                                                " · " + report.labNumber else "",
                                                            fontSize = 12.sp)
                                                    }
                                                    HorizontalDivider()
                                                }
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        chooseSource = false
                                        reportQueryPrefill = ""

                    selectedTab = ResultsTab.ALL
                                    }) { Text("All source reports") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { chooseSource = false }) { Text("Close") }
                                }
                            )
                        }
                        if (isCulture && clinical.none { row ->
                                Regex("(?i)sensitivity|susceptib|antibiogram|\\bmic\\b|resistan")
                                    .containsMatchIn(row.parameter + " " + row.valueText)
                            }) {
                            Text("Susceptibility not separately identified in structured rows; check the original report.",
                                fontSize = 10.sp, color = Color(0xFF66788A))
                        }
                    }
                }
            }
            if (sourceMicro.isNotEmpty()) item {
                TextButton(onClick = {
                    allResultsSourceFilter = "Microbiology"

                    selectedTab = ResultsTab.ALL
                }) { Text("View " + sourceMicro.size + " original microbiology PDFs in All results") }
            }

        }
    }


    @Composable
    private fun TrendsScreen(current: ClinicalBundle, scroll: LazyListState) {
        val series = remember(current.results) {
            current.results.filter { ClinicalResultRules.isTrendEligible(it) }
                .groupBy { ClinicalResultRules.parameterKey(it) }
                .mapValues { (_, values) ->
                    values.sortedByDescending { BulkParser.dateKey(it.date) }
                        .distinctBy { listOf(it.date, it.reportedDate, it.sentDate,
                            it.labNumber, it.valueText, it.sourceKey) }
                        .sortedWith(compareBy<LabResult> { BulkParser.dateKey(it.date) }
                            .thenBy { it.reportedDate }
                            .thenBy { it.sentDate })
                }
                .filterValues { it.size >= 2 }
                .toList().sortedByDescending { it.second.size }
        }
        var chosen by remember(current.patient.crNo) {
            mutableStateOf(series.firstOrNull()?.first.orEmpty())
        }
        var span by remember(current.patient.crNo) { mutableStateOf("All") }
        val selected = series.firstOrNull { it.first == chosen }
            ?: series.firstOrNull()
        val period: Long? = when (span) {
            "7D" -> 7L
            "30D" -> 30L
            "90D" -> 90L
            "1Y" -> 365L
            else -> null
        }
        val latest = selected?.second?.lastOrNull()?.date.orEmpty()
        val rows = selected?.second?.filter {
            ClinicalResultRules.inWindow(it.date, latest, period)
        }.orEmpty()
        LazyColumn(state = scroll,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 10.dp, top = 10.dp,
                end = 28.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            item {
                Text("Clinical laboratory trends", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Only validated quantitative lab parameters are plotted. Administrative fields, specimen codes and processing counts are excluded.",
                    fontSize = 11.sp, color = Color(0xFF66788A))
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    series.forEach { (key, measurements) ->
                        val label = measurements.first().parameter
                        FilterChip(selected = key == selected?.first,
                            onClick = { chosen = key },
                            label = { Text(label, fontSize = 11.sp, maxLines = 1) })
                    }
                }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("7D", "30D", "90D", "1Y", "All").forEach { option ->
                        FilterChip(selected = span == option, onClick = { span = option },
                            label = { Text(option, fontSize = 11.sp) })
                    }
                }
            }
            if (rows.size >= 2) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(12.dp)) {
                            val first = rows.first()
                            val last = rows.last()
                            Text(first.parameter + if (first.unit.isNotBlank()) " (" + first.unit + ")" else "",
                                fontWeight = FontWeight.Bold)
                            Text(rows.size.toString() + " measurements · " +
                                first.date + " → " + last.date, fontSize = 11.sp,
                                color = Color(0xFF66788A))
                            Spacer(Modifier.height(6.dp))
                            TrendChart(rows, Modifier.fillMaxWidth().height(180.dp))
                            Text("Dates on the horizontal axis reflect actual elapsed time.",
                                fontSize = 10.sp, color = Color(0xFF829AB1))
                            Text("Latest: " + last.valueText + " · " + last.date,
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                items(rows.reversed(), key = { it.id }) { row -> ResultRow(row) }
            } else {
                item {
                    Text(if (series.isEmpty()) "No eligible lab parameter has repeated numeric measurements."
                         else "Fewer than two measurements in this date range. Select a longer interval.",
                        color = Color(0xFF66788A))
                }
            }
        }
    }

    @Composable
    private fun TrendChart(rows: List<LabResult>, modifier: Modifier = Modifier) {
        val points = rows.mapNotNull { row ->
            val day = runCatching {
                java.time.LocalDate.parse(row.date).toEpochDay().toDouble()
            }.getOrNull()
            val value = row.numericValue
            // Use source-supplied clock time when available. Do not invent
            // times for repeated samples with only a calendar date.
            val timestamp = row.reportedDate.ifBlank { row.sentDate }
            val hhmm = Regex("""(\d{1,2}):(\d{2})""").find(timestamp)
            val minutes = hhmm?.let {
                val hour = it.groupValues[1].toIntOrNull()
                val minute = it.groupValues[2].toIntOrNull()
                if (hour != null && minute != null &&
                    hour in 0..23 && minute in 0..59) hour * 60 + minute else null
            }
            if (day != null && value != null)
                (day + (minutes?.div(1440.0) ?: 0.0)) to value else null
        }
        if (points.size < 2) return
        val values = points.map { it.second }
        val min = values.minOrNull() ?: return
        val max = values.maxOrNull() ?: return
        val days = points.map { it.first }
        val minDay = days.minOrNull() ?: return
        val maxDay = days.maxOrNull() ?: return
        val valueRange = (max - min).takeIf { it != 0.0 } ?: 1.0
        val dayRange = (maxDay - minDay).takeIf { it != 0.0 } ?: 1.0
        Canvas(modifier = modifier.background(Color(0xFFF7FAFC),
            RoundedCornerShape(10.dp)).border(1.dp,
            Color(0xFFE2E8F0), RoundedCornerShape(10.dp))) {
            val pad = 18f
            val usableW = size.width - 2 * pad
            val usableH = size.height - 2 * pad
            val path = Path()
            points.forEachIndexed { index, point ->
                val x = pad + usableW * ((point.first - minDay) / dayRange).toFloat()
                val y = pad + usableH * (1f - ((point.second - min) / valueRange).toFloat())
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(Color(0xFF0B5CAB), radius = 4f, center = Offset(x, y))
            }
            drawPath(path, Color(0xFF0B5CAB), style = Stroke(width = 3f))
        }
    }


    @Composable
    private fun AllResultsScreen(current: ClinicalBundle, scroll: LazyListState) {
        var subview by remember(current.patient.crNo) { mutableStateOf("Investigations") }
        var query by remember(current.patient.crNo) { mutableStateOf("") }
        var category by remember(current.patient.crNo) { mutableStateOf("All") }
        var range by remember(current.patient.crNo) { mutableStateOf("All") }
        val days = when (range) {
            "7D" -> 7L
            "30D" -> 30L
            "90D" -> 90L
            else -> null
        }
        // The former Clinical results / Original PDFs switch duplicated the
        // same episode and stranded bundled reports. Both now share one feed.
        LaunchedEffect(allResultsSourceFilter) {
            if (allResultsSourceFilter in listOf("Microbiology", "Blood bank",
                    "Metabolic", "Pathology")) {
                category = allResultsSourceFilter
                query = ""
                scroll.scrollToItem(0)
            }
        }
        val baseFeed = remember(current.results, current.reports, reportTitleEpoch) {
            UnifiedResultsFeed.baseEntries(current)
        }
        val feed = remember(baseFeed, query, category, range) {
            UnifiedResultsFeed.filterEntries(baseFeed, query, category, days)
        }
        val visibleFeed = feed.filter { if (subview == "Report index / PDFs")
            it.report != null else it.report == null }
        val studyCount = feed.count { it.report == null }
        val sourceCount = feed.count { it.report != null }
        LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 10.dp, top = 10.dp,
                end = 27.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text("All results", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("$studyCount investigation groups · $sourceCount source-index entries",
                    fontSize = 11.sp, color = Color(0xFF66788A))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("Investigations", "Report index / PDFs").forEach { name ->
                        FilterChip(selected = subview == name,
                            onClick = { subview = name; query = "" },
                            label = { Text(name, fontSize = 11.sp) })
                    }
                }
                if (pdfTotalCount > 0) Text(
                    "$pdfTotalCount source PDFs · Open a report to inspect it securely",
                    fontSize = 10.sp, color = Color(0xFF66788A))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Search test, result, lab number, report or date") }
                )
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("All", "Microbiology", "Metabolic", "Pathology",
                        "Blood bank", "Labs", "Flagged", "Raw").forEach { choice ->
                        FilterChip(selected = category == choice,
                            onClick = {
                                category = choice
                                allResultsSourceFilter = choice
                            },
                            label = { Text(choice, fontSize = 11.sp) })
                    }
                }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("7D", "30D", "90D", "All").forEach { value ->
                        FilterChip(selected = range == value,
                            onClick = { range = value },
                            label = { Text(value, fontSize = 11.sp) })
                    }
                }
                Text("Full investigations and the independent source PDF list are separate. " +
                    "Specimen identifiers from the report index are kept separate until verified.",
                    fontSize = 10.sp, color = Color(0xFF66788A))
            }
            if (visibleFeed.isEmpty()) item {
                Text("No investigations or source-index entries match these filters.",
                    color = Color(0xFF66788A))
            }
            items(visibleFeed, key = { it.key }) { entry ->
                var expanded by remember(current.patient.crNo, entry.key) {
                    mutableStateOf(false)
                }
                var showCandidates by remember(current.patient.crNo, entry.key) {
                    mutableStateOf(false)
                }
                var showEntireSource by remember(current.patient.crNo, entry.key) {
                    mutableStateOf(false)
                }
                val report = entry.report
                val rows = entry.rows
                val resultLinks = ReportCandidates(null, emptyList())
                val verifiedReport = resultLinks.verified
                val accession = ClinicalPresentation.accession(rows)
                val linkedPart = verifiedReport?.url?.let { url ->
                    ReportTitleCache.sections(url).filter { part ->
                        accession.isNotBlank() && part.header.labNumber.isNotBlank() &&
                            normalizeKey(part.header.labNumber) == normalizeKey(accession)
                    }.distinctBy { it.header.labNumber }.singleOrNull()
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded },
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.title.ifBlank { "Investigation title unavailable" },
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF102A43))
                                Text(
                                    if (report != null) (if (report.url.isNotBlank()) "Original NIMS PDF · " else "NIMS source index (PDF unavailable) · ") +
                                        (if (entry.date == LocalDate.MIN) "date not provided"
                                         else entry.date.toString())
                                    else rows.size.toString() + " result fields · column date " +
                                        (if (entry.date == LocalDate.MIN) "date not provided"
                                         else entry.date.toString()),
                                    fontSize = 10.sp, color = Color(0xFF66788A))
                            }
                            Text(if (expanded) "Collapse ↑" else "Expand ↓",
                                fontSize = 11.sp, color = Color(0xFF0B5CAB))
                        }
                        if (report == null) {
                            val summary = remember(rows) {
                                ClinicalReportRenderer.preferredRows(rows)
                                    .firstOrNull {
                                        it.valueText.isNotBlank() &&
                                            !ClinicalResultRules.isAdministrativeParameter(it.parameter)
                                    }?.valueText.orEmpty()
                            }
                            if (!expanded && summary.isNotBlank()) Text(
                                summary.replace(Regex("[\\r\\n]+"), " ").take(155) +
                                    if (summary.length > 155) "…" else "",
                                fontSize = 11.sp, maxLines = 2,
                                color = Color(0xFF486581))
                            else if (!expanded) Text(
                                "Only source-coded or technical fields; expand to review.",
                                fontSize = 10.sp, color = Color(0xFF66788A))
                            val collected = rows.firstOrNull { it.collectionDateTime.isNotBlank() }
                                ?.collectionDateTime.orEmpty()
                            val sent = rows.firstOrNull { it.sentDate.isNotBlank() }?.sentDate.orEmpty()
                            val reported = ClinicalPresentation.reportedDate(rows)
                                .ifBlank { linkedPart?.header?.reportedDate.orEmpty() }
                            if (!expanded) {
                                Text(
                                    "Lab: " + accession.ifBlank { "—" } +
                                        " · Sample: " + rows.firstOrNull()?.sampleNumber.orEmpty().ifBlank { "—" } +
                                        " · Collected: " + collected.ifBlank { "—" } +
                                        " · Reported: " + reported.ifBlank { "—" },
                                    fontSize = 10.sp, color = Color(0xFF486581))
                            } else {
                                Text("Lab/study no.: " +
                                    accession.ifBlank { "Not available in structured feed" },
                                    fontSize = 11.sp)
                                Text("Collected/studied: " +
                                    collected.ifBlank { "Not in this Custom Sheet response" }, fontSize = 11.sp)
                                if (sent.isNotBlank()) Text("Sample sent: " + sent, fontSize = 11.sp)
                                rows.firstOrNull()?.let { first ->
                                    if (first.sampleNumber.isNotBlank()) Text("Sample: " + first.sampleNumber, fontSize=11.sp)
                                    if (first.accessionNumber.isNotBlank()) Text("Accession: " + first.accessionNumber, fontSize=11.sp)
                                    if (first.specimenType.isNotBlank()) Text("Specimen: " + first.specimenType, fontSize=11.sp)
                                    if (first.laboratory.isNotBlank()) Text("Laboratory: " + first.laboratory, fontSize=11.sp)
                                    if (first.receivedDateTime.isNotBlank()) Text("Received: " + first.receivedDateTime, fontSize=11.sp)
                                    if (first.validationDateTime.isNotBlank()) Text("Validated: " + first.validationDateTime, fontSize=11.sp)
                                    if (first.requisitionDateTime.isNotBlank()) Text("Requisition: " + first.requisitionDateTime, fontSize=11.sp)
                                    if (first.sourceOrderId.isNotBlank()) Text("Order: " + first.sourceOrderId, fontSize=11.sp)
                                    if (first.reportNumber.isNotBlank()) Text("Report No: " + first.reportNumber, fontSize=11.sp)
                                    if (first.reportStage.isNotBlank()) Text("Stage: " + first.reportStage, fontSize=11.sp)
                                }
                                Text("Reported: " +
                                    reported.ifBlank { "Not in this Custom Sheet response" }, fontSize = 11.sp)
                            }
                            val ordered = rows.firstOrNull { it.orderDate.isNotBlank() }
                                ?.orderDate.orEmpty().ifBlank {
                                    linkedPart?.header?.orderDate.orEmpty()
                                }
                            if (ordered.isNotBlank()) Text("Ordered: $ordered", fontSize = 10.sp)
                            if (expanded) {
                                HorizontalDivider()
                                // Preserve even unclassified source values. Codes are
                                // labelled, never falsely interpreted or discarded.
                                val preferred = ClinicalReportRenderer.preferredRows(rows)
                                val display = if (category == "Raw" || preferred.isEmpty())
                                    rows else preferred
                                display.forEach { result ->
                                    Text(result.parameter.ifBlank { "Result" } +
                                        if (result.unit.isNotBlank()) " (" + result.unit + ")"
                                        else "", fontSize = 11.sp,
                                        color = Color(0xFF486581),
                                        fontWeight = FontWeight.SemiBold)
                                    Text(ClinicalPresentation.displayValue(result),
                                        fontSize = 12.sp,
                                        color = abnormalColor(result.abnormal))
                                    if (result.refRange.isNotBlank()) Text(
                                        "Reference: " + result.refRange, fontSize = 10.sp)
                                }
                                if (category == "Raw") {
                                    val rawForTest = remember(current.rawRows, entry.title) {
                                        current.rawRows.filter { it.investigation == entry.title }
                                    }
                                    rawForTest.forEach { source ->
                                        Text("Original source row " + source.rowIndex, fontSize = 11.sp)
                                        Text(source.sourceJson, fontSize = 10.sp, color = Color(0xFF486581))
                                    }
                                }
                                if (preferred.size < rows.size) Text(
                                    "Additional unclassified source fields are available " +
                                        "under Raw; verify clinical meaning in the PDF.",
                                    fontSize = 10.sp, color = Color(0xFF875B22))
                                if (linkedPart != null) {
                                    Text("Confirmed source section · page " + linkedPart.page,
                                        fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    val extractedValues = remember(linkedPart.text) {
                                        PdfClinicalValues.parse(linkedPart.text)
                                    }
                                    extractedValues.take(25).forEach { value ->
                                        Text(value.parameter + ": " + value.value +
                                            if (value.unit.isNotBlank()) " " + value.unit else "",
                                            fontSize = 11.sp, color = Color(0xFF243B53))
                                    }
                                    Text(if (showEntireSource) linkedPart.text.take(4000)
                                         else linkedPart.text.take(900) +
                                            if (linkedPart.text.length > 900) "…" else "",
                                        fontSize = 11.sp, color = Color(0xFF486581))
                                    if (linkedPart.text.length > 900) TextButton(onClick = {
                                        showEntireSource = !showEntireSource
                                    }) { Text(if (showEntireSource) "Collapse original text"
                                        else "Read complete PDF-derived narrative") }
                                }
                            }
                            when {
                                verifiedReport != null -> TextButton(onClick = {
                                    openReport(verifiedReport.url)
                                }) { Text("Open accession-linked original PDF") }
                                resultLinks.candidates.size == 1 -> TextButton(onClick = {
                                    openReport(resultLinks.candidates.single().url)
                                }) { Text("Review possible PDF · match unverified") }
                                resultLinks.candidates.isNotEmpty() -> {
                                    TextButton(onClick = {
                                        showCandidates = !showCandidates
                                    }) {
                                        Text(if (showCandidates) "Hide candidate PDFs"
                                            else "Review " + resultLinks.candidates.size +
                                                " possible PDFs")
                                    }
                                    if (showCandidates) {
                                        Text("These links are not specimen verified. " +
                                            "Check accession, collection time and patient CR.",
                                            fontSize = 10.sp, color = Color(0xFF875B22))
                                        resultLinks.candidates.forEach { candidate ->
                                            TextButton(onClick = { openReport(candidate.url) }) {
                                                Text(candidate.title + " · " +
                                                    candidate.date, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                                current.reports.isNotEmpty() -> Text(
                                    "No defensible PDF mapping yet; original source " +
                                        "documents remain in this list.",
                                    fontSize = 10.sp, color = Color(0xFF875B22))
                            }
                        } else {
                            val parts = ReportTitleCache.sections(report.url)
                            val header = ReportTitleCache.header(report.url)
                                ?.takeIf { it.crNo == current.patient.crNo }
                            val lab = header?.labNumber.orEmpty().ifBlank { report.labNumber }
                            val collected = header?.collectionDate.orEmpty()
                                .ifBlank { report.collectionDateTime }
                                .ifBlank { report.sentDate }
                            val reported = header?.reportedDate.orEmpty()
                                .ifBlank { report.reportedDate }
                            Text("Lab/study no.: " + lab.ifBlank {
                                if (parts.isNotEmpty()) "Multiple studies / see sections"
                                else "Not yet extracted"
                            }, fontSize = 11.sp)
                            if (report.sampleNumber.isNotBlank()) Text(
                                "Sample No.: " + report.sampleNumber, fontSize = 11.sp)
                            if (report.sourceSampleLabText.isNotBlank()) Text(
                                "Original Sample No/Lab No: " + report.sourceSampleLabText,
                                fontSize = 11.sp)
                            if (report.department.isNotBlank()) Text(
                                "Source laboratory: " + report.department, fontSize = 11.sp)
                            if (report.reportNumber.isNotBlank()) Text(
                                "Report No.: " + report.reportNumber, fontSize = 11.sp)
                            if (report.reportStage.isNotBlank()) Text(
                                "Report stage: " + report.reportStage, fontSize = 11.sp)
                            Text("Collection date (report index / verified PDF): " +
                                collected.ifBlank { "Not supplied" }, fontSize = 11.sp)
                            Text("Reporting date/time (verified PDF only): " +
                                reported.ifBlank { "Not in this report-index row" }, fontSize = 11.sp)
                            if (expanded) {
                                HorizontalDivider()
                                val uniqueAccessions = parts.map {
                                    it.header.labNumber
                                }.filter(String::isNotBlank).distinct()
                                if (uniqueAccessions.isNotEmpty()) Text(
                                    uniqueAccessions.size.toString() +
                                        " explicitly labelled lab/study accession(s) " +
                                        "inside this source PDF: " +
                                        uniqueAccessions.joinToString(", "),
                                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                val associated = current.results.filter { result ->
                                    result.labNumber.isNotBlank() &&
                                        uniqueAccessions.any { labNo ->
                                            normalizeKey(result.labNumber) ==
                                                normalizeKey(labNo)
                                        }
                                }.map { it.test }.distinct()
                                if (associated.isNotEmpty()) Text(
                                    "Linked investigations: " + associated.joinToString(" · "),
                                    fontSize = 11.sp, color = Color(0xFF102A43))
                                if (parts.isEmpty()) {
                                    Text("Open this PDF to index all readable pages. " +
                                        "Unverified PDF text is never assigned to another specimen.",
                                        fontSize = 11.sp, color = Color(0xFF875B22))
                                } else {
                                    val visibleParts = if (showEntireSource) parts else parts.take(3)
                                    visibleParts.forEach { part ->
                                        HorizontalDivider()
                                        Text("PDF page " + part.page +
                                            if (part.title.isNotBlank()) " · " + part.title else "",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold)
                                        if (part.header.labNumber.isNotBlank()) Text(
                                            "Lab/study: " + part.header.labNumber, fontSize = 10.sp)
                                        if (part.header.collectionDate.isNotBlank()) Text(
                                            "Collected/sent: " + part.header.collectionDate,
                                            fontSize = 10.sp)
                                        if (part.header.reportedDate.isNotBlank()) Text(
                                            "Reported: " + part.header.reportedDate, fontSize = 10.sp)
                                        val sourceValues = remember(part.text) {
                                            PdfClinicalValues.parse(part.text)
                                        }
                                        if (sourceValues.isNotEmpty()) {
                                            Text(sourceValues.size.toString() +
                                                " named PDF-derived laboratory values",
                                                fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                            sourceValues.take(if (showEntireSource) 100 else 8)
                                                .forEach { value ->
                                                    Text(value.parameter + ": " + value.value +
                                                        if (value.unit.isNotBlank()) " " + value.unit else "",
                                                        fontSize = 11.sp)
                                                }
                                        }
                                        Text(if (showEntireSource) part.text.take(4000)
                                            else part.text.take(450) +
                                                if (part.text.length > 450) "…" else "",
                                            fontSize = 11.sp, color = Color(0xFF486581))
                                    }
                                    if (parts.size > 3 || parts.any { it.text.length > 450 }) {
                                        TextButton(onClick = {
                                            showEntireSource = !showEntireSource
                                        }) {
                                            Text(if (showEntireSource) "Collapse PDF text"
                                                else "Show longer PDF excerpts (" + parts.size +
                                                    " sections)")
                                        }
                                    }
                                }
                            }
                            if (report.url.isNotBlank()) {
                                TextButton(onClick = { openReport(report.url) }) {
                                    Text("Open complete original PDF")
                                }
                            } else Text("Metadata-only NIMS report row: no usable PDF URL provided.",
                                fontSize = 11.sp, color = Color(0xFF875B22))
                            if (expanded && report.sourceRow.isNotBlank()) {
                                TextButton(onClick = { showEntireSource = !showEntireSource }) {
                                    Text(if (showEntireSource) "Hide original index row"
                                        else "Show complete source index row")
                                }
                                if (showEntireSource) Text(report.sourceRow,
                                    fontSize = 10.sp, color = Color(0xFF486581))
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ResultRow(row: LabResult) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 20.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.parameter.ifBlank { row.test }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color(0xFF243B53))
                if (row.parameter.isNotBlank() && row.test.isNotBlank()) {
                    Text(row.test, fontSize = 11.sp, color = Color(0xFF829AB1))
                }
                if (row.refRange.isNotBlank()) Text("Ref: ${row.refRange}", fontSize = 10.sp, color = Color(0xFF9AA8B5))
            }
            Column(
                modifier = Modifier.widthIn(max = 118.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    row.valueText + if (row.unit.isNotBlank()) " ${row.unit}" else "",
                    fontWeight = FontWeight.Bold,
                    color = abnormalColor(row.abnormal)
                )
                Text(row.date, fontSize = 10.sp, color = Color(0xFF829AB1))
            }
        }
    }

    private fun tabLabel(tab: ResultsTab): String = when (tab) {
        ResultsTab.OVERVIEW -> "Overview"
        ResultsTab.RECENT -> "Last 7 days"
        ResultsTab.ID -> "ID focus"
        ResultsTab.LABS -> "Labs"
        ResultsTab.MICROBIOLOGY -> "Microbiology"
        ResultsTab.TRENDS -> "Trends"
        ResultsTab.ALL -> "All results"
    }

    private fun isMicro(row: LabResult): Boolean {
        // Source narrative may mention earlier cultures/PCR in radiology or
        // pathology history. Classify by investigation identity, not prose.
        return ClinicalReportRenderer.isMicrobiologyInvestigation(row.test)
    }

    private fun normalizeKey(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "")

    @Composable
    private fun abnormalColor(flag: String): Color {
        val value = flag.lowercase()
        return when {
            "critical" in value -> Color(0xFFB71C1C)
            value == "h" || "high" in value -> Color(0xFFC62828)
            value == "l" || "low" in value -> Color(0xFF1565C0)
            else -> Color(0xFF102A43)
        }
    }

    override fun onDestroy() {
        cancelTimeout()
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        inventoryWorker.shutdownNow()
        diagnosticsWorker.shutdownNow()
        pdfIndexSelection = ""
        engine.destroy()
        super.onDestroy()
    }
}
