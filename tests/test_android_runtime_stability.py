from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "mobile/android/app"
MAIN = APP / "src/main/java/org/kundakarlab/nimsfastsummarymobile/MainActivity.kt"


def test_runtime_assets_are_generated_from_shared_sources():
    text = (APP / "build.gradle.kts").read_text()
    assert "syncNimsRuntimeAssets" in text
    for name in ("nimsReportCore.js", "contentUtils.js", "nimsAndroidFrameBridge.js", "nimsWebviewShim.js"):
        assert name in text
    assert (ROOT / "shared/nims-web/nimsReportCore.js").is_file()


def test_main_activity_uses_crash_safe_report_pipeline():
    source = MAIN.read_text()
    manifest = (APP / "src/main/AndroidManifest.xml").read_text()
    assert 'android:name=".MainActivity"' in manifest
    assert 'android:label="NIMS Results"' in manifest
    assert "directReportUrlOrNull" in source
    assert "SafeJsonArrayDecoder" in source
    assert "selectRowsForModeFromDoc" in source
    assert "PreparedReportRequest" in source


def test_bulk_workers_do_not_call_webview_javascript():
    source = MAIN.read_text()
    body = source.split("private suspend fun fetchAndParseOne", 1)[1].split("private fun fetchWithWebViewCookies", 1)[0]
    assert "evaluateCore" not in body
    assert "evaluateJson" not in body


def test_request_preparation_rejects_tokens_without_throwing():
    source = MAIN.read_text()
    body = source.split("private fun prepareReportRequests", 1)[1].split("private fun startFetchParseSummarize", 1)[0]
    assert "directReportUrlOrNull" in body
    assert "directReportUrl(template" not in body


def test_webview_surface_settings_are_present():
    source = MAIN.read_text()
    for statement in (
        "settings.useWideViewPort = true",
        "settings.loadWithOverviewMode = true",
        "settings.builtInZoomControls = true",
        "settings.displayZoomControls = false",
        "setAcceptThirdPartyCookies(this, true)",
        "webView.requestFocus()",
        "NimsFastSummaryApp(",
        "AndroidView(factory = { webView }",
    ):
        assert statement in source
