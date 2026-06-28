from pathlib import Path

SOURCE = Path("mobile/android/app/src/main/java/org/kundakarlab/nimsfastsummarymobile/MainActivity.kt")


def replace_once(text: str, before: str, after: str, label: str) -> str:
    if after in text:
        return text
    count = text.count(before)
    if count != 1:
        raise RuntimeError(f"Expected exactly one {label} target, found {count}")
    return text.replace(before, after, 1)


def main() -> None:
    text = SOURCE.read_text(encoding="utf-8")

    text = replace_once(text, "import androidx.webkit.WebViewFeature\n", "import androidx.webkit.WebViewFeature\nimport androidx.webkit.ScriptHandler\n", "ScriptHandler import")

    text = replace_once(
        text,
        '    private var webViewUserAgent = ""\n',
        '''    private var webViewUserAgent = ""
    private var documentStartScriptHandler: ScriptHandler? = null
    private var webRuntimeSupported = false
    private var webRuntimeError = ""
''',
        "runtime fields",
    )

    text = replace_once(
        text,
        '''        webView = createWebView()
        clearWebViewSession(coldStartOnly = true) { webView.loadUrl(NIMS_LOGIN_URL) }
        webViewUserAgent = webView.settings.userAgentString
        val initial = InitialStatePolicy.derive(processingMode, settings.helperUrl().isNotBlank(), settings.hasApiKey())
        setState(initial.state, initial.message)
''',
        '''        webView = createWebView()
        webViewUserAgent = webView.settings.userAgentString
        if (webRuntimeSupported) {
            clearWebViewSession(coldStartOnly = true) { webView.loadUrl(NIMS_LOGIN_URL) }
            val initial = InitialStatePolicy.derive(processingMode, settings.helperUrl().isNotBlank(), settings.hasApiKey())
            setState(initial.state, initial.message)
        } else {
            setState(AppState.ERROR, webRuntimeError.ifBlank { "NIMS WebView runtime could not be installed." })
        }
''',
        "initial WebView load",
    )

    text = replace_once(
        text,
        '                    onNimsLogin = { webView.loadUrl(NIMS_LOGIN_URL) },',
        '''                    onNimsLogin = {
                        if (webRuntimeSupported) webView.loadUrl(NIMS_LOGIN_URL)
                        else setState(AppState.ERROR, webRuntimeError.ifBlank { "Update Chrome and Android System WebView." })
                    },''',
        "NIMS login action",
    )

    text = replace_once(
        text,
        '                    onOpenCrReports = { runMode("bulk_fast") },',
        '''                    onOpenCrReports = {
                        val visibleRows = crossFrameReport?.optJSONArray("rows")?.length() ?: 0
                        if (visibleRows > 0) runMode("bulk_fast") else openCrWiseReports()
                    },''',
        "Open CR action",
    )

    text = replace_once(
        text,
        '''        val coreJs = runCatching { assets.open("nimsReportCore.js").bufferedReader().use { it.readText() } }.getOrNull()
        val utilsJs = runCatching { assets.open("contentUtils.js").bufferedReader().use { it.readText() } }.getOrNull()
        val bridgeJs = runCatching { assets.open("nimsAndroidFrameBridge.js").bufferedReader().use { it.readText() } }.getOrNull()
        // Runtime compatibility shim: neutralizes NIMS's confirmed crashes
        // (missing date_time global, and the $("#menuStrip").offset().left throw
        // in tabmenu.js) so the menu/content render isn't aborted in the WebView.
        val shimJs = runCatching { assets.open("nimsWebviewShim.js").bufferedReader().use { it.readText() } }.getOrNull()
''',
        '        // NimsWebViewRuntime loads and registers the bundled runtime assets below.\n',
        "legacy asset loading",
    )

    text = replace_once(
        text,
        '''            // All-frames bridge (mirrors the extension's all_frames model). The
            // top frame cannot read a cross-origin result iframe, so inject the
            // core + bridge into every frame and let the frame that owns the rows
            // post them back via nimsAndroidBridge. Feature-gated; if unsupported,
            // the existing same-origin top-frame path still runs.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                runCatching {
                    WebViewCompat.addWebMessageListener(this, "nimsAndroidBridge", setOf("*")) { _, message, _, _, _ ->
                        message.data?.let { data -> post { onFrameReport(data) } }
                    }
                }
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                // The shim is the render fix and must run first, before NIMS's
                // own scripts, and even if the reader assets failed to load.
                val readerJs = if (coreJs != null && utilsJs != null && bridgeJs != null) {
                    "\n$coreJs\n$utilsJs\n$bridgeJs"
                } else {
                    ""
                }
                val payload = (shimJs ?: "") + readerJs
                if (payload.isNotBlank()) {
                    runCatching {
                        val injected = "try{\n$payload\n}catch(e){if(window.console&&console.error)console.error('NIMS inject failed',e);}"
                        WebViewCompat.addDocumentStartJavaScript(this, injected, setOf("*"))
                    }
                }
            }
''',
        '''            val runtime = NimsWebViewRuntime.install(
                webView = this,
                onMessage = { data -> post { onFrameReport(data) } },
                onLog = { detail -> log(detail) }
            )
            documentStartScriptHandler = runtime.scriptHandler
            webRuntimeSupported = runtime.supported
            webRuntimeError = runtime.error
            if (!runtime.supported) log(runtime.error)
''',
        "document-start runtime block",
    )

    text = replace_once(
        text,
        '''        when (json.optString("type")) {
            "nims_frame_debug" -> {
''',
        '''        when (json.optString("type")) {
            "nims_runtime_ready" -> {
                val path = json.optString("path").take(180)
                val jqueryVersion = json.optString("jqueryVersion").take(24)
                val ready = json.optBoolean("dateTimeReady") &&
                    (!path.startsWith("/HISInvestigationG5/") || json.optBoolean("jqueryPresent"))
                log("Runtime frame=$path ready=$ready jquery=${jqueryVersion.ifBlank { "none" }} fallback=${json.optBoolean("jqueryFallbackUsed")} offset=${json.optBoolean("offsetPatched")} tab=${json.optBoolean("ajaxCompleteTabPatched")}")
                if (ready && path.startsWith("/HISInvestigationG5/") && appStateValue == AppState.HELPER_READY && activeProcessingJob?.isActive != true) {
                    setState(AppState.HELPER_READY, "NIMS Investigation runtime ready. Continue to the CR-wise report page.")
                }
                return
            }
            "nims_runtime_error" -> {
                val path = json.optString("path").take(180)
                val detail = json.optString("detail", "WebView runtime error").take(160)
                log("Runtime error frame=$path detail=$detail")
                if (!detail.contains("without a matching iframe", ignoreCase = true)) {
                    setState(AppState.ERROR, "NIMS WebView runtime error. Reload the page and retry.")
                }
                return
            }
            "nims_frame_debug" -> {
''',
        "runtime message handling",
    )

    text = replace_once(
        text,
        "        crossFrameReport = json\n",
        '''        if (FrameReportNormalizer.normalize(json, webView.url ?: NIMS_LOGIN_URL) { log(it) } == null) return
        crossFrameReport = json
''',
        "frame report normalization",
    )

    text = replace_once(
        text,
        '''    override fun onDestroy() {
        cancelNavigation()
        activeProcessingJob?.cancel()
''',
        '''    override fun onDestroy() {
        cancelNavigation()
        activeProcessingJob?.cancel()
        runCatching { documentStartScriptHandler?.remove() }
        documentStartScriptHandler = null
''',
        "script handler cleanup",
    )

    text = replace_once(text, 'Text("Analyze Current Results")', 'Text("Open CR / Analyze")', "primary button label")
    text = replace_once(text, 'AppState.REPORT_PAGE_READY -> "Report list detected. Discover mapping."', 'AppState.REPORT_PAGE_READY -> "Enter the CR number if needed; after the report list appears, tap Open CR / Analyze."', "report page guidance")

    SOURCE.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
