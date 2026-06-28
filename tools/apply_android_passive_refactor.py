from __future__ import annotations

import re
from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def replace_regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.DOTALL)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one regex match, found {count}")
    return updated


def update_main_activity() -> None:
    path = Path("mobile/android/app/src/main/java/org/kundakarlab/nimsfastsummarymobile/MainActivity.kt")
    text = path.read_text(encoding="utf-8")

    text = replace_once(
        text,
        "import androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.setValue",
        "import androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.remember\nimport androidx.compose.runtime.setValue",
        "remember import",
    )

    text = replace_once(
        text,
        '''                    onOpenCrReports = {
                        val visibleRows = crossFrameReport?.optJSONArray("rows")?.length() ?: 0
                        if (visibleRows > 0) runMode("bulk_fast") else openCrWiseReports()
                    },''',
        '''                    onOpenCrReports = {
                        val visibleRows = crossFrameReport?.optJSONArray("rows")?.length() ?: 0
                        if (visibleRows > 0) {
                            runMode("bulk_fast")
                        } else {
                            setState(
                                AppState.HELPER_READY,
                                "Navigate in NIMS to Investigation → CR No Wise Result Report Printing New, submit the CR number, then tap Analyze Results."
                            )
                        }
                    },''',
        "manual analyze callback",
    )

    page_state_handler = '''        when (json.optString("type")) {
            "nims_page_state" -> {
                if (activeProcessingJob?.isActive == true) return
                val pageKind = json.optString("pageKind")
                val reportCount = json.optInt("reportCount")
                when (pageKind) {
                    "login" -> setState(AppState.HELPER_READY, "Login to NIMS manually.")
                    "portal" -> if (appStateValue.ordinal < AppState.REPORT_PAGE_READY.ordinal) {
                        setState(
                            AppState.HELPER_READY,
                            "Navigate in NIMS to Investigation → CR No Wise Result Report Printing New."
                        )
                    }
                    "cr_search" -> {
                        crossFrameReport = null
                        mapping = null
                        mappingValidated = false
                        setState(AppState.REPORT_PAGE_READY, "CR search ready. Enter the CR number and submit it in NIMS.")
                    }
                    "cr_results" -> setState(
                        AppState.REPORT_PAGE_READY,
                        "Report list detected ($reportCount visible). Tap Analyze Results."
                    )
                }
                return
            }
            "nims_runtime_ready" -> {'''
    text = replace_once(
        text,
        '''        when (json.optString("type")) {
            "nims_runtime_ready" -> {''',
        page_state_handler,
        "passive page-state handler",
    )

    new_webview_screen = '''@Composable
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
    onOpenCrReports: () -> Unit,
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
    var showAdvanced by remember { mutableStateOf(false) }
    Column(modifier) {
        StatusCard(state)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { OutlinedButton(onClick = onBack) { Text("Back") } }
            item { OutlinedButton(onClick = onForward) { Text("Forward") } }
            item { OutlinedButton(onClick = onReload) { Text("Reload") } }
            item { OutlinedButton(onClick = onNimsLogin) { Text("NIMS Login") } }
            item {
                Button(
                    onClick = onOpenCrReports,
                    enabled = !navigationInProgress && state != AppState.FETCHING
                ) { Text("Analyze Results") }
            }
            item {
                OutlinedButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "Hide tools" else "Advanced tools")
                }
            }
            if (state == AppState.FETCHING) {
                item { OutlinedButton(onClick = onCancelProcessing) { Text("Stop") } }
            }
        }
        if (showAdvanced) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { OutlinedButton(onClick = onClearNimsSession) { Text("Clear session") } }
                item { OutlinedButton(onClick = onZoomOut) { Text("Zoom -") } }
                item { OutlinedButton(onClick = onZoomIn) { Text("Zoom +") } }
                item { OutlinedButton(onClick = onDiagnose) { Text("Diagnose") } }
                item { OutlinedButton(onClick = onDiscover) { Text("Discover") } }
                item { OutlinedButton(onClick = onTestOne) { Text("Test one") } }
                item { OutlinedButton(onClick = onFast) { Text("Fast") } }
                item { OutlinedButton(onClick = onCulturesOnly) { Text("Cultures") } }
                item { OutlinedButton(onClick = onFull) { Text("Full") } }
            }
        }
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
        if (showAdvanced && logText.isNotBlank()) {
            Text(
                logText.takeLast(1200),
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(Color(0xFFF7F9FC))
                    .padding(8.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ReportsScreen'''
    text = replace_regex_once(
        text,
        r"@Composable\nprivate fun NimsWebViewScreen\([\s\S]*?\n}\n\n@Composable\nprivate fun ReportsScreen",
        new_webview_screen,
        "simplified WebView screen",
    )

    text = replace_once(
        text,
        '''                AppState.HELPER_READY -> "Login to NIMS manually."
                AppState.NIMS_LOGIN -> "Open the report page after login."
                AppState.REPORT_PAGE_READY -> "Enter the CR number if needed; after the report list appears, tap Open CR / Analyze."
                AppState.MAPPING_DISCOVERED -> "Mapping ready. Run Test One Report."''',
        '''                AppState.HELPER_READY -> "Login, then navigate in NIMS to Investigation → CR No Wise Result Report Printing New."
                AppState.NIMS_LOGIN -> "Use the normal NIMS menu to open the CR-wise report page."
                AppState.REPORT_PAGE_READY -> "Enter and submit the CR number in NIMS. When View Report rows appear, tap Analyze Results."
                AppState.MAPPING_DISCOVERED -> "Report request validated. Analysis can continue."''',
        "status guidance",
    )

    text = text.replace("NIMS Investigation runtime ready. Continue to the CR-wise report page.", "NIMS frame observed. Continue using the normal NIMS page.")
    path.write_text(text, encoding="utf-8")


def update_readme() -> None:
    path = Path("README.md")
    text = path.read_text(encoding="utf-8")
    replacement = '''### Android WebView Mobile Mode

The Android app under `mobile/android/` separates the NIMS portal from the native
results UI. NIMS remains responsible for manual login, menu navigation, CR-number
entry, form submission, and rendering. A passive all-frame observer detects the
genuine CR search/result frame and sends sanitized report references to Android.
The app then fetches supported reports with the authenticated WebView session,
processes them on-device, and presents Reports, Trends, Cultures, and Summary.

Normal workflow:

1. Install the debug-signed APK.
2. Log in to NIMS manually.
3. Navigate in NIMS to **Investigation → CR No Wise Result Report Printing New**.
4. Enter and submit the CR number manually.
5. Keep the result table with visible **View Report** rows on screen.
6. Tap **Analyze Results**.
7. Review the native result tabs and verify values against source reports.

The Android runtime does not bundle jQuery, patch `date_time`, wrap
`ajaxCompleteTab`, click menus, automate login, or navigate directly to internal
NIMS endpoints. Railway remains optional advanced fallback; on-device processing
is the default. Image-only PDFs remain unsupported because OCR is not enabled.

Android build steps:'''
    text = replace_regex_once(
        text,
        r"### Android WebView Mobile Mode\n\n[\s\S]*?\nAndroid build steps:",
        replacement,
        "README Android section",
    )
    path.write_text(text, encoding="utf-8")


def main() -> None:
    update_main_activity()
    update_readme()


if __name__ == "__main__":
    main()
