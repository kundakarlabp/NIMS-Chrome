package org.kundakarlab.nimsdashboard

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var dashboard: WebView
    private lateinit var nims: WebView
    private lateinit var loginOverlay: LinearLayout
    private lateinit var loginStatus: TextView

    private val executor = Executors.newFixedThreadPool(2)
    private val handler = Handler(Looper.getMainLooper())
    private val reportInventory by lazy { ReportInventory() }

    @Volatile private var loggedIn = false
    @Volatile private var activeRequestId = ""
    @Volatile private var activeCrNo = ""
    @Volatile private var activePatientJson = "{}"
    @Volatile private var retryStructuredOnNextAuthenticatedPage = false
    @Volatile private var structuredRetryCount = 0
    private val bulkHandled = AtomicBoolean(false)

    private lateinit var bulkNormalizerScript: String
    private lateinit var dashboardBridgeScript: String
    private lateinit var nimsRunnerScript: String

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bulkNormalizerScript = asset("bulkNormalizer.js")
        dashboardBridgeScript = asset("android_dashboard_bridge.js")
        nimsRunnerScript = asset("nims_structured_runner.js")

        CookieManager.getInstance().setAcceptCookie(true)

        root = FrameLayout(this)
        setContentView(root)

        dashboard = WebView(this)
        configureDashboard(dashboard)
        root.addView(
            dashboard,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        buildLoginOverlay()

        nims = WebView(this)
        configureNims(nims)
        val nimsParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        loginOverlay.addView(nims, nimsParams)
        CookieManager.getInstance().setAcceptThirdPartyCookies(nims, true)

        dashboard.loadUrl(DASHBOARD_URL)
        nims.loadUrl(NIMS_LOGIN_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureDashboard(view: WebView) {
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.cacheMode = WebSettings.LOAD_DEFAULT
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        view.settings.setSupportMultipleWindows(false)
        view.addJavascriptInterface(DashboardInterface(), "AndroidDashboard")
        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (uri.scheme == "https" && uri.host.equals(DASHBOARD_HOST, true)) return false
                if (uri.scheme == "https") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!url.startsWith(DASHBOARD_ORIGIN)) return
                injectDashboardRuntime()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureNims(view: WebView) {
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.cacheMode = WebSettings.LOAD_DEFAULT
        view.settings.userAgentString = DESKTOP_UA
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = true
        view.settings.setSupportMultipleWindows(false)
        view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        view.addJavascriptInterface(NimsInterface(), "AndroidNims")
        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host?.lowercase().orEmpty()
                if (uri.scheme == "https" && host in NIMS_HOSTS) return false
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                injectNimsRunner {
                    probeNims(autoCloseLogin = true)
                }
            }
        }
    }

    private fun buildLoginOverlay() {
        loginOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(13, 48, 79))
        }
        loginStatus = TextView(this).apply {
            text = "NIMS login"
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        header.addView(
            loginStatus,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val check = Button(this).apply {
            text = "Check login"
            setOnClickListener { probeNims(autoCloseLogin = true) }
        }
        val back = Button(this).apply {
            text = "Dashboard"
            setOnClickListener { loginOverlay.visibility = View.GONE }
        }
        header.addView(check)
        header.addView(back)
        loginOverlay.addView(
            header,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            loginOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun injectDashboardRuntime() {
        dashboard.evaluateJavascript(bulkNormalizerScript, null)
        dashboard.evaluateJavascript(dashboardBridgeScript, null)
    }

    private fun injectNimsRunner(after: (() -> Unit)? = null) {
        nims.evaluateJavascript(nimsRunnerScript) {
            after?.invoke()
        }
    }

    private fun probeNims(autoCloseLogin: Boolean) {
        injectNimsRunner {
            nims.evaluateJavascript(
                "JSON.stringify(window.AndroidNimsStructured ? window.AndroidNimsStructured.probe() : {})"
            ) { raw ->
                val json = decodeJsString(raw)
                val probe = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
                val authenticated = probe.optBoolean("authenticated", false)
                val loginForm = probe.optBoolean("loginForm", false)
                val expired = probe.optBoolean("sessionExpired", false)
                loggedIn = authenticated && !expired
                loginStatus.text = when {
                    loggedIn -> "NIMS connected"
                    expired -> "NIMS session expired — sign in again"
                    loginForm -> "Enter NIMS credentials and CAPTCHA"
                    else -> "Complete NIMS login"
                }
                postDashboard(
                    "KBP_NIMS_STATUS",
                    JSONObject()
                        .put("ok", true)
                        .put("loggedIn", loggedIn)
                        .put("state", if (loggedIn) "ready" else "signing_in")
                )
                if (!loggedIn && !loginForm && loginOverlay.visibility == View.VISIBLE) {
                    nims.evaluateJavascript(
                        "window.AndroidNimsStructured && window.AndroidNimsStructured.prepareLogin ? window.AndroidNimsStructured.prepareLogin() : 'not_found';",
                        null
                    )
                }
                if (loggedIn && retryStructuredOnNextAuthenticatedPage) {
                    retryStructuredOnNextAuthenticatedPage = false
                    runStructuredCurrent()
                }
                if (loggedIn && autoCloseLogin && loginOverlay.visibility == View.VISIBLE) {
                    loginOverlay.visibility = View.GONE
                    postDashboard(
                        "KBP_NIMS_LOGIN_RESPONSE",
                        JSONObject().put("ok", true).put("loggedIn", true).put("state", "ready")
                    )
                }
            }
        }
    }

    private fun showLogin() {
        runOnUiThread {
            loginOverlay.visibility = View.VISIBLE
            loginStatus.text = "Complete NIMS login and CAPTCHA"
            val host = runCatching { Uri.parse(nims.url).host?.lowercase().orEmpty() }.getOrDefault("")
            if (host !in NIMS_HOSTS) nims.loadUrl(NIMS_LOGIN_URL)
        }
    }

    private fun startFetch(rawCr: String, rawRequestId: String) {
        val crNo = rawCr.filter(Char::isDigit)
        val requestId = rawRequestId.trim().take(128).ifBlank { "android-" + System.currentTimeMillis() }
        if (!Regex("""\d{15}""").matches(crNo)) {
            postDashboard(
                "KBP_NIMS_FETCH_ERROR",
                JSONObject().put("requestId", requestId).put("crNo", crNo)
                    .put("error", "Enter the 15-digit NIMS CR number.")
            )
            return
        }

        activeCrNo = crNo
        activeRequestId = requestId
        activePatientJson = JSONObject().put("crNo", crNo).toString()
        structuredRetryCount = 0
        retryStructuredOnNextAuthenticatedPage = false
        bulkHandled.set(false)

        if (!loggedIn) {
            postDashboard(
                "KBP_NIMS_AUTH_REQUIRED",
                JSONObject()
                    .put("requestId", requestId)
                    .put("crNo", crNo)
                    .put("state", "signing_in")
                    .put("preferredSource", "nims_bulk_api")
            )
            showLogin()
            return
        }

        postDashboard(
            "KBP_NIMS_FETCH_STARTED",
            JSONObject()
                .put("requestId", requestId)
                .put("crNo", crNo)
                .put("preferredSource", "nims_bulk_api")
        )
        postDashboard(
            "KBP_NIMS_RETRIEVAL_STAGE",
            JSONObject()
                .put("requestId", requestId)
                .put("crNo", crNo)
                .put("stage", "structured_api")
        )

        runOnUiThread {
            runStructuredCurrent()
            handler.postDelayed({
                if (activeRequestId == requestId && !bulkHandled.get()) {
                    bulkHandled.set(true)
                    postFetchError(
                        requestId,
                        crNo,
                        "NIMS structured retrieval timed out. Reopen NIMS login and retry this CR."
                    )
                }
            }, STRUCTURED_TIMEOUT_MS)
        }
    }

    private fun runStructuredCurrent() {
        val requestId = activeRequestId
        val crNo = activeCrNo
        if (requestId.isBlank() || crNo.isBlank()) return
        injectNimsRunner {
            val js = "window.AndroidNimsStructured.run(" +
                JSONObject.quote(crNo) + "," + JSONObject.quote(requestId) + ");"
            nims.evaluateJavascript(js, null)
        }
    }

    private fun processBulk(requestId: String, rawBulk: String) {
        if (requestId != activeRequestId || !bulkHandled.compareAndSet(false, true)) return
        val crNo = activeCrNo
        val patientJson = activePatientJson

        executor.execute {
            var inventoryError = ""
            val inventory = try {
                reportInventory.load(crNo)
            } catch (mismatch: NimsIdentityMismatch) {
                runOnUiThread {
                    postFetchError(requestId, crNo, mismatch.message ?: "NIMS identity verification failed.")
                }
                return@execute
            } catch (auth: NimsAuthRequired) {
                inventoryError = auth.message.orEmpty()
                InventoryResult(emptyList(), "report_inventory_unavailable", 0)
            } catch (error: Throwable) {
                inventoryError = error.message.orEmpty()
                InventoryResult(emptyList(), "report_inventory_unavailable", 0)
            }

            val options = JSONObject()
                .put("patient", runCatching { JSONObject(patientJson) }.getOrDefault(JSONObject().put("crNo", crNo)))
                .put("reportIndex", JSONArray().also { array -> inventory.reports.forEach { array.put(it.toJson()) } })

            runOnUiThread {
                sendNormalizedBundle(
                    requestId = requestId,
                    crNo = crNo,
                    rawBulk = rawBulk,
                    options = options,
                    inventory = inventory,
                    inventoryError = inventoryError
                )
            }
        }
    }

    private fun sendNormalizedBundle(
        requestId: String,
        crNo: String,
        rawBulk: String,
        options: JSONObject,
        inventory: InventoryResult,
        inventoryError: String
    ) {
        val rawQuoted = JSONObject.quote(rawBulk)
        val optionsQuoted = JSONObject.quote(options.toString())
        val requestQuoted = JSONObject.quote(requestId)
        val crQuoted = JSONObject.quote(crNo)
        val inventorySource = JSONObject.quote(inventory.source)
        val note = JSONObject.quote(inventoryError)
        val script = """
            (function(){
              try {
                if (!window.NimsBulkNormalizer) throw new Error('Dashboard normalizer unavailable.');
                const raw = JSON.parse($rawQuoted);
                const options = JSON.parse($optionsQuoted);
                const bundle = window.NimsBulkNormalizer.normalizeBulk(raw, $crQuoted, options);
                bundle.schemaVersion = 'nims-clinical-bundle/1.0';
                bundle.source = 'nims_bulk_api';
                bundle.patient = bundle.patient || { crNo: $crQuoted };
                bundle.patient.provenance = { source: 'nims', identityVerified: true };
                bundle.results = (bundle.results || []).map(row => ({
                  ...row,
                  provenance: { ...(row.provenance || {}), source: 'nims', retrieval: 'nims_bulk_api' }
                }));
                bundle.reports = (bundle.reports || []).map(report => ({
                  ...report,
                  provenance: { ...(report.provenance || {}), source: 'nims', retrieval: $inventorySource }
                }));
                bundle.coverage = {
                  ...(bundle.coverage || {}),
                  reportInventoryAvailable: bundle.reports.length > 0,
                  reportInventorySource: $inventorySource,
                  linkedReportCount: bundle.reports.filter(r => r.url).length,
                  structuredApiResultCount: bundle.results.length
                };
                bundle.reportInventory = {
                  source: $inventorySource,
                  reportCount: bundle.reports.length,
                  linkedReportCount: bundle.reports.filter(r => r.url).length
                };
                const diagnostics = {
                  strategy: 'nims_bulk_api',
                  attempts: [
                    { source: 'nims_bulk_api', ok: true },
                    { source: $inventorySource, ok: bundle.reports.length > 0 }
                  ]
                };
                if ($note) diagnostics.note = $note;
                if (window.__KBP_ANDROID_EMIT) {
                  window.__KBP_ANDROID_EMIT('KBP_NIMS_BULK_RESULTS', {
                    requestId: $requestQuoted,
                    crNo: $crQuoted,
                    payload: bundle,
                    diagnostics
                  });
                }
              } catch (error) {
                if (window.__KBP_ANDROID_EMIT) {
                  window.__KBP_ANDROID_EMIT('KBP_NIMS_FETCH_ERROR', {
                    requestId: $requestQuoted,
                    crNo: $crQuoted,
                    error: String(error && error.message || error || 'NIMS data normalization failed.')
                  });
                }
              }
            })();
        """.trimIndent()
        dashboard.evaluateJavascript(script, null)
    }

    private fun postFetchError(requestId: String, crNo: String, error: String) {
        postDashboard(
            "KBP_NIMS_FETCH_ERROR",
            JSONObject()
                .put("requestId", requestId)
                .put("crNo", crNo)
                .put("error", error)
        )
    }

    private fun postDashboard(type: String, payload: JSONObject = JSONObject()) {
        runOnUiThread {
            val js = """
                (function(){
                  const p = JSON.parse(${JSONObject.quote(payload.toString())});
                  if (window.__KBP_ANDROID_EMIT) {
                    window.__KBP_ANDROID_EMIT(${JSONObject.quote(type)}, p);
                  } else {
                    window.postMessage({type: ${JSONObject.quote(type)}, version:'0.7.0', platform:'android', ...p}, window.location.origin);
                  }
                })();
            """.trimIndent()
            dashboard.evaluateJavascript(js, null)
        }
    }

    private fun openNimsUrl(raw: String) {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return
        val host = uri.host?.lowercase().orEmpty()
        if (uri.scheme != "https" || host !in NIMS_HOSTS) return
        if (uri.path == ReportInventory.REPORT_PATH) {
            startActivity(
                Intent(this, ReportViewerActivity::class.java)
                    .putExtra(ReportViewerActivity.EXTRA_URL, raw)
            )
        } else {
            loginOverlay.visibility = View.VISIBLE
            nims.loadUrl(raw)
        }
    }

    private inner class DashboardInterface {
        @JavascriptInterface
        fun ping() {
            postDashboard("KBP_NIMS_PONG", JSONObject().put("ready", true))
        }

        @JavascriptInterface
        fun status() {
            postDashboard(
                "KBP_NIMS_STATUS",
                JSONObject()
                    .put("ok", true)
                    .put("loggedIn", loggedIn)
                    .put("state", if (loggedIn) "ready" else "signed_out")
            )
        }

        @JavascriptInterface
        fun login() {
            if (loggedIn) {
                postDashboard(
                    "KBP_NIMS_LOGIN_RESPONSE",
                    JSONObject().put("ok", true).put("loggedIn", true).put("state", "ready")
                )
            } else {
                postDashboard(
                    "KBP_NIMS_LOGIN_RESPONSE",
                    JSONObject().put("ok", true).put("loggedIn", false).put("state", "signing_in")
                )
                showLogin()
            }
        }

        @JavascriptInterface
        fun fetch(crNo: String, requestId: String) {
            runOnUiThread { startFetch(crNo, requestId) }
        }

        @JavascriptInterface
        fun helperStatus() {
            postDashboard(
                "KBP_NIMS_HELPER_STATUS",
                JSONObject()
                    .put("ok", true)
                    .put("settings", JSONObject().put("mode", "local").put("hasApiKey", false))
                    .put("health", JSONObject().put("ok", true))
            )
        }

        @JavascriptInterface
        fun helperSave() {
            postDashboard(
                "KBP_NIMS_HELPER_SAVE_RESPONSE",
                JSONObject()
                    .put("ok", false)
                    .put("error", "Android uses the built-in structured NIMS retrieval path.")
                    .put("health", JSONObject().put("ok", true))
            )
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            runOnUiThread { openNimsUrl(url) }
        }
    }

    private inner class NimsInterface {
        @JavascriptInterface
        fun onStructuredReady(requestId: String, patientJson: String) {
            if (requestId != activeRequestId) return
            activePatientJson = patientJson
        }

        @JavascriptInterface
        fun onBulkCapture(requestId: String, json: String) {
            if (requestId != activeRequestId || json.isBlank()) return
            processBulk(requestId, json)
        }

        @JavascriptInterface
        fun onStructuredError(requestId: String, error: String) {
            if (requestId != activeRequestId || bulkHandled.get()) return
            if (Regex("(?i)auth|login|session").containsMatchIn(error)) {
                loggedIn = false
                bulkHandled.set(true)
                showLogin()
                postFetchError(requestId, activeCrNo, error)
                return
            }
            if (structuredRetryCount < 1) {
                structuredRetryCount += 1
                retryStructuredOnNextAuthenticatedPage = true
                postDashboard(
                    "KBP_NIMS_RETRIEVAL_STAGE",
                    JSONObject()
                        .put("requestId", requestId)
                        .put("crNo", activeCrNo)
                        .put("stage", "structured_api")
                        .put("detail", "Reopening the authenticated NIMS shell and retrying once.")
                )
                runOnUiThread { nims.loadUrl(NIMS_LOGIN_URL) }
                return
            }
            bulkHandled.set(true)
            postFetchError(requestId, activeCrNo, error)
        }
    }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { JSONArray("[$raw]").optString(0) }.getOrDefault("")
    }

    private fun asset(name: String): String =
        assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        if (::nims.isInitialized) probeNims(autoCloseLogin = false)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            loginOverlay.visibility == View.VISIBLE -> loginOverlay.visibility = View.GONE
            dashboard.canGoBack() -> dashboard.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        runCatching { dashboard.destroy() }
        runCatching { nims.destroy() }
        super.onDestroy()
    }

    companion object {
        private const val DASHBOARD_URL = "https://nims-results-cockpit-ne5nig.v2.appdeploy.ai/"
        private const val DASHBOARD_ORIGIN = "https://nims-results-cockpit-ne5nig.v2.appdeploy.ai"
        private const val DASHBOARD_HOST = "nims-results-cockpit-ne5nig.v2.appdeploy.ai"
        private const val NIMS_LOGIN_URL = "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action"
        private val NIMS_HOSTS = setOf("nimsts.edu.in", "www.nimsts.edu.in")
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val STRUCTURED_TIMEOUT_MS = 65_000L
    }
}
