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
    private var microFilter by mutableStateOf("All")
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

    private fun openLogin(manageSession: Boolean = false) {
        loginLauncher.launch(Intent(this, LoginActivity::class.java)
            .putExtra(LoginActivity.EXTRA_MANAGE_SESSION, manageSession))
    }

    private fun logout() {
        cancelTimeout()
        vault.clear()
        cacheDir.listFiles { file ->
            file.name.startsWith("nims-verified-") && file.name.endsWith(".pdf")
        }?.forEach { it.delete() }
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
                    TextButton(onClick = { openLogin(manageSession = true) }) {
                        Text("NIMS session")
                    }
                    TextButton(onClick = { logout() }) { Text("Clear local") }
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(current.patient.name.ifBlank { "Current patient" },
                        fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        color = Color(0xFF102A43), maxLines = 1)
                    val sex = current.patient.sex
                        .replace(Regex("(?i)age\\s*:.*"), "").trim()
                    Text(
                        listOf(
                            "CR " + current.patient.crNo,
                            current.patient.age,
                            sex,
                            current.results.size.toString() + " results",
                            current.reports.size.toString() + " PDFs"
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        color = Color(0xFF486581), fontSize = 10.sp, maxLines = 2
                    )
                }
                TextButton(onClick = { refreshCurrent() }) {
                    Text("↻", fontSize = 21.sp)
                }
                TextButton(onClick = onToggleTools) {
                    Text("CR ▾", fontSize = 12.sp)
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
    private fun OverviewScreen(current: ClinicalBundle) {
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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            item {
                Text("Clinical review", fontWeight = FontWeight.Bold,
                    fontSize = 17.sp, color = Color(0xFF102A43))
                Text("Latest available report: " + latestDate +
                    " · values may have different collection dates",
                    fontSize = 11.sp, color = Color(0xFF66788A))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(modifier = Modifier.weight(1f),
                        onClick = { microFilter = "Positive"; selectedTab = ResultsTab.MICROBIOLOGY }) {
                        Text("Positive cultures (" + positives.size + ")", fontSize = 11.sp)
                    }
                    OutlinedButton(modifier = Modifier.weight(1f),
                        onClick = { selectedTab = ResultsTab.LABS }) {
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
    private fun LabsScreen(current: ClinicalBundle) {
        var query by remember(current.patient.crNo) { mutableStateOf("") }
        var parameter by remember(current.patient.crNo) { mutableStateOf("All") }
        var period by remember(current.patient.crNo) { mutableStateOf("90D") }
        var columns by remember(current.patient.crNo) { mutableStateOf("4") }
        val nonMicro = remember(current.results) { current.results.filterNot { isMicro(it) } }
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
        val options = remember(nonMicro) {
            nonMicro.filter { it.parameter.isNotBlank() }
                .groupingBy { it.parameter }.eachCount()
                .entries.sortedByDescending { it.value }
                .take(20).map { it.key }
        }
        val filtered = remember(nonMicro, query, parameter, period, latest) {
            nonMicro.filter { row ->
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
        LazyColumn(
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
            items(groups, key = { it.first }) { (test, rows) ->
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
                var selectedValue by remember(current.patient.crNo, test) {
                    mutableStateOf<LabResult?>(null)
                }
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(test + " · " + rows.size + " values", fontWeight = FontWeight.Bold,
                            fontSize = 14.sp, color = Color(0xFF102A43))
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
                                        Text(date.takeLast(5), fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.height(27.dp))
                                        parameters.forEach { (_, values) ->
                                            val value = values.firstOrNull { it.date == date }
                                            Box(
                                                modifier = Modifier.height(44.dp).fillMaxWidth()
                                                    .clickable(enabled = value != null) {
                                                        selectedValue = value
                                                    }.padding(end = 6.dp)
                                            ) {
                                                Text(value?.valueText ?: "—",
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
                        selectedValue?.let { detail ->
                            HorizontalDivider()
                            Text(detail.parameter + " · " + detail.date,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(detail.valueText + (if (detail.unit.isBlank()) ""
                                else " " + detail.unit), fontSize = 13.sp)
                            if (detail.refRange.isNotBlank()) {
                                Text("Reference: " + detail.refRange, fontSize = 11.sp,
                                    color = Color(0xFF66788A))
                            }
                            TextButton(onClick = { selectedValue = null }) { Text("Close detail") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MicroScreen(current: ClinicalBundle) {
        var query by remember(current.patient.crNo) { mutableStateOf("") }
        val grouped = remember(current.results, query, microFilter) {
            // Filter complete culture reports rather than individual rows: this
            // preserves accompanying susceptibility, resistance and notes.
            current.results.filter { isMicro(it) }
                .groupBy { row -> row.test to row.date }.toList()
                .filter { (key, rows) ->
                    val category = CultureReview.status(rows).name
                    (microFilter == "All" || category.equals(microFilter, true)) &&
                        (query.isBlank() || (key.first + " " + key.second + " " +
                            rows.joinToString(" ") { it.parameter + " " + it.valueText })
                            .contains(query, ignoreCase = true))
                }
                .sortedByDescending { BulkParser.dateKey(it.first.second) }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("Microbiology reports", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Select positive cultures or inspect all microbiology reports.",
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
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(date, color = Color(0xFF829AB1), fontSize = 11.sp)
                            val classification = CultureReview.status(rows)
                            val color = when (classification) {
                                CultureStatus.POSITIVE -> Color(0xFFB3261E)
                                CultureStatus.NEGATIVE -> Color(0xFF28744A)
                                CultureStatus.PENDING -> Color(0xFF996100)
                                CultureStatus.OTHER -> Color(0xFF66788A)
                            }
                            Text(classification.name, fontWeight = FontWeight.Bold,
                                fontSize = 10.sp, color = color)
                        }
                        val reportRows = clinical.filter { row ->
                            row.parameter.equals("Result", true) ||
                            row.valueText.contains("CULTURE REPORT", true) ||
                            row.valueText.contains("SENSITIVITY REPORT", true)
                        }
                        val narrative = reportRows.joinToString("\n") { it.valueText }
                        val parts = CultureNarrative.parse(narrative)
                        if (parts.culture.isNotBlank()) {
                            Text("Culture finding", color = Color(0xFF486581),
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
                        if (narrative.isNotBlank() || clinical.any { it.valueText.length > 120 }) {
                            TextButton(onClick = { expanded = !expanded }) {
                                Text(if (expanded) "Hide original narrative" else "Full culture / AST details")
                            }
                            if (expanded && narrative.isNotBlank()) {
                                Text("Original result text", fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp, color = Color(0xFF486581))
                                Text(narrative, fontSize = 12.sp)
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
            .matches(parameter.trim().trimEnd(':').trim())

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
        val series = remember(current.results) {
            current.results.filter { ClinicalResultRules.isTrendEligible(it) }
                .groupBy { ClinicalResultRules.parameterKey(it) }
                .mapValues { (_, values) ->
                    values.sortedByDescending { BulkParser.dateKey(it.date) }
                        .distinctBy { it.date }.sortedBy { BulkParser.dateKey(it.date) }
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
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(10.dp),
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
            if (day != null && value != null) day to value else null
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
