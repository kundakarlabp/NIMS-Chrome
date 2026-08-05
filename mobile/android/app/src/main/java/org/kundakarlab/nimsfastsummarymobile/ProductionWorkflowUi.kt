package org.kundakarlab.nimsfastsummarymobile

import android.view.ViewGroup
import android.webkit.WebView
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
import org.kundakarlab.nimsfastsummarymobile.domain.recovery.ClinicianCorrection
import org.kundakarlab.nimsfastsummarymobile.domain.recovery.ReportIssue
import org.kundakarlab.nimsfastsummarymobile.domain.recovery.ReportIssueKind
import org.kundakarlab.nimsfastsummarymobile.ui.models.Abnormality
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiCultureRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiLabTrendRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSourceReport
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary

@Composable
internal fun ProductionWorkflowTheme(content: @Composable () -> Unit) {
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
internal fun ProductionWorkflowApp(
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
    onUndoCorrection: (String) -> Unit,
    onIgnoreIssue: (String) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val reviewVisible = summary != null && phase in setOf(ProductionPhase.REVIEW, ProductionPhase.PROCESSING)

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF171912)).padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "NIMS Results",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
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
                phase in setOf(ProductionPhase.LOGIN, ProductionPhase.SESSION_EXPIRED) -> LoginScreen(
                    webView,
                    status,
                    onContinueLogin,
                    onLogoutOtherSessions
                )
                reviewVisible -> {
                    Column(Modifier.fillMaxSize()) {
                        ReviewStatusHeader(activeCrNumber, status, done, total, processing, issues.size)
                        Box(Modifier.weight(1f)) {
                            when (selectedTab) {
                                0 -> OverviewScreen(summary, status)
                                1 -> LabsScreen(summary.labTrends)
                                2 -> CulturesScreen(summary.cultures, onOpenReport)
                                3 -> ReportsScreen(
                                    summary.sourceReports,
                                    issues,
                                    corrections,
                                    onRetryOne,
                                    onOpenReport,
                                    onManualCorrection,
                                    onUndoCorrection,
                                    onIgnoreIssue
                                )
                                else -> IssuesScreen(
                                    issues,
                                    corrections,
                                    onRetryOne,
                                    onRetryAll,
                                    onLoginAgain,
                                    onOpenReport,
                                    onManualCorrection,
                                    onUndoCorrection,
                                    onIgnoreIssue
                                )
                            }
                        }
                        HiddenTransport(webView)
                    }
                }
                phase == ProductionPhase.PROCESSING -> ProcessingScreen(done, total, status, issues.size, onRetryAll, webView)
                else -> CrScreen(crNumber, onCrChange, status, crReady, onFetch, onLoginAgain, onCopyLogs, webView)
            }
        }
    }
}

@Composable
private fun LoginScreen(
    webView: WebView,
    status: String,
    onContinue: () -> Unit,
    onLogoutOthers: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("NIMS login", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Enter user ID, password and captcha. Credentials are not stored by this app.")
        ReusableNimsWebView(webView, Modifier.fillMaxWidth().weight(1f))
        Text(status, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onContinue, modifier = Modifier.weight(1f)) { Text("Continue") }
            OutlinedButton(onClick = onLogoutOthers, modifier = Modifier.weight(1f)) { Text("Logout other sessions") }
        }
    }
}

@Composable
private fun CrScreen(
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
        Text(
            if (ready) "NIMS session ready" else "Preparing the authenticated CR module…",
            color = if (ready) MaterialTheme.colorScheme.secondary else Color.DarkGray
        )
        if (!ready) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedTextField(
            value = cr,
            onValueChange = onChange,
            label = { Text("CR number") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = onFetch, enabled = ready && cr.length >= 6, modifier = Modifier.fillMaxWidth()) {
            Text("Fetch results")
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedButton(onClick = onLoginAgain) { Text("Login again") } }
            item { OutlinedButton(onClick = onCopyLogs) { Text("Copy logs") } }
        }
        HiddenTransport(webView)
    }
}

@Composable
private fun ProcessingScreen(
    done: Int,
    total: Int,
    status: String,
    failed: Int,
    onRetry: () -> Unit,
    webView: WebView
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(status, fontWeight = FontWeight.Bold)
        Text(if (total > 0) "$done of $total reports" else "Preparing reports")
        if (total > 0) {
            LinearProgressIndicator(
                progress = { done.toFloat() / total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
            )
        }
        if (failed > 0) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.padding(top = 12.dp)) {
                Text("Retry failed ($failed)")
            }
        }
        HiddenTransport(webView)
    }
}

@Composable
private fun ReusableNimsWebView(webView: WebView, modifier: Modifier) {
    AndroidView(
        factory = {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView
        },
        modifier = modifier
    )
}

@Composable
private fun HiddenTransport(webView: WebView) {
    ReusableNimsWebView(webView, Modifier.size(1.dp).alpha(0.01f))
}

@Composable
private fun ReviewStatusHeader(
    activeCr: String,
    status: String,
    done: Int,
    total: Int,
    processing: Boolean,
    issueCount: Int
) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFF3F6FA)).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (activeCr.isBlank()) "Patient review" else "CR $activeCr",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (issueCount > 0) Text("$issueCount issue(s)", color = MaterialTheme.colorScheme.error)
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (processing && total > 0) {
            LinearProgressIndicator(
                progress = { done.toFloat() / total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp)
            )
        }
    }
}

@Composable
private fun OverviewScreen(summary: UiSummary, status: String) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Overview", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            ReviewCard {
                Text(
                    summary.patientSnapshot.identityLine.ifBlank { "Patient identity will appear when available" },
                    fontWeight = FontWeight.Bold
                )
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
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LabsScreen(rows: List<UiLabTrendRow>) {
    var panel by remember { mutableStateOf("All") }
    val filtered = rows.filter { panel == "All" || clinicalPanel(it.parameter) == panel }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Laboratory results", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf("All", "Hemogram", "Renal", "Liver", "Inflammatory", "Molecular", "Other")) { value ->
                    OutlinedButton(onClick = { panel = value }) {
                        Text((if (panel == value) "✓ " else "") + value)
                    }
                }
            }
        }
        if (filtered.isEmpty()) item { ReviewCard { Text("No results in this panel") } }
        items(filtered) { CompactLabCard(it) }
    }
}

@Composable
private fun CompactLabCard(row: UiLabTrendRow) {
    ReviewCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.parameter, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(
                row.latestValue,
                fontWeight = FontWeight.Bold,
                color = if (row.abnormality in setOf(Abnormality.HIGH, Abnormality.LOW, Abnormality.CRITICAL)) {
                    MaterialTheme.colorScheme.error
                } else {
                    Color.Unspecified
                }
            )
        }
        Text(row.latestDate, style = MaterialTheme.typography.bodySmall)
        if (row.previousValue != null) {
            Text("Previous ${row.previousValue} · ${row.trendText}", style = MaterialTheme.typography.bodySmall)
        }
        if (row.history.size > 2) {
            Text("${row.history.size} dated values available", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CulturesScreen(rows: List<UiCultureRow>, onOpenReport: (String, String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Cultures", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        if (rows.isEmpty()) item { ReviewCard { Text("No culture observations were parsed") } }
        items(rows) { row ->
            CompactCultureCard(row) {
                onOpenReport(row.sourceKey, row.sourceReportName.ifBlank { row.organism.ifBlank { "Culture report" } })
            }
        }
    }
}

@Composable
private fun CompactCultureCard(row: UiCultureRow, onOpen: (() -> Unit)?) {
    ReviewCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.organism.ifBlank { row.growth.ifBlank { row.status.replace('_', ' ') } },
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                row.status.replace('_', ' '),
                fontWeight = FontWeight.Bold,
                color = if (row.status == "growth_detected") MaterialTheme.colorScheme.error else Color.Unspecified
            )
        }
        Text(
            listOf(row.site.ifBlank { row.specimen }, row.collectionDate, row.reportStage)
                .filter(String::isNotBlank)
                .joinToString(" · ")
        )
        sensitivityGroups(row.sensitivitySummary).forEach { (label, value) ->
            Text(
                "$label: $value",
                style = MaterialTheme.typography.bodySmall,
                color = when (label) {
                    "R" -> MaterialTheme.colorScheme.error
                    "S" -> Color(0xFF1B6E2C)
                    else -> Color(0xFF9A5A00)
                }
            )
        }
        if (row.comment.isNotBlank()) Text(row.comment, style = MaterialTheme.typography.bodySmall)
        if (onOpen != null && row.sourceKey.isNotBlank()) {
            OutlinedButton(onClick = onOpen) { Text("Open report") }
        }
    }
}

@Composable
private fun ReportsScreen(
    reports: List<UiSourceReport>,
    issues: List<ReportIssue>,
    corrections: List<ClinicianCorrection>,
    onRetry: (String) -> Unit,
    onOpen: (String, String) -> Unit,
    onCorrection: (String, String, String, String) -> Unit,
    onUndo: (String) -> Unit,
    onIgnore: (String) -> Unit
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
            ReportCard(
                report,
                issueById[report.sourceKey],
                corrections.filter { it.reportId == report.sourceKey },
                onRetry,
                onOpen,
                onCorrection,
                onUndo,
                onIgnore
            )
        }
        items(issueOnly) { issue ->
            IssueOnlyReportCard(
                issue,
                corrections.filter { it.reportId == issue.reportId },
                onRetry,
                onOpen,
                onCorrection,
                onUndo,
                onIgnore
            )
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
    onUndo: (String) -> Unit,
    onIgnore: (String) -> Unit
) {
    var correcting by remember(report.sourceKey) { mutableStateOf(false) }
    var field by remember(report.sourceKey) { mutableStateOf("") }
    var value by remember(report.sourceKey) { mutableStateOf("") }
    var unit by remember(report.sourceKey) { mutableStateOf("") }
    ReviewCard(container = if (issue != null) Color(0xFFFFF2F0) else MaterialTheme.colorScheme.surfaceVariant) {
        Row {
            Column(Modifier.weight(1f)) {
                Text(report.reportName, fontWeight = FontWeight.Bold)
                Text(report.dateSent, style = MaterialTheme.typography.bodySmall)
            }
            Text(if (issue != null) "Needs attention" else report.type.uppercase(), style = MaterialTheme.typography.labelMedium)
        }
        Text(
            issue?.userMessage ?: when {
                report.results.isNotEmpty() -> "${report.results.size} structured result(s)"
                report.cultureCount > 0 -> "${report.cultureCount} culture observation(s)"
                else -> "Report available"
            }
        )
        corrections.forEach {
            Text(
                "Clinician entered · ${it.field}: ${it.value} ${it.unit}".trim(),
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { OutlinedButton(onClick = { onOpen(report.sourceKey, report.reportName) }) { Text("Open report") } }
            if (issue?.retryable == true) item { Button(onClick = { onRetry(report.sourceKey) }) { Text("Retry") } }
            if (issue != null) item { OutlinedButton(onClick = { correcting = !correcting }) { Text("Enter result") } }
            if (issue != null) item { TextButton(onClick = { onIgnore(report.sourceKey) }) { Text("Ignore") } }
            if (corrections.isNotEmpty()) item { TextButton(onClick = { onUndo(report.sourceKey) }) { Text("Undo correction") } }
        }
        if (correcting) {
            CorrectionForm(field, value, unit, { field = it }, { value = it }, { unit = it }) {
                onCorrection(report.sourceKey, field, value, unit)
                correcting = false
            }
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
    onUndo: (String) -> Unit,
    onIgnore: (String) -> Unit
) {
    var correcting by remember(issue.reportId) { mutableStateOf(false) }
    var field by remember(issue.reportId) { mutableStateOf("") }
    var value by remember(issue.reportId) { mutableStateOf("") }
    var unit by remember(issue.reportId) { mutableStateOf("") }
    ReviewCard(container = Color(0xFFFFF2F0)) {
        Text(issue.reportName, fontWeight = FontWeight.Bold)
        Text(issue.dateSent, style = MaterialTheme.typography.bodySmall)
        Text(issue.userMessage)
        corrections.forEach {
            Text(
                "Clinician entered · ${it.field}: ${it.value} ${it.unit}".trim(),
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (issue.retryable) item { Button(onClick = { onRetry(issue.reportId) }) { Text("Retry") } }
            item { OutlinedButton(onClick = { onOpen(issue.reportId, issue.reportName) }) { Text("Open report") } }
            item { OutlinedButton(onClick = { correcting = !correcting }) { Text("Enter result") } }
            item { TextButton(onClick = { onIgnore(issue.reportId) }) { Text("Ignore") } }
            if (corrections.isNotEmpty()) item { TextButton(onClick = { onUndo(issue.reportId) }) { Text("Undo correction") } }
        }
        if (correcting) {
            CorrectionForm(field, value, unit, { field = it }, { value = it }, { unit = it }) {
                onCorrection(issue.reportId, field, value, unit)
                correcting = false
            }
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
private fun IssuesScreen(
    issues: List<ReportIssue>,
    corrections: List<ClinicianCorrection>,
    onRetry: (String) -> Unit,
    onRetryAll: () -> Unit,
    onLoginAgain: () -> Unit,
    onOpen: (String, String) -> Unit,
    onCorrection: (String, String, String, String) -> Unit,
    onUndo: (String) -> Unit,
    onIgnore: (String) -> Unit
) {
    val grouped = issues.groupBy { it.kind }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Issues", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(onClick = onRetryAll, enabled = issues.any { it.retryable }) { Text("Retry all") }
            }
        }
        if (issues.isEmpty()) item { ReviewCard { Text("No unresolved report issues") } }
        grouped.forEach { (kind, entries) ->
            item { Text(issueHeading(kind), fontWeight = FontWeight.Bold) }
            items(entries) { issue ->
                var correcting by remember(issue.reportId) { mutableStateOf(false) }
                var field by remember(issue.reportId) { mutableStateOf("") }
                var value by remember(issue.reportId) { mutableStateOf("") }
                var unit by remember(issue.reportId) { mutableStateOf("") }
                ReviewCard(container = Color(0xFFFFF2F0)) {
                    Text(issue.reportName, fontWeight = FontWeight.Bold)
                    Text(issue.userMessage)
                    Text("Attempt ${issue.attempts}", style = MaterialTheme.typography.bodySmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (issue.retryable) item { Button(onClick = { onRetry(issue.reportId) }) { Text("Retry") } }
                        item { OutlinedButton(onClick = { onOpen(issue.reportId, issue.reportName) }) { Text("Open report") } }
                        item { OutlinedButton(onClick = { correcting = !correcting }) { Text("Enter result") } }
                        if (issue.kind == ReportIssueKind.SESSION_EXPIRED) item { OutlinedButton(onClick = onLoginAgain) { Text("Login again") } }
                        item { TextButton(onClick = { onIgnore(issue.reportId) }) { Text("Ignore") } }
                        if (corrections.any { it.reportId == issue.reportId }) item { TextButton(onClick = { onUndo(issue.reportId) }) { Text("Undo correction") } }
                    }
                    if (correcting) {
                        CorrectionForm(field, value, unit, { field = it }, { value = it }, { unit = it }) {
                            onCorrection(issue.reportId, field, value, unit)
                            correcting = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewCard(
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

private fun clinicalPanel(parameter: String): String {
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
            entry.contains("susceptible", true) || entry.contains("sensitive", true) -> {
                groups.getValue("S") += entry.replace(Regex("(?i)\\s+(susceptible|sensitive)$"), "")
            }
            entry.contains("intermediate", true) -> {
                groups.getValue("I") += entry.replace(Regex("(?i)\\s+intermediate$"), "")
            }
            entry.contains("resistant", true) -> {
                groups.getValue("R") += entry.replace(Regex("(?i)\\s+resistant$"), "")
            }
        }
    }
    return groups.mapNotNull { (label, values) ->
        values.distinct().takeIf { it.isNotEmpty() }?.let { label to it.joinToString(", ") }
    }
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
internal fun ProductionPdfDialog(
    state: ProductionPdfState,
    onClose: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF111111)) {
            Column {
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        state.title,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.pageCount > 0) Text("${state.pageIndex + 1}/${state.pageCount}", color = Color.White)
                    TextButton(onClick = onClose) { Text("Close", color = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    state.bitmap?.let {
                        Image(
                            it.asImageBitmap(),
                            "PDF page",
                            Modifier.fillMaxSize().padding(8.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
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
