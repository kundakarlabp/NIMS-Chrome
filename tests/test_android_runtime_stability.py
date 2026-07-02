from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID_APP = ROOT / "mobile" / "android" / "app"
JAVA_ROOT = ANDROID_APP / "src/main/java/org/kundakarlab/nimsfastsummarymobile"
MAIN_ACTIVITY = JAVA_ROOT / "MainActivity.kt"


def test_android_build_generates_only_runtime_web_assets() -> None:
    build_gradle = (ANDROID_APP / "build.gradle.kts").read_text(encoding="utf-8")
    assert 'syncNimsRuntimeAssets' in build_gradle
    for name in (
        "nimsReportCore.js",
        "contentUtils.js",
        "nimsAndroidFrameBridge.js",
        "nimsWebviewShim.js",
    ):
        assert f'"{name}"' in build_gradle
    assert 'assets.srcDirs("src/main/assets", "../../../shared/nims-web")' not in build_gradle
    assert (ROOT / "shared/nims-web/nimsReportCore.js").is_file()


def test_main_activity_is_the_launcher_and_uses_the_verified_pipeline() -> None:
    manifest = (ANDROID_APP / "src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    source = MAIN_ACTIVITY.read_text(encoding="utf-8")
    assert 'android:name=".MainActivity"' in manifest
    assert 'android:label="NIMS Results"' in manifest
    assert "directReportUrlOrNull" in source
    assert "SafeJsonArrayDecoder" in source
    assert "selectRowsForModeFromDoc" in source
    assert "PreparedReportRequest" in source


def test_android_bulk_workers_do_not_evaluate_webview_javascript() -> None:
    source = MAIN_ACTIVITY.read_text(encoding="utf-8")
    fetch_body = source.split("private suspend fun fetchAndParseOne", 1)[1].split(
        "private fun fetchWithWebViewCookies", 1
    )[0]
    assert "evaluateJavascript" not in fetch_body
    assert "extractor.extract" not in fetch_body
    assert "OnDemandReportRequest" in source
    assert "catch (cancelled: CancellationException)" in source


def test_android_request_preparation_does_not_throw_on_rejected_tokens() -> None:
    source = MAIN_ACTIVITY.read_text(encoding="utf-8")
    preparation = source.split("private fun prepareReportRequests", 1)[1].split(
        "private fun startFetchParseSummarize", 1
    )[0]
    assert "directReportUrlOrNull" in preparation
    assert "directReportUrl(template" not in preparation
    assert "token rejected" in preparation


def test_android_rendering_uses_optional_json_objects() -> None:
    source = MAIN_ACTIVITY.read_text(encoding="utf-8")
    assert ".getJSONObject(" not in source
    assert ".optJSONObject(" in source


def test_android_webview_login_surface_is_optimized() -> None:
    source = MAIN_ACTIVITY.read_text(encoding="utf-8")
    manifest = (ANDROID_APP / "src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    assert 'android:windowSoftInputMode="adjustResize"' in manifest
    assert "settings.useWideViewPort = true" in source
    assert "settings.loadWithOverviewMode = true" in source
    assert "settings.builtInZoomControls = true" in source
    assert "settings.displayZoomControls = false" in source
    assert "setAcceptThirdPartyCookies(this, true)" in source
    assert "webView.requestFocus()" in source
    assert "NimsFastSummaryApp(" in source
    assert "SettingsDialog(" in source
    assert "AndroidView(factory = { webView }" in source


def test_android_app_does_not_store_nims_credentials_or_automate_login() -> None:
    source = MAIN_ACTIVITY.read_text(encoding="utf-8").lower()
    secure_settings = (JAVA_ROOT / "SecureSettings.kt").read_text(encoding="utf-8").lower()
    combined = source + "\n" + secure_settings
    assert "nims_password" not in combined
    assert "nims_user" not in combined
    assert "captcha_value" not in combined
    assert "autologin" not in combined
    assert "captcha_value" not in combined
