package org.kundakarlab.nimsresults

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
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
        fun onError(requestId: String, message: String)
    }

    val webView: WebView = WebView(context)
    private val engineScript = context.assets.open("nims_engine.js").bufferedReader().use { it.readText() }
    private var pageReady = false
    private var pendingProbe: ((SessionState) -> Unit)? = null
    private var pendingRun: Pair<String, String>? = null

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
            val state = when {
                probe.optBoolean("sessionExpired", false) -> SessionState.EXPIRED
                probe.optBoolean("authenticated", false) -> SessionState.CONNECTED
                probe.optBoolean("loginForm", false) -> SessionState.LOGIN_REQUIRED
                else -> SessionState.LOGIN_REQUIRED
            }
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
