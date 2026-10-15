package org.kundakarlab.nimsresults

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
    private var crInput by mutableStateOf("")
    private var activeRequestId = ""
    private var activeCr = ""
    private var pendingPatient = PatientInfo()
    private var pendingCrAfterLogin = ""
    private var currentTimeout: Runnable? = null

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

        root = FrameLayout(this)
        setContentView(root)

        engine = NimsEngine(this, root, vault, this)

        val composeHost = androidx.compose.ui.platform.ComposeView(this).apply {
            setContent { NimsApp() }
        }
        root.addView(
            composeHost,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun requestFetch() {
        val cr = crInput.filter(Char::isDigit)
        if (!cr.matches(Regex("""\d{15}"""))) {
            statusText = "Enter the 15-digit NIMS CR number."
            return
        }
        if (sessionState == SessionState.CONNECTED) {
            startFetch(cr)
        } else {
            pendingCrAfterLogin = cr
            openLogin()
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
        if (cr.matches(Regex("""\d{15}"""))) {
            if (sessionState == SessionState.CONNECTED) startFetch(cr)
            else {
                pendingCrAfterLogin = cr
                openLogin()
            }
        }
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
        runOnUiThread {
            loadStage = LoadStage.ENRICHING_REPORTS
            statusText = "Mapping source reports and PDF links…"
        }

        val cr = activeCr
        val patient = pendingPatient
        worker.execute {
            try {
                val reports = try {
                    reportRepository.load(cr).reports
                } catch (mismatch: IdentityMismatchException) {
                    throw mismatch
                } catch (_: Throwable) {
                    emptyList()
                }
                val next = BulkParser.parse(rawJson, cr, patient, reports)
                runOnUiThread {
                    if (requestId != activeRequestId) return@runOnUiThread
                    bundle = next
                    crInput = cr
                    selectedTab = ResultsTab.OVERVIEW
                    loadStage = LoadStage.READY
                    statusText = "${next.results.size} results · ${next.reports.size} source reports"
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

    private fun openReport(url: String) {
        if (ReportPayloadParser.safeReportUrl(url).isBlank()) return
        startActivity(Intent(this, ReportActivity::class.java).putExtra(ReportActivity.EXTRA_URL, url))
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
        MaterialTheme(colorScheme = colors) {
            Scaffold(
                topBar = { AppHeader() },
                containerColor = MaterialTheme.colorScheme.surface
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    ConnectionPanel()
                    if (loadStage !in setOf(LoadStage.IDLE, LoadStage.READY, LoadStage.ERROR)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    CrPanel()
                    val current = bundle
                    if (current == null) {
                        EmptyHome()
                    } else {
                        PatientStrip(current)
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
        Surface(color = Color(0xFF0C2D4C), shadowElevation = 4.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("NIMS Results", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                    Text("Fast longitudinal investigations · cultures · reports", color = Color(0xFFC8D9E8), fontSize = 12.sp)
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 14.dp, vertical = 8.dp),
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
                        if (loadStage !in setOf(LoadStage.IDLE, LoadStage.READY, LoadStage.ERROR)) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (bundle?.patient?.crNo == localCr) "Refresh" else "Get results")
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
    private fun PatientStrip(current: ClinicalBundle) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF))
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(current.patient.name.ifBlank { "Current patient" }, fontWeight = FontWeight.Bold, fontSize = 19.sp, color = Color(0xFF102A43))
                    Text(
                        listOfNotNull(
                            "CR: ${current.patient.crNo}",
                            current.patient.age.takeIf { it.isNotBlank() }?.let { "Age: $it" },
                            current.patient.sex.takeIf { it.isNotBlank() }?.let { "Sex: $it" },
                            "${current.results.size} results",
                            "${current.reports.size} reports"
                        ).joinToString(" · "),
                        color = Color(0xFF486581),
                        fontSize = 12.sp
                    )
                }
                OutlinedButton(onClick = { refreshCurrent() }) { Text("Refresh") }
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
            latestByParameter.entries.firstOrNull { (key, _) -> aliases.any { key.contains(it) } }?.value?.let { label to it }
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
                    color = abnormalColor(row.abnormal)
                )
                Text(row.date, fontSize = 11.sp, color = Color(0xFF829AB1))
            }
        }
    }

    @Composable
    private fun LabsScreen(current: ClinicalBundle) {
        val groups = current.results.groupBy { it.test.ifBlank { "Other investigations" } }.toList()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(groups, key = { it.first }) { (test, rows) ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(test, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF102A43))
                        Spacer(Modifier.height(6.dp))
                        rows.sortedByDescending { BulkParser.dateKey(it.date) }.take(20).forEachIndexed { index, row ->
                            ResultRow(row)
                            if (index < minOf(rows.size, 20) - 1) HorizontalDivider(color = Color(0xFFE8EEF5))
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MicroScreen(current: ClinicalBundle) {
        val rows = current.results.filter { isMicro(it) }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("Microbiology timeline", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("${rows.size} source-derived microbiology rows", color = Color(0xFF66788A), fontSize = 12.sp)
            }
            if (rows.isEmpty()) {
                item { Text("No microbiology rows were identified in the structured result set.", color = Color(0xFF66788A)) }
            } else {
                items(rows) { row ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(row.test, fontWeight = FontWeight.Bold)
                            Text(row.parameter, color = Color(0xFF486581), fontSize = 12.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(row.valueText, fontSize = 14.sp)
                            Text(row.date, color = Color(0xFF829AB1), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ReportsScreen(current: ClinicalBundle) {
        var query by remember(current.patient.crNo) { mutableStateOf("") }
        val filtered = current.reports.filter {
            query.isBlank() || (it.title + " " + it.date + " " + it.department).contains(query, true)
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
