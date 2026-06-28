package org.kundakarlab.nimsfastsummarymobile

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary

internal enum class PortalStage {
    BROWSING,
    SCANNING,
    PROCESSING,
    READY,
    ERROR
}

@Composable
internal fun ChromeModeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF075985),
            secondary = Color(0xFF006B5F),
            error = Color(0xFFB3261E)
        ),
        content = content
    )
}

@Composable
internal fun ChromeModeApp(
    webView: WebView,
    stage: PortalStage,
    statusMessage: String,
    currentPage: String,
    loadProgress: Int,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    showMore: Boolean,
    onToggleMore: () -> Unit,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onLogin: () -> Unit,
    onClearSession: () -> Unit,
    onAnalyze: () -> Unit,
    onAnalyzeCultures: () -> Unit,
    onAnalyzeFull: () -> Unit,
    onStop: () -> Unit,
    summary: UiSummary?,
    physicianNote: String,
    onPhysicianNoteChange: (String) -> Unit,
    onCopySummary: () -> Unit,
    onExportSummary: () -> Unit,
    onClearResults: () -> Unit
) {
    Scaffold(
        topBar = { CompactHeader(currentPage, statusMessage, loadProgress) },
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
        val modifier = Modifier.fillMaxSize().padding(padding)
        when (selectedTab) {
            0 -> PortalScreen(
                modifier = modifier,
                webView = webView,
                stage = stage,
                showMore = showMore,
                onToggleMore = onToggleMore,
                onBack = onBack,
                onReload = onReload,
                onLogin = onLogin,
                onClearSession = onClearSession,
                onAnalyze = onAnalyze,
                onAnalyzeCultures = onAnalyzeCultures,
                onAnalyzeFull = onAnalyzeFull,
                onStop = onStop
            )
            1 -> SourceReportsScreen(modifier, summary?.sourceReports.orEmpty())
            2 -> LabTrendsScreen(modifier, summary?.labTrends.orEmpty())
            3 -> CultureResultsScreen(modifier, summary?.cultures.orEmpty())
            else -> ClinicalSummaryScreen(
                modifier = modifier,
                summary = summary,
                physicianNote = physicianNote,
                onPhysicianNoteChange = onPhysicianNoteChange,
                onCopy = onCopySummary,
                onExport = onExportSummary,
                onClear = onClearResults
            )
        }
    }
}

@Composable
private fun CompactHeader(currentPage: String, message: String, progress: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text("NIMS Fast Summary", color = Color.White, fontWeight = FontWeight.Bold)
        Text(
            currentPage,
            color = Color(0xFFD7E8FF),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            if (progress in 1..99) "Loading $progress%" else message,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun PortalScreen(
    modifier: Modifier,
    webView: WebView,
    stage: PortalStage,
    showMore: Boolean,
    onToggleMore: () -> Unit,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onLogin: () -> Unit,
    onClearSession: () -> Unit,
    onAnalyze: () -> Unit,
    onAnalyzeCultures: () -> Unit,
    onAnalyzeFull: () -> Unit,
    onStop: () -> Unit
) {
    Column(modifier) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { OutlinedButton(onClick = onBack) { Text("Back") } }
            item { OutlinedButton(onClick = onReload) { Text("Reload") } }
            item {
                Button(
                    onClick = onAnalyze,
                    enabled = stage !in setOf(PortalStage.SCANNING, PortalStage.PROCESSING)
                ) { Text("Analyze") }
            }
            item { OutlinedButton(onClick = onToggleMore) { Text(if (showMore) "Less" else "More") } }
            if (stage == PortalStage.PROCESSING) {
                item { OutlinedButton(onClick = onStop) { Text("Stop") } }
            }
        }
        if (showMore) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item { OutlinedButton(onClick = onLogin) { Text("Login") } }
                item { OutlinedButton(onClick = onAnalyzeCultures) { Text("Cultures") } }
                item { OutlinedButton(onClick = onAnalyzeFull) { Text("Full") } }
                item { OutlinedButton(onClick = onClearSession) { Text("Clear session") } }
            }
        }
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}
