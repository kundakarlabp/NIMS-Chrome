from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID_APP = ROOT / "mobile" / "android" / "app"
JAVA_ROOT = ANDROID_APP / "src/main/java/org/kundakarlab/nimsfastsummarymobile"
CHROME_ACTIVITY = JAVA_ROOT / "ChromeModeActivity.kt"
CHROME_UI = JAVA_ROOT / "ChromeModeUi.kt"
EXTRACTOR_ASSET = ANDROID_APP / "src/main/assets/nimsOnDemandExtractor.js"


def test_android_packages_only_the_local_on_demand_extractor() -> None:
    build_gradle = (ANDROID_APP / "build.gradle.kts").read_text(encoding="utf-8")
    assert 'src/main/assets/nimsOnDemandExtractor.js' in build_gradle
    assert '"../../../shared/nims-web"' not in build_gradle
    assert EXTRACTOR_ASSET.is_file()


def test_launcher_uses_chrome_mode_without_document_start_runtime() -> None:
    manifest = (ANDROID_APP / "src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    source = CHROME_ACTIVITY.read_text(encoding="utf-8")
    assert 'android:name=".ChromeModeActivity"' in manifest
    assert "NimsWebViewRuntime.install" not in source
    assert "addDocumentStartJavaScript" not in source
    assert "addWebMessageListener" not in source
    assert "OnDemandNimsExtractor(webView)" in source
    assert "extractor.extract" in source


def test_on_demand_extractor_is_read_only_and_non_polling() -> None:
    source = EXTRACTOR_ASSET.read_text(encoding="utf-8")
    lowered = source.lower()
    assert "setinterval(" not in lowered
    assert "mutationobserver" not in lowered
    assert ".click(" not in lowered
    assert ".submit(" not in lowered
    assert "jquery =" not in lowered
    assert "datetime" not in lowered
    assert "transientprintreportarg" in lowered


def test_android_bulk_workers_do_not_evaluate_webview_javascript() -> None:
    source = CHROME_ACTIVITY.read_text(encoding="utf-8")
    fetch_body = source.split("private suspend fun fetchAndParse", 1)[1].split(
        "private fun fetchWithWebViewSession", 1
    )[0]
    assert "evaluateJavascript" not in fetch_body
    assert "extractor.extract" not in fetch_body
    assert "OnDemandReportRequest" in source
    assert "catch (cancelled: CancellationException)" in source


def test_android_rendering_uses_optional_json_objects() -> None:
    source = CHROME_ACTIVITY.read_text(encoding="utf-8")
    assert ".getJSONObject(" not in source
    assert ".optJSONObject(" in source


def test_android_webview_login_surface_is_optimized() -> None:
    source = CHROME_ACTIVITY.read_text(encoding="utf-8")
    ui = CHROME_UI.read_text(encoding="utf-8")
    manifest = (ANDROID_APP / "src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    assert 'android:windowSoftInputMode="adjustResize"' in manifest
    assert "settings.useWideViewPort = true" in source
    assert "settings.loadWithOverviewMode = true" in source
    assert "settings.builtInZoomControls = true" in source
    assert "settings.displayZoomControls = false" in source
    assert "setAcceptThirdPartyCookies(this, true)" in source
    assert "webView.requestFocus()" in source
    assert "ChromeModeApp(" in source
    assert "AndroidView(factory = { webView }" in ui


def test_android_app_does_not_store_nims_credentials_or_automate_login() -> None:
    source = CHROME_ACTIVITY.read_text(encoding="utf-8").lower()
    secure_settings = (JAVA_ROOT / "SecureSettings.kt").read_text(encoding="utf-8").lower()
    extractor = EXTRACTOR_ASSET.read_text(encoding="utf-8").lower()
    combined = source + "\n" + secure_settings + "\n" + extractor
    assert "nims_password" not in combined
    assert "nims_user" not in combined
    assert "autologin" not in combined
    assert "captcha_value" not in combined
