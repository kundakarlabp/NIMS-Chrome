package org.kundakarlab.nimsresults

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
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
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject

class LoginActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var status: TextView
    private lateinit var continueButton: Button
    private lateinit var signOutButton: Button
    private var manageSession = false
    private var signOutPending = false
    private lateinit var vault: SessionVault
    private lateinit var engineScript: String
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var injected = false
    private var authCompletionScheduled = false
    private var lastProbeSummary = "Login probe has not completed yet."

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = SessionVault(this)
        manageSession = intent.getBooleanExtra(EXTRA_MANAGE_SESSION, false)
        vault.restoreToWebView()
        engineScript = assets.open("nims_engine.js").bufferedReader().use { it.readText() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            setBackgroundColor(Color.rgb(12, 45, 76))
        }
        val title = TextView(this).apply {
            text = if (manageSession) "NIMS session" else "NIMS login"
            setTextColor(Color.WHITE)
            textSize = 18f
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
        signOutButton = Button(this).apply {
            text = "Sign out NIMS"
            isEnabled = false
            setOnClickListener { requestPortalSignOut() }
        }
        actions.addView(back)
        actions.addView(reload)
        actions.addView(continueButton)
        if (manageSession) actions.addView(signOutButton)

        val diagnosticActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val copyDiagnostics = Button(this).apply {
            text = "Copy diagnostics"
            setOnClickListener { copyDiagnostics() }
        }
        diagnosticActions.addView(copyDiagnostics)

        top.addView(title)
        top.addView(status)
        top.addView(actions)
        top.addView(diagnosticActions)
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
                authCompletionScheduled = false
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
            signOutButton.isEnabled = authenticated && !signOutPending
            val expired = obj.optBoolean("sessionExpired", false)
            val loginForm = obj.optBoolean("loginForm", false)
            continueButton.isEnabled = authenticated
            lastProbeSummary = safeProbeSummary(obj)
            if (signOutPending && (loginForm || expired)) {
                signOutPending = false
                vault.clear()
                setResult(RESULT_FIRST_USER)
                finish()
                return@evaluateJavascript
            }
            status.text = when {
                signOutPending -> "NIMS sign-out requested. Waiting for the portal to confirm logout…"
                authenticated -> {
                    vault.captureFromWebView()
                    if (!manageSession && !authCompletionScheduled) {
                        authCompletionScheduled = true
                        handler.postDelayed({
                            if (!isFinishing && !signOutPending) {
                                vault.captureFromWebView()
                                setResult(RESULT_OK)
                                finish()
                            }
                        }, 900)
                    }
                    if (manageSession) "NIMS is connected. Use Sign out NIMS to release this portal session."
                    else "NIMS login verified. Returning to results automatically…"
                }
                expired -> "The previous NIMS session has expired. Log in again and complete CAPTCHA."
                loginForm -> "Enter NIMS credentials and CAPTCHA below. Switching to another app will not reset this page."
                else -> "Waiting for NIMS authentication. If the NIMS home page is visible, use Copy diagnostics and send the text."
            }
        }
    }

    private fun requestPortalSignOut() {
        if (signOutPending) return
        // Click only a visible NIMS portal Logout control, never guessed endpoints.
        // The request is confirmed only after the page becomes a login form or
        // the portal reports session expiry. Clearing cookies alone does not
        // release a NIMS server-side session slot.
        val js = """
          (() => {
            const documents = [document], seen = new Set();
            for (let i = 0; i < documents.length && i < 12; i++) {
              const doc = documents[i];
              if (seen.has(doc)) continue;
              seen.add(doc);
              try {
                for (const frame of doc.querySelectorAll('iframe,frame')) {
                  try { if (frame.contentDocument) documents.push(frame.contentDocument); } catch (_) {}
                }
              } catch (_) {}
            }
            for (const doc of documents) {
              try {
                for (const el of doc.querySelectorAll('a,button,input[type="button"],input[type="submit"]')) {
                  const label = (el.innerText || el.textContent || el.value || '').trim();
                  if (/^(log\\s*out|logout|sign\\s*out)$/i.test(label)) {
                    el.click();
                    return 'clicked';
                  }
                }
              } catch (_) {}
            }
            return 'unavailable';
          })()
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            if (decodeJsString(raw) == "clicked") {
                signOutPending = true
                signOutButton.isEnabled = false
                status.text = "Requested portal logout; waiting for NIMS confirmation…"
                handler.postDelayed({
                    if (signOutPending && !isFinishing) {
                        signOutPending = false
                        status.text = "NIMS did not confirm sign-out. Use the portal menu's Logout control."
                        probe()
                    }
                }, 12_000L)
            } else {
                status.text = "No NIMS portal Logout control found. Open its menu and sign out manually; local cookie clearing will not release the server slot."
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

    private fun safeProbeSummary(obj: JSONObject): String {
        val rawHref = obj.optString("href")
        val uri = runCatching { Uri.parse(rawHref) }.getOrNull()
        val safePath = buildString {
            append(uri?.scheme.orEmpty())
            append("://")
            append(uri?.host.orEmpty())
            append(uri?.path.orEmpty())
        }
        return listOf(
            "screen=login",
            "url=$safePath",
            "authenticated=${obj.optBoolean("authenticated", false)}",
            "loginRoute=${obj.optBoolean("loginRoute", false)}",
            "loginForm=${obj.optBoolean("loginForm", false)}",
            "sessionExpired=${obj.optBoolean("sessionExpired", false)}",
            "shellEvidence=${obj.optBoolean("shellEvidence", false)}",
            "welcomeEvidence=${obj.optBoolean("welcomeEvidence", false)}",
            "navEvidence=${obj.optBoolean("navEvidence", false)}",
            "menuPresent=${obj.optBoolean("menuPresent", false)}",
            "trendFramePresent=${obj.optBoolean("trendFramePresent", false)}",
            "documentCount=${obj.optInt("documentCount", 0)}",
            "cookiePresent=${CookieManager.getInstance().getCookie(NimsUrls.WWW_ORIGIN).orEmpty().isNotBlank()}"
        ).joinToString("\n")
    }

    private fun copyDiagnostics() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NIMS diagnostics", lastProbeSummary))
        Toast.makeText(this, "Safe diagnostics copied", Toast.LENGTH_SHORT).show()
    }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { JSONArray("[$raw]").optString(0) }.getOrDefault("")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MANAGE_SESSION = "manage_session"
    }
}
