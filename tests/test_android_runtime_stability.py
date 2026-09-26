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


def test_production_workflow_can_share_clinical_summary_to_chatgpt():
    activity = (APP / "src/main/java/org/kundakarlab/nimsfastsummarymobile/ProductionWorkflowActivity.kt").read_text()
    ui = (APP / "src/main/java/org/kundakarlab/nimsfastsummarymobile/ProductionWorkflowUi.kt").read_text()
    assert "ClinicalSummaryFormatter.cleanText" in activity
    assert "Intent.ACTION_SEND" in activity
    assert "onShareSummary = ::shareClinicalSummary" in activity
    assert 'Text("Send to ChatGPT")' in ui


def test_streamlined_auth_preserves_captcha_boundary_and_keystore_credentials():
    login = (APP / "src/main/java/org/kundakarlab/nimsfastsummarymobile/LoginGateActivity.kt").read_text()
    autofill = (APP / "src/main/java/org/kundakarlab/nimsfastsummarymobile/NimsCredentialAutofill.kt").read_text()
    settings = (APP / "src/main/java/org/kundakarlab/nimsfastsummarymobile/SecureSettings.kt").read_text()
    manifest = (APP / "src/main/AndroidManifest.xml").read_text()
    assert "resumeExistingSession()" in login
    assert "saveNimsCredentials" in login
    assert 'Text("Authenticate")' in login
    assert "captcha_required" in autofill
    assert "captchaValue" not in autofill
    assert "NIMS_CREDENTIAL_KEY_ALIAS" in settings
    assert "nims_results_login_credentials" in settings
    assert 'android:allowBackup="false"' in manifest


def test_android_relay_worker_is_encrypted_and_keeps_authentication_local():
    manifest = (APP / "src/main/AndroidManifest.xml").read_text()
    service = (APP / "src/main/java/org/kundakarlab/nimsfastsummarymobile/NimsRelayService.kt").read_text()
    crypto = (APP / "src/main/java/org/kundakarlab/nimsfastsummarymobile/NimsRelayCrypto.kt").read_text()
    client = (APP / "src/main/java/org/kundakarlab/nimsfastsummarymobile/NimsRelayClient.kt").read_text()
    retriever = (APP / "src/main/java/org/kundakarlab/nimsfastsummarymobile/NimsBackgroundRetriever.kt").read_text()
    assert 'android:foregroundServiceType="specialUse"' in manifest
    assert "FOREGROUND_SERVICE_SPECIAL_USE" in manifest
    assert "decryptEnvelope" in service
    assert "encryptEnvelope" in crypto
    assert "RSA-OAEP" in crypto and "AES/GCM/NoPadding" in crypto
    assert "x-nims-device-secret" in client
    assert "SHOWPATDETAILS" in retriever and "patCrNo" in retriever
    assert "NimsAuthenticationRequiredException" in retriever
    assert "nimsUsername()" not in client
    assert "nimsPassword()" not in client
    assert "Cookie" not in client
