package org.kundakarlab.nimsresults

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

class LoginActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var continueButton: Button
    private lateinit var vault: SessionVault
    private lateinit var engineScript: String
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var injected = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = SessionVault(this)
        vault.restoreToWebView()
        engineScript = assets.open("nims_engine.js").bufferedReader().use { it.readText() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            setBackgroundColor(Color.rgb(12, 45, 76))
        }
        val title = TextView(this).apply {
            text = "NIMS login"
            setTextColor(Color.WHITE)
            textSize = 20f
        }
        status = TextView(this).apply {
            text = "Use the NIMS page below. This screen will stay exactly where it is if you switch apps."
            setTextColor(Color.rgb(220, 232, 242))
            textSize = 13f
            setPadding(0, dp(5), 0, dp(8))
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val back = Button(this).apply {
            text = "Cancel"
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        val reload = Button(this).apply {
            text = "Open login"
            setOnClickListener {
                injected = false
                webView.loadUrl(NimsUrls.LOGIN)
            }
        }
        continueButton = Button(this).apply {
            text = "Continue"
            isEnabled = false
            setOnClickListener {
                vault.captureFromWebView()
                setResult(RESULT_OK)
                finish()
            }
        }
        actions.addView(back)
        actions.addView(reload)
        actions.addView(continueButton)

        top.addView(title)
        top.addView(status)
        top.addView(actions)
        root.addView(top)

        webView = WebView(this)
        configureWebView()
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        setContentView(root)

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(NimsUrls.LOGIN)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        CookieManager.getInstance().setAcceptCookie(true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = NimsUrls.DESKTOP_UA
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host?.lowercase().orEmpty()
                return uri.scheme != "https" || host !in NimsUrls.HOSTS
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                injected = false
                continueButton.isEnabled = false
                status.text = "NIMS page is loading. Your session and page are kept inside this app."
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()
                injectAndProbe()
            }
        }
    }

    private fun injectAndProbe() {
        if (!injected) {
            webView.evaluateJavascript(engineScript) {
                injected = true
                probe()
            }
        } else {
            probe()
        }
    }

    private fun probe() {
        if (!injected) return
        webView.evaluateJavascript(
            "JSON.stringify(window.NativeNimsEngine ? window.NativeNimsEngine.probe() : {})"
        ) { raw ->
            val json = decodeJsString(raw)
            val obj = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
            val authenticated = obj.optBoolean("authenticated", false)
            val expired = obj.optBoolean("sessionExpired", false)
            val loginForm = obj.optBoolean("loginForm", false)
            continueButton.isEnabled = authenticated
            status.text = when {
                authenticated -> {
                    vault.captureFromWebView()
                    "Login verified. Tap Continue. You will not be asked to log in again unless the NIMS server session expires."
                }
                expired -> "The previous NIMS session has expired. Log in again and complete CAPTCHA."
                loginForm -> "Enter NIMS credentials and CAPTCHA below. Switching to another app will not reset this page."
                else -> "Continue through the NIMS login page below. This screen is not reloaded when you switch apps."
            }
        }
    }

    private val periodicProbe = object : Runnable {
        override fun run() {
            if (!resumed) return
            injectAndProbe()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        handler.removeCallbacks(periodicProbe)
        handler.postDelayed(periodicProbe, 700)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(periodicProbe)
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { webView.stopLoading() }
        runCatching { webView.destroy() }
        super.onDestroy()
    }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { JSONArray("[$raw]").optString(0) }.getOrDefault("")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
