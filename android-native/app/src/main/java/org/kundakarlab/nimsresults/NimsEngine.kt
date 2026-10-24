package org.kundakarlab.nimsresults

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject

class NimsEngine(
    private val context: Context,
    parent: FrameLayout,
    private val vault: SessionVault,
    private val listener: Listener
) {
    interface Listener {
        fun onSession(state: SessionState)
        fun onStage(requestId: String, stage: String)
        fun onPatient(requestId: String, patient: PatientInfo)
        fun onBulk(requestId: String, rawJson: String)
        fun onReportIndexHtml(requestId: String, html: String, code: String)
        fun onError(requestId: String, message: String)
    }

    val webView: WebView = WebView(context)
    private val engineScript = context.assets.open("nims_engine.js").bufferedReader().use { it.readText() }
    private var pageReady = false
    private var pendingProbe: ((SessionState) -> Unit)? = null
    private var pendingRun: Pair<String, String>? = null
    private var ambiguousProbeCount = 0
    @Volatile private var lastProbeJson: String = "{}"

    @Volatile
    var sessionState: SessionState = SessionState.UNKNOWN
        private set

    @Volatile
    var activeRequestId: String = ""
        private set

    init {
        configure()
        vault.restoreToWebView()
        parent.addView(
            webView,
            0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        webView.alpha = 0.01f
        webView.isClickable = false
        webView.isFocusable = false
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.loadUrl(NimsUrls.LOGIN)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure() {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = NimsUrls.DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webView.addJavascriptInterface(Bridge(), "AndroidNimsBridge")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host?.lowercase().orEmpty()
                return uri.scheme != "https" || host !in NimsUrls.HOSTS
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageReady = false
                ambiguousProbeCount = 0
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                inject {
                    pageReady = true
                    probeInternal()
                }
            }
        }
    }

    fun checkSession(callback: ((SessionState) -> Unit)? = null) {
        pendingProbe = callback
        if (!pageReady) {
            if (webView.url.isNullOrBlank()) webView.loadUrl(NimsUrls.LOGIN)
            return
        }
        probeInternal()
    }

    fun reloadSession() {
        pageReady = false
        webView.loadUrl(NimsUrls.LOGIN)
    }

    fun run(crNo: String, requestId: String) {
        activeRequestId = requestId
        if (!pageReady) {
            pendingRun = crNo to requestId
            return
        }
        pendingRun = null
        val js = "window.NativeNimsEngine && window.NativeNimsEngine.run(" +
            JSONObject.quote(crNo) + "," + JSONObject.quote(requestId) + ");"
        webView.evaluateJavascript(js, null)
    }

    /** Same-origin fallback: runs in the already-authenticated NIMS WebView,
     * not a new login window. HTML is sent only to the local Java bridge and
     * must be re-validated for the requested CR by ReportPayloadParser. */
    fun fetchReportIndexHtml(crNo: String, requestId: String) {
        if (!pageReady || requestId != activeRequestId) {
            listener.onReportIndexHtml(requestId, "", "webview_unavailable")
            return
        }
        val script = """
          (async function(cr, req) {
            function done(html, code) {
              try { window.AndroidNimsBridge.onReportIndexHtml(req, html || "", code); }
              catch (_) { }
            }
            try {
              const endpoint = ${JSONObject.quote(NimsUrls.CR_REPORTS)};
              const origin = new URL(endpoint).origin;
              if (window.location.origin !== origin) {
                done("", "origin_mismatch"); return;
              }
              const first = await fetch(endpoint, { credentials:"include", redirect:"follow",
                  cache:"no-store" });
              if (!first.ok) { done("", "landing_http_error"); return; }
              const landing = await first.text();
              if (landing.length > 2500000) { done("", "landing_too_large"); return; }
              const documentNode = new DOMParser().parseFromString(landing, "text/html");
              const form = documentNode.querySelector(
                  'form[name="viewExternalInvFB"],form#viewExternalInvFB,form[action*="viewcrnowisereportprocess"]');
              const fields = new URLSearchParams();
              if (form) {
                form.querySelectorAll('input[type="hidden"][name]').forEach(input => {
                  if (!/^(?:hmode|patCrNo)$/i.test(input.name))
                    fields.set(input.name, input.value);
                });
              }
              fields.set("hmode","SHOWPATDETAILS");
              fields.set("patCrNo", cr);
              const dest = form ? new URL(form.getAttribute("action") || endpoint, endpoint) :
                  new URL(endpoint);
              if (dest.origin !== origin) { done("", "post_origin_mismatch"); return; }
              const response = await fetch(dest.toString(), {
                method:"POST", credentials:"include", redirect:"follow", cache:"no-store",
                headers: {"Content-Type":"application/x-www-form-urlencoded"},
                body:fields.toString()
              });
              if (!response.ok) { done("", "post_http_error"); return; }
              const result = await response.text();
              if (result.length > 2500000) { done("", "html_too_large"); return; }
              done(result, "ok");
            } catch (error) { done("", "webview_fetch_error"); }
          })(${JSONObject.quote(crNo)}, ${JSONObject.quote(requestId)});
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    fun safeDiagnostics(): String {
        val probe = runCatching { JSONObject(lastProbeJson) }.getOrDefault(JSONObject())
        val current = runCatching { Uri.parse(webView.url.orEmpty()) }.getOrNull()
        val path = buildString {
            append(current?.scheme.orEmpty())
            append("://")
            append(current?.host.orEmpty())
            append(current?.path.orEmpty())
        }
        val cookiePresent = runCatching {
            CookieManager.getInstance().getCookie(NimsUrls.WWW_ORIGIN).orEmpty().isNotBlank() ||
                CookieManager.getInstance().getCookie(NimsUrls.APEX_ORIGIN).orEmpty().isNotBlank()
        }.getOrDefault(false)

        return listOf(
            "engine.sessionState=$sessionState",
            "engine.pageReady=$pageReady",
            "engine.currentPath=$path",
            "engine.cookiePresent=$cookiePresent",
            "probe.authenticated=${probe.optBoolean("authenticated", false)}",
            "probe.loginRoute=${probe.optBoolean("loginRoute", false)}",
            "probe.loginForm=${probe.optBoolean("loginForm", false)}",
            "probe.sessionExpired=${probe.optBoolean("sessionExpired", false)}",
            "probe.shellEvidence=${probe.optBoolean("shellEvidence", false)}",
            "probe.welcomeEvidence=${probe.optBoolean("welcomeEvidence", false)}",
            "probe.navEvidence=${probe.optBoolean("navEvidence", false)}",
            "probe.menuPresent=${probe.optBoolean("menuPresent", false)}",
            "probe.trendFramePresent=${probe.optBoolean("trendFramePresent", false)}",
            "probe.documentCount=${probe.optInt("documentCount", 0)}",
            "engine.pendingRun=${pendingRun != null}",
            "engine.activeRequest=${activeRequestId.isNotBlank()}"
        ).joinToString("\n")
    }

    fun destroy() {
        runCatching { webView.removeJavascriptInterface("AndroidNimsBridge") }
        runCatching { webView.stopLoading() }
        runCatching { webView.destroy() }
    }

    private fun inject(after: (() -> Unit)? = null) {
        webView.evaluateJavascript(engineScript) { after?.invoke() }
    }

    private fun probeInternal() {
        val js = "JSON.stringify(window.NativeNimsEngine ? window.NativeNimsEngine.probe() : {})"
        webView.evaluateJavascript(js) { raw ->
            val json = decodeJsString(raw)
            val probe = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
            lastProbeJson = probe.toString()
            // A bare login URL without a form is often an intermediate HIS
            // frameset load, not evidence that the authenticated session died.
            // Never claim CONNECTED from a cookie alone; retry before login.
            val definite = probe.optBoolean("sessionExpired", false) ||
                probe.optBoolean("authenticated", false) ||
                probe.optBoolean("loginForm", false)
            val hasCookie = CookieManager.getInstance().getCookie(NimsUrls.WWW_ORIGIN)
                .isNullOrBlank().not() ||
                CookieManager.getInstance().getCookie(NimsUrls.APEX_ORIGIN)
                    .isNullOrBlank().not()
            val state = when {
                probe.optBoolean("sessionExpired", false) -> SessionState.EXPIRED
                probe.optBoolean("authenticated", false) -> SessionState.CONNECTED
                probe.optBoolean("loginForm", false) -> SessionState.LOGIN_REQUIRED
                hasCookie && ambiguousProbeCount < 3 -> SessionState.CHECKING
                else -> SessionState.LOGIN_REQUIRED
            }
            if (state == SessionState.CHECKING && !definite) {
                ambiguousProbeCount++
                sessionState = state
                listener.onSession(state)
                webView.postDelayed({ if (pageReady) probeInternal() }, 1100L)
                return@evaluateJavascript
            }
            ambiguousProbeCount = 0
            sessionState = state
            if (state == SessionState.CONNECTED) vault.captureFromWebView()
            listener.onSession(state)
            pendingProbe?.invoke(state)
            pendingProbe = null
            val queued = pendingRun
            if (queued != null) {
                if (state == SessionState.CONNECTED) {
                    pendingRun = null
                    run(queued.first, queued.second)
                } else {
                    pendingRun = null
                    listener.onError(queued.second, "NIMS authentication required.")
                }
            }
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onStage(requestId: String, stage: String) {
            if (requestId != activeRequestId) return
            listener.onStage(requestId, stage)
        }

        @JavascriptInterface
        fun onPatient(requestId: String, json: String) {
            if (requestId != activeRequestId) return
            val obj = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
            val patient = PatientInfo(
                name = obj.optString("name").trim(),
                crNo = obj.optString("crNo").filter(Char::isDigit),
                age = obj.optString("age").trim(),
                sex = obj.optString("sex").trim()
            )
            listener.onPatient(requestId, patient)
        }

        @JavascriptInterface
        fun onBulk(requestId: String, json: String) {
            if (requestId != activeRequestId || json.isBlank()) return
            vault.captureFromWebView()
            listener.onBulk(requestId, json)
        }

        @JavascriptInterface
        fun onReportIndexHtml(requestId: String, html: String, code: String) {
            if (requestId != activeRequestId) return
            listener.onReportIndexHtml(requestId, html.take(2500000), code.take(50))
        }

        @JavascriptInterface
        fun onError(requestId: String, message: String) {
            if (requestId.isNotBlank() && requestId != activeRequestId) return
            if (Regex("(?i)auth|login|session").containsMatchIn(message)) {
                sessionState = SessionState.LOGIN_REQUIRED
                listener.onSession(SessionState.LOGIN_REQUIRED)
            }
            listener.onError(requestId, message)
        }
    }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { JSONArray("[$raw]").optString(0) }.getOrDefault("")
    }
}
