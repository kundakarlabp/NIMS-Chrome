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

    text = replace_once(
        text,
        '                    onOpenCrReports = { runMode("bulk_fast") },',
        '''                    onOpenCrReports = {
                        val visibleRows = crossFrameReport?.optJSONArray("rows")?.length() ?: 0
                        if (visibleRows > 0) runMode("bulk_fast") else openCrWiseReports()
                    },''',
        "Open CR / Analyze action",
    )

    text = replace_once(
        text,
        "        crossFrameReport = json",
        '''        if (FrameReportNormalizer.normalize(json, webView.url ?: NIMS_LOGIN_URL) { log(it) } == null) return
        crossFrameReport = json''',
        "cross-frame normalization",
    )

    text = replace_once(
        text,
        '        val shimJs = runCatching { assets.open("nimsWebviewShim.js").bufferedReader().use { it.readText() } }.getOrNull()',
        '''        val shimJs = runCatching { assets.open("nimsWebviewShim.js").bufferedReader().use { it.readText() } }.getOrNull()
        val jqueryJs = runCatching { assets.open("jquery-3.7.1.min.js").bufferedReader().use { it.readText() } }.getOrNull()''',
        "bundled jQuery loader",
    )

    text = replace_once(
        text,
        'WebViewCompat.addWebMessageListener(this, "nimsAndroidBridge", setOf("*"))',
        'WebViewCompat.addWebMessageListener(this, "nimsAndroidBridge", setOf("https://www.nimsts.edu.in", "https://nimsts.edu.in"))',
        "bridge origin restriction",
    )

    text = replace_once(
        text,
        '                val payload = (shimJs ?: "") + readerJs',
        '''                val jqueryBootstrap = if (jqueryJs.isNullOrBlank()) {
                    ""
                } else {
                    """
                    (function(w){
                      try {
                        var host = String(w.location && w.location.hostname || "");
                        if (/(^|\\.)nimsts\\.edu\\.in$/i.test(host) && typeof w.jQuery === "undefined") {
                    """.trimIndent() + "\\n" + jqueryJs + "\\n" + """
                          w.__nimsBundledJqueryVersion = "3.7.1";
                        }
                      } catch (error) {
                        if (w.console && w.console.error) w.console.error("NIMS bundled jQuery fallback failed", error);
                      }
                    })(window);
                    """.trimIndent()
                }
                val payload = jqueryBootstrap + "\\n" + (shimJs ?: "") + readerJs''',
        "document-start jQuery bootstrap",
    )

    text = replace_once(
        text,
        'WebViewCompat.addDocumentStartJavaScript(this, injected, setOf("*"))',
        'WebViewCompat.addDocumentStartJavaScript(this, injected, setOf("https://www.nimsts.edu.in", "https://nimsts.edu.in"))',
        "document-start origin restriction",
    )

    text = replace_once(
        text,
        '            item { Button(onClick = onOpenCrReports, enabled = !navigationInProgress) { Text("Analyze Current Results") } }',
        '            item { Button(onClick = onOpenCrReports, enabled = !navigationInProgress) { Text("Open CR / Analyze") } }',
        "primary button label",
    )

    text = replace_once(
        text,
        '                AppState.REPORT_PAGE_READY -> "Report list detected. Discover mapping."',
        '                AppState.REPORT_PAGE_READY -> "Enter the CR number if needed; after the report list appears, tap Open CR / Analyze."',
        "report-page guidance",
    )

    SOURCE.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
