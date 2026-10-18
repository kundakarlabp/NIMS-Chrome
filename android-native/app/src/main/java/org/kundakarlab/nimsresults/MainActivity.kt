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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : ComponentActivity(), NimsEngine.Listener {
    private lateinit var root: FrameLayout
    private lateinit var engine: NimsEngine
    private lateinit var vault: SessionVault
    private val reportRepository by lazy { ReportRepository() }
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var sessionState by mutableStateOf(SessionState.UNKNOWN)
    private var loadStage by mutableStateOf(LoadStage.IDLE)
    private var statusText by mutableStateOf("Checking saved NIMS session…")
    private var bundle by mutableStateOf<ClinicalBundle?>(null)
    private var selectedTab by mutableStateOf(ResultsTab.OVERVIEW)
    private var reportQueryPrefill by mutableStateOf("")
    private var crInput by mutableStateOf("")
    private var appLockEnabled by mutableStateOf(false)
    private var appUnlocked by mutableStateOf(true)
    private var leftAt = 0L
    private var unlockPromptActive = false
    private var activeRequestId = ""
    private var activeCr = ""
    private var pendingPatient = PatientInfo()
    private var pendingCrAfterLogin = ""
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
                    if (cr.matches(Regex("""\d{15}"""))) startFetch(cr)
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
        vault = SessionVault(this)
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
        if (appLockEnabled) handler.post { requestDeviceUnlock() }
    }

    private fun toggleAppLock() {
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

    override fun onStop() {
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

    private fun requestFetch() {
        val cr = crInput.filter(Char::isDigit)
        if (!cr.matches(Regex("""\d{15}"""))) {
            statusText = "Enter the 15-digit NIMS CR number."
            return
        }
        if (sessionState == SessionState.CONNECTED) {
            startFetch(cr)
            return
        }

        pendingCrAfterLogin = cr
        sessionState = SessionState.CHECKING
        loadStage = LoadStage.CHECKING_SESSION
        statusText = "Checking the existing NIMS session…"
        engine.checkSession { state ->
            runOnUiThread {
                if (pendingCrAfterLogin != cr) return@runOnUiThread
                if (state == SessionState.CONNECTED) {
                    pendingCrAfterLogin = ""
                    startFetch(cr)
                } else {
                    loadStage = LoadStage.IDLE
                    statusText = if (state == SessionState.EXPIRED) {
                        "NIMS session expired. Login once to continue."
                    } else {
                        "NIMS login is required."
                    }
                    openLogin()
                }
            }
        }
    }

    private fun startFetch(cr: String) {
        cancelTimeout()
        activeCr = cr
        activeRequestId = "native-" + UUID.randomUUID().toString()
        pendingPatient = PatientInfo(crNo = cr)
        loadStage = LoadStage.OPENING_TRENDS
        statusText = "Opening NIMS Investigation Trends…"
        engine.run(cr, activeRequestId)

        val timeout = Runnable {
            if (loadStage != LoadStage.READY && activeRequestId.isNotBlank()) {
                loadStage = LoadStage.ERROR
                statusText = "NIMS retrieval timed out. Your existing results remain available; retry when the NIMS session is responsive."
            }
        }
        currentTimeout = timeout
        handler.postDelayed(timeout, 75_000)
    }

    private fun refreshCurrent() {
        val cr = bundle?.patient?.crNo?.takeIf { it.matches(Regex("""\d{15}""")) } ?: crInput
        crInput = cr
        if (cr.matches(Regex("""\d{15}"""))) requestFetch()
    }

    private fun openLogin() {
        loginLauncher.launch(Intent(this, LoginActivity::class.java))
    }

    private fun logout() {
        cancelTimeout()
        vault.clear()
        bundle = null
        sessionState = SessionState.LOGIN_REQUIRED
        loadStage = LoadStage.IDLE
        statusText = "Logged out from NIMS on this app."
        engine.reloadSession()
    }

    private fun cancelTimeout() {
        currentTimeout?.let { handler.removeCallbacks(it) }
        currentTimeout = null
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
                    SessionState.ERROR -> "NIMS session check failed."
                    SessionState.UNKNOWN -> "NIMS session not checked yet."
                }
                if (loadStage == LoadStage.CHECKING_SESSION && state != SessionState.CHECKING) loadStage = LoadStage.IDLE
            }
        }
    }

    override fun onStage(requestId: String, stage: String) {
        if (requestId != activeRequestId) return
        runOnUiThread {
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
        pendingPatient = patient.copy(crNo = activeCr)
    }

    override fun onBulk(requestId: String, rawJson: String) {
        if (requestId != activeRequestId) return
        cancelTimeout()
        val cr = activeCr
        val patient = pendingPatient
        // Normalize and display authenticated Custom Sheet values immediately.
        // An optional report-index fetch must never block clinical lab review.
        worker.execute {
            try {
                val initial = BulkParser.parse(rawJson, cr, patient, emptyList())
                runOnUiThread {
                    if (requestId != activeRequestId) return@runOnUiThread
                    bundle = initial
                    crInput = cr
                    selectedTab = ResultsTab.OVERVIEW
                    reportQueryPrefill = ""
                    loadStage = LoadStage.READY
                    statusText = initial.results.size.toString() + " results · loading report links…"
                }
                val inventory = try {
                    reportRepository.load(cr)
                } catch (mismatch: IdentityMismatchException) {
                    runOnUiThread {
                        if (requestId == activeRequestId) {
                            statusText = "Report identity mismatch. PDF links withheld."
                        }
                    }
                    return@execute
                } catch (_: Throwable) {
                    null
                }
                runOnUiThread {
                    if (requestId != activeRequestId) return@runOnUiThread
                    // No patient data is retained from the REST/HTML reply beyond validated links.
                    bundle = initial.copy(reports = inventory?.reports.orEmpty())
                    statusText = initial.results.size.toString() + " results · " +
                        (inventory?.reports?.size ?: 0) + " source reports"
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    if (requestId != activeRequestId) return@runOnUiThread
                    loadStage = LoadStage.ERROR
                    statusText = error.message ?: "Could not normalize NIMS results."
                }
            }
        }
    }

    override fun onError(requestId: String, message: String) {
        if (requestId.isNotBlank() && requestId != activeRequestId) return
        cancelTimeout()
        runOnUiThread {
            val authLike = Regex("(?i)auth|login|session").containsMatchIn(message)
            if (authLike) {
                sessionState = SessionState.LOGIN_REQUIRED
                pendingCrAfterLogin = activeCr
                loadStage = LoadStage.IDLE
                statusText = "NIMS session needs login. Complete CAPTCHA once; this CR will resume."
                openLogin()
            } else {
                loadStage = LoadStage.ERROR
                statusText = message
            }
        }
    }

    private fun diagnosticsText(): String {
        val current = bundle
        return buildString {
            appendLine("NIMS Results diagnostics")
            appendLine("app.version=${BuildConfig.VERSION_NAME}")
            appendLine("app.sessionState=$sessionState")
            appendLine("app.loadStage=$loadStage")
            appendLine("app.status=" + statusText.replace(Regex("""\b\d{15}\b"""), "[CR redacted]"))
            appendLine("app.crInputLength=${crInput.length}")
            appendLine("app.activeCrPresent=${activeCr.isNotBlank()}")
            appendLine("app.pendingCrAfterLogin=${pendingCrAfterLogin.isNotBlank()}")
            appendLine("app.hasBundle=${current != null}")
            appendLine("app.resultCount=${current?.results?.size ?: 0}")
            appendLine("app.reportCount=${current?.reports?.size ?: 0}")
            append(engine.safeDiagnostics())
        }
    }

    private fun copyDiagnostics() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NIMS diagnostics", diagnosticsText()))
        Toast.makeText(this, "Safe diagnostics copied", Toast.LENGTH_SHORT).show()
    }

    private fun openReport(url: String) {
        if (ReportPayloadParser.safeReportUrl(url).isBlank()) return
        startActivity(Intent(this, ReportActivity::class.java).putExtra(ReportActivity.EXTRA_URL, url))
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
                    if (current == null) {
                        EmptyHome()
                    } else {
                        PatientStrip(current, onToggleTools = { toolsOpen = !toolsOpen })
                        NativeTabs()
                        Box(modifier = Modifier.fillMaxSize()) {
                            when (selectedTab) {
                                ResultsTab.OVERVIEW -> OverviewScreen(current)
                                ResultsTab.LABS -> LabsScreen(current)
                                ResultsTab.MICROBIOLOGY -> MicroScreen(current)
                                ResultsTab.REPORTS -> ReportsScreen(current)
                                ResultsTab.TRENDS -> TrendsScreen(current)
                                ResultsTab.ALL -> AllResultsScreen(current)
                            }
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
                Column(modifier = Modifier.weight(1f)) {
                    Text("NIMS Results", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(statusText, fontSize = 13.sp, color = Color(0xFF33475B))
                }
                if (sessionState != SessionState.CONNECTED) {
                    Button(onClick = { openLogin() }) { Text("Login") }
                } else {
                    TextButton(onClick = { engine.checkSession() }) { Text("Check") }
                    TextButton(onClick = { logout() }) { Text("Logout") }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { toggleAppLock() }) {
                    Text(if (appLockEnabled) "Device lock: On" else "Enable device lock",
                        fontSize = 12.sp)
                }
                TextButton(onClick = { copyDiagnostics() }) {
                    Text("Copy diagnostics", fontSize = 12.sp)
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
    private fun PatientStrip(current: ClinicalBundle, onToggleTools: () -> Unit) {
        Surface(color = Color(0xFFEFF6FF)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(current.patient.name.ifBlank { "Current patient" },
                        fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        color = Color(0xFF102A43), maxLines = 1)
                    Text("CR " + current.patient.crNo,
                        color = Color(0xFF486581), fontSize = 11.sp, maxLines = 1)
                    val sex = current.patient.sex.replace(Regex("(?i)age\\s*:.*"), "").trim()
                    val demographics = listOf(current.patient.age, sex)
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    Text(listOf(demographics,
                        current.results.size.toString() + " results",
                        current.reports.size.toString() + " PDFs")
                        .filter { it.isNotBlank() }.joinToString(" · "),
                        color = Color(0xFF486581), fontSize = 11.sp, maxLines = 1)
                }
                TextButton(onClick = { refreshCurrent() }) { Text("Refresh", fontSize = 12.sp) }
                TextButton(onClick = onToggleTools) { Text("CR / Menu", fontSize = 12.sp) }
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
    private fun OverviewScreen(current: ClinicalBundle) {
        val latestByParameter = current.results
            .groupBy { normalizeKey(it.parameter.ifBlank { it.test }) }
            .mapValues { (_, rows) -> rows.maxByOrNull { BulkParser.dateKey(it.date) }!! }

        val wanted = listOf(
            "WBC / TLC" to listOf("wbc", "tlc", "totalleukocytecount", "whitebloodcell"),
            "ANC" to listOf("anc", "absoluteneutrophilcount"),
            "Hemoglobin" to listOf("hemoglobin", "haemoglobin", "hb"),
            "Platelets" to listOf("platelet", "plateletcount"),
            "Creatinine" to listOf("creatinine"),
            "CRP" to listOf("crp", "creactiveprotein"),
            "Procalcitonin" to listOf("procalcitonin", "pct"),
            "Sodium" to listOf("sodium", "na")
        )
        val cards = wanted.mapNotNull { (label, aliases) ->
            latestByParameter.entries.firstOrNull { (key, _) -> aliases.any { key.contains(it) } }?.value?.takeIf { it.numericValue != null && it.valueText.length <= 24 }?.let { label to it }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("Latest clinical snapshot", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color(0xFF102A43))
            }
            items(cards.chunked(2)) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    pair.forEach { (label, row) ->
                        MetricCard(label, row, Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Recent results", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            items(current.results.take(15)) { row ->
                ResultRow(row)
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
    private fun LabsScreen(current: ClinicalBundle) {
        // Date-by-parameter matrix similar to the existing desktop dashboard.
        // Preserve every original result for full-detail inspection and trends.
        var query by remember(current.patient.crNo) { mutableStateOf("") }
        val filtered = remember(current.results, query) {
            current.results.filter { row ->
                !isMicro(row) &&
                    (query.isBlank() || listOf(row.test, row.parameter, row.valueText, row.date)
                        .any { it.contains(query, true) })
            }
        }
        val groups = remember(filtered) {
            filtered.groupBy { it.test.ifBlank { "Other investigations" } }.toList()
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("Laboratory matrix", fontWeight = FontWeight.Bold, fontSize = 18.sp,
                    color = Color(0xFF102A43))
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Find Hb, CBC, creatinine, date…") }
                )
                Text("Columns show the latest four dates. Tap a value for complete details.",
                    fontSize = 11.sp, color = Color(0xFF66788A))
            }
            items(groups, key = { it.first }) { (test, rows) ->
                val dates = remember(rows) {
                    rows.map { it.date }.distinct().sortedByDescending { BulkParser.dateKey(it) }.take(4)
                }
                val parameters = remember(rows) {
                    rows.groupBy { it.parameter.ifBlank { it.test } }.toList()
                }
                var selectedValue by remember(current.patient.crNo, test) {
                    mutableStateOf<LabResult?>(null)
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(test + " · " + rows.size + " values", fontWeight = FontWeight.Bold,
                            fontSize = 15.sp, color = Color(0xFF102A43))
                        Spacer(Modifier.height(8.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.width(135.dp)) {
                                Text("Parameter", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.height(32.dp))
                                parameters.forEach { (parameter, values) ->
                                    val unit = values.firstOrNull()?.unit.orEmpty()
                                    Column(modifier = Modifier.height(52.dp).padding(end = 4.dp)) {
                                        Text(parameter, fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium, maxLines = 2)
                                        if (unit.isNotBlank()) {
                                            Text(unit, fontSize = 10.sp,
                                                color = Color(0xFF829AB1), maxLines = 1)
                                        }
                                    }
                                }
                            }
                            Row(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                                dates.forEach { date ->
                                    Column(modifier = Modifier.width(100.dp)) {
                                        Text(date, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.height(32.dp), maxLines = 1)
                                        parameters.forEach { (_, values) ->
                                            val value = values.firstOrNull { it.date == date }
                                            Box(
                                                modifier = Modifier
                                                    .height(52.dp)
                                                    .fillMaxWidth()
                                                    .clickable(enabled = value != null) {
                                                        selectedValue = value
                                                    }
                                                    .padding(end = 7.dp)
                                            ) {
                                                Text(value?.let {
                                                    it.valueText
                                                } ?: "—",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (value != null)
                                                        abnormalColor(value.abnormal) else Color(0xFF829AB1),
                                                    maxLines = 2)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val detail = selectedValue
                        if (detail != null) {
                            HorizontalDivider()
                            Spacer(Modifier.height(5.dp))
                            Text(detail.parameter.ifBlank { detail.test } + " · " + detail.date,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(detail.valueText + (if (detail.unit.isBlank()) "" else " " + detail.unit),
                                fontSize = 13.sp)
                            if (detail.refRange.isNotBlank()) {
                                Text("Reference: " + detail.refRange,
                                    fontSize = 11.sp, color = Color(0xFF66788A))
                            }
                            TextButton(onClick = { selectedValue = null }) { Text("Close detail") }
                        }
                    }
                }
            }
            if (groups.isEmpty()) {
                item { Text("No matching laboratory values.", color = Color(0xFF66788A)) }
            }
        }
    }

    @Composable
    private fun MicroScreen(current: ClinicalBundle) {
        var query by remember(current.patient.crNo) { mutableStateOf("") }
        val grouped = remember(current.results, query) {
            current.results.filter { row ->
                isMicro(row) && (query.isBlank() ||
                    (row.test + " " + row.parameter + " " + row.valueText + " " + row.date)
                        .contains(query, ignoreCase = true))
            }.groupBy { row -> row.test to row.date }
                .toList().sortedByDescending { BulkParser.dateKey(it.first.second) }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("Microbiology reports", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Reports are grouped by test and date; laboratory processing metadata is hidden by default.",
                    color = Color(0xFF66788A), fontSize = 11.sp)
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("Search organism, specimen or date") }
                )
            }
            if (grouped.isEmpty()) {
                item { Text("No matching microbiology reports.", color = Color(0xFF66788A)) }
            }
            items(grouped, key = { it.first.first + "|" + it.first.second }) { (key, rows) ->
                val (test, date) = key
                val clinical = rows.filterNot { isMicroTechnicalField(it.parameter) }
                    .sortedWith(compareBy<LabResult> {
                        if (it.parameter.equals("Result", ignoreCase = true)) 0
                        else if (Regex("(?i)organism|sensitivity|susceptib|antibiotic|mic")
                                .containsMatchIn(it.parameter)) 1 else 2
                    })
                var expanded by remember(current.patient.crNo, key) { mutableStateOf(false) }
                var showMetadata by remember(current.patient.crNo, key) { mutableStateOf(false) }
                val exact = current.reports.firstOrNull { report ->
                    BulkParser.isoDate(report.date) == date &&
                        normalizeKey(report.title) == normalizeKey(test) &&
                        report.url.isNotBlank()
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(test, fontWeight = FontWeight.Bold, color = Color(0xFF102A43),
                            fontSize = 15.sp)
                        Text(date, color = Color(0xFF829AB1), fontSize = 11.sp)
                        clinical.forEach { row ->
                            if (row.parameter.isNotBlank()) {
                                Text(row.parameter, color = Color(0xFF486581),
                                    fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            val value = row.valueText
                            Text(
                                value,
                                fontSize = 13.sp,
                                maxLines = if (expanded) Int.MAX_VALUE else 4,
                                color = Color(0xFF243B53)
                            )
                            Spacer(Modifier.height(5.dp))
                        }
                        if (clinical.any { it.valueText.length > 120 }) {
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(if (expanded) "Collapse narrative" else "Read full culture details")
                            }
                        }
                        if (rows.size > clinical.size) {
                            TextButton(onClick = { showMetadata = !showMetadata }) {
                                Text(if (showMetadata) "Hide laboratory metadata"
                                     else "Lab metadata (" + (rows.size - clinical.size) + ")")
                            }
                            if (showMetadata) {
                                rows.filter { isMicroTechnicalField(it.parameter) }.forEach { row ->
                                    Text(row.parameter + ": " + row.valueText,
                                        fontSize = 11.sp, color = Color(0xFF66788A))
                                }
                            }
                        }
                        if (exact != null) {
                            OutlinedButton(onClick = { openReport(exact.url) }) {
                                Text("Source PDF / susceptibility")
                            }
                        } else if (current.reports.isNotEmpty()) {
                            TextButton(onClick = {
                                reportQueryPrefill = date
                                selectedTab = ResultsTab.REPORTS
                            }) { Text("Browse source PDFs for this date") }
                        }
                        if (clinical.none { row ->
                                Regex("(?i)sensitivity|susceptib|antibiogram|\\bmic\\b|resistan")
                                    .containsMatchIn(row.parameter + " " + row.valueText)
                            }) {
                            Text("Susceptibility not separately identified in structured rows; check the original report.",
                                fontSize = 10.sp, color = Color(0xFF66788A))
                        }
                    }
                }
            }
        }
    }

    private fun isMicroTechnicalField(parameter: String): Boolean =
        Regex("(?i)^(test\\s*done\\s*by|sample\\s*processed|processed\\s*by|entered\\s*by|verified\\s*by|machine\\s*(?:id|no)|lab\\s*id|technician)$")
            .matches(parameter.trim())

    @Composable
    private fun ReportsScreen(current: ClinicalBundle) {
        var query by remember(current.patient.crNo, reportQueryPrefill) { mutableStateOf(reportQueryPrefill) }
        val filtered = current.reports.filter {
            query.isBlank() || (it.title + " " + it.date + " " +
                BulkParser.isoDate(it.date) + " " + it.department).contains(query, true)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("Source reports", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Find test or date") },
                    singleLine = true
                )
            }
            if (filtered.isEmpty()) {
                item { Text("No source reports were mapped for this retrieval.", color = Color(0xFF66788A)) }
            } else {
                items(filtered, key = { it.id + it.date }) { report ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(report.title, fontWeight = FontWeight.Bold, color = Color(0xFF102A43))
                                Text(listOf(report.date, report.department).filter { it.isNotBlank() }.joinToString(" · "), color = Color(0xFF66788A), fontSize = 12.sp)
                            }
                            if (report.url.isNotBlank()) {
                                Button(onClick = { openReport(report.url) }) { Text("Open PDF") }
                            } else {
                                Text("Link unavailable", color = Color(0xFF829AB1), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TrendsScreen(current: ClinicalBundle) {
        val series = current.results
            .filter { it.numericValue != null && it.parameter.isNotBlank() }
            .groupBy { it.parameter }
            .filterValues { it.size >= 2 }
            .toList()
            .sortedByDescending { it.second.size }

        var selected by remember(current.patient.crNo) { mutableStateOf(series.firstOrNull()?.first.orEmpty()) }
        val selectedRows = series.firstOrNull { it.first == selected }?.second
            ?.sortedBy { BulkParser.dateKey(it.date) }
            .orEmpty()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Text("Longitudinal trends", fontWeight = FontWeight.Bold, fontSize = 18.sp) }
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    series.take(18).forEach { (name, rows) ->
                        FilterChip(
                            selected = selected == name,
                            onClick = { selected = name },
                            label = { Text("$name (${rows.size})") }
                        )
                    }
                }
            }
            if (selectedRows.size >= 2) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(selected, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            TrendChart(selectedRows, Modifier.fillMaxWidth().height(180.dp))
                            Spacer(Modifier.height(6.dp))
                            val last = selectedRows.last()
                            val first = selectedRows.first()
                            Text("${first.date}: ${first.valueText}  →  ${last.date}: ${last.valueText}", fontSize = 12.sp, color = Color(0xFF486581))
                        }
                    }
                }
                items(selectedRows.reversed()) { row -> ResultRow(row) }
            } else {
                item { Text("No numeric parameter has enough measurements for a trend.", color = Color(0xFF66788A)) }
            }
        }
    }

    @Composable
    private fun TrendChart(rows: List<LabResult>, modifier: Modifier = Modifier) {
        val values = rows.mapNotNull { it.numericValue }
        if (values.size < 2) return
        val min = values.minOrNull() ?: return
        val max = values.maxOrNull() ?: return
        val range = (max - min).takeIf { it != 0.0 } ?: 1.0
        Canvas(modifier = modifier.background(Color(0xFFF7FAFC), RoundedCornerShape(10.dp)).border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(10.dp))) {
            val pad = 18f
            val usableW = size.width - 2 * pad
            val usableH = size.height - 2 * pad
            val path = Path()
            values.forEachIndexed { index, value ->
                val x = pad + usableW * index / (values.size - 1).coerceAtLeast(1)
                val y = pad + usableH * (1f - ((value - min) / range).toFloat())
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                drawCircle(Color(0xFF0B5CAB), radius = 4f, center = Offset(x, y))
            }
            drawPath(path, Color(0xFF0B5CAB), style = Stroke(width = 3f))
        }
    }

    @Composable
    private fun AllResultsScreen(current: ClinicalBundle) {
        var query by remember(current.patient.crNo) { mutableStateOf("") }
        val filtered = current.results.filter {
            query.isBlank() || (it.test + " " + it.parameter + " " + it.valueText + " " + it.date).contains(query, true)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Text("All results", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search test, parameter, value or date") },
                    singleLine = true
                )
            }
            items(filtered, key = { it.id }) { row -> ResultRow(row) }
        }
    }

    @Composable
    private fun ResultRow(row: LabResult) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.parameter.ifBlank { row.test }, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Color(0xFF243B53))
                if (row.parameter.isNotBlank() && row.test.isNotBlank()) {
                    Text(row.test, fontSize = 11.sp, color = Color(0xFF829AB1))
                }
                if (row.refRange.isNotBlank()) Text("Ref: ${row.refRange}", fontSize = 10.sp, color = Color(0xFF9AA8B5))
            }
            Column(horizontalAlignment = Alignment.End) {
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
        ResultsTab.LABS -> "Labs"
        ResultsTab.MICROBIOLOGY -> "Microbiology"
        ResultsTab.REPORTS -> "Reports"
        ResultsTab.TRENDS -> "Trends"
        ResultsTab.ALL -> "All results"
    }

    private fun isMicro(row: LabResult): Boolean {
        val text = (row.test + " " + row.parameter + " " + row.valueText).lowercase()
        return Regex("""culture|microbiology|organism|sensitivity|susceptib|no growth|growth|gram stain|pcr|cbnaat|genexpert""").containsMatchIn(text)
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
        engine.destroy()
        super.onDestroy()
    }
}
