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
        "jQuery asset loader",
    )

    text = replace_once(
        text,
        '''            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
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
            }''',
        '''            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                runCatching {
                    WebViewCompat.addWebMessageListener(
                        this,
                        "nimsAndroidBridge",
                        setOf("https://www.nimsts.edu.in", "https://nimsts.edu.in")
                    ) { _, message, _, _, _ ->
                        message.data?.let { data -> post { onFrameReport(data) } }
                    }
                }.onSuccess {
                    log("RUNTIME_BOOTSTRAP: bridge installed")
                }.onFailure {
                    log("RUNTIME_BOOTSTRAP_ERROR: bridge ${it.javaClass.simpleName}")
                }
            } else {
                log("RUNTIME_BOOTSTRAP_ERROR: WEB_MESSAGE_LISTENER unsupported")
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                val readerJs = if (coreJs != null && utilsJs != null && bridgeJs != null) {
                    "\n$coreJs\n$utilsJs\n$bridgeJs"
                } else {
                    ""
                }
                val jqueryBootstrap = if (jqueryJs.isNullOrBlank()) {
                    ""
                } else {
                    """
                    (function(w){
                      try {
                        var host = String(w.location && w.location.hostname || "");
                        if (/(^|\\.)nimsts\\.edu\\.in$/i.test(host) && typeof w.jQuery === "undefined") {
                    """.trimIndent() + "\n" + jqueryJs + "\n" + """
                          w.__nimsBundledJqueryVersion = w.jQuery && w.jQuery.fn ? w.jQuery.fn.jquery : "unknown";
                        }
                      } catch (error) {
                        if (w.console && w.console.error) w.console.error("NIMS jQuery fallback failed", error);
                      }
                    })(window);
                    """.trimIndent()
                }
                val payload = jqueryBootstrap + "\n" + (shimJs ?: "") + readerJs
                if (payload.isNotBlank()) {
                    runCatching {
                        val injected = "try{\n$payload\n}catch(e){if(window.console&&console.error)console.error('NIMS inject failed',e);}"
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            injected,
                            setOf("https://www.nimsts.edu.in", "https://nimsts.edu.in")
                        )
                    }.onSuccess {
                        log("RUNTIME_BOOTSTRAP: document-start installed jqueryAsset=${!jqueryJs.isNullOrBlank()} shimAsset=${!shimJs.isNullOrBlank()}")
                    }.onFailure {
                        log("RUNTIME_BOOTSTRAP_ERROR: document-start ${it.javaClass.simpleName}")
                        setState(AppState.ERROR, "This Android WebView cannot install the NIMS compatibility runtime.")
                    }
                } else {
                    log("RUNTIME_BOOTSTRAP_ERROR: compatibility assets missing")
                    setState(AppState.ERROR, "NIMS compatibility assets are missing from this APK.")
                }
            } else {
                log("RUNTIME_BOOTSTRAP_ERROR: DOCUMENT_START_SCRIPT unsupported")
                setState(AppState.ERROR, "Update Android System WebView before using NIMS Fast Summary.")
            }''',
        "document-start runtime block",
    )

    text = replace_once(
        text,
        '''        when (json.optString("type")) {
            "nims_frame_debug" -> {''',
        '''        when (json.optString("type")) {
            "nims_runtime_status" -> {
                log(
                    "RUNTIME ${json.optString("url")}: phase=${json.optString("phase")} " +
                        "dateTime=${json.optBoolean("dateTimeReady")} jquery=${json.optBoolean("jqueryReady")} " +
                        "version=${json.optString("jqueryVersion")} offset=${json.optBoolean("offsetPatched")} " +
                        "ajax=${json.optBoolean("ajaxCompleteTabPatched")} bundled=${json.optString("bundledJqueryVersion")}"
                )
                return
            }
            "nims_frame_debug" -> {''',
        "runtime status handler",
    )

    text = replace_once(
        text,
        '                AppState.REPORT_PAGE_READY -> "Report list detected. Discover mapping."',
        '                AppState.REPORT_PAGE_READY -> "Navigate manually to Investigation → Cr No Wise Result Report Printing New, enter the CR number, press Go, then tap Analyze Current Results."',
        "manual workflow guidance",
    )

    SOURCE.write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
