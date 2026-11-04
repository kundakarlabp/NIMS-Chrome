package org.kundakarlab.nimsresults

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
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
import android.webkit.WebResourceError
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
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
    private lateinit var networkNotice: TextView
    private var alternateAttempted = false
    private var portalUnavailable = false
    private var networkErrorCode = 0
    private var failedHostname = ""
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
            text = if (manageSession) "NIMS · Punjagutta | Session"
                else "NIMS · Punjagutta | Sign in"
            setTextColor(Color.WHITE)
            textSize = 17f
        }
        val institute = TextView(this).apply {
            text = "Nizam's Institute of Medical Sciences, Hyderabad"
            setTextColor(Color.rgb(196, 216, 234))
            textSize = 11f
        }
        status = TextView(this).apply {
            text = "Official NIMS portal · your login remains server-controlled"
            setTextColor(Color.rgb(220, 232, 242))
            textSize = 11f
            setPadding(0, dp(3), 0, dp(3))
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
                alternateAttempted = false
                portalUnavailable = false
                networkNotice.visibility = View.GONE
                webView.visibility = View.VISIBLE
                status.text = "Retrying the NIMS hospital login…"
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

        val diagnosticActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val copyDiagnostics = Button(this).apply {
            text = "Copy diagnostics"
            setOnClickListener { copyDiagnostics() }
        }
        if (manageSession) diagnosticActions.addView(signOutButton)
        diagnosticActions.addView(copyDiagnostics)

        top.addView(title)
        top.addView(institute)
        top.addView(status)
        top.addView(actions)
        top.addView(diagnosticActions)
        if(manageSession) {
            val choices=LinearLayout(this).apply {
                orientation=LinearLayout.HORIZONTAL
                gravity=Gravity.START
            }
            val show=Button(this).apply {
                text="Show NIMS sessions"
                setOnClickListener { showPortalSessions() }
            }
            val specific=Button(this).apply {
                text="Logout one"
                setOnClickListener { showPortalSessions() }
            }
            val all=Button(this).apply {
                text="Logout all"
                setOnClickListener {
                    AlertDialog.Builder(this@LoginActivity)
                        .setTitle("Logout all NIMS sessions?")
                        .setMessage("This uses the hospital portal's own Logout all action. It may sign out other devices. Continue?")
                        .setNegativeButton("Cancel",null)
                        .setPositiveButton("Logout all") { _,_ -> logoutAllPortalSessions() }
                        .show()
                }
            }
            val portalBack=Button(this).apply {
                text="Portal back"
                setOnClickListener {
                    if(webView.canGoBack()) webView.goBack()
                    else status.text="No previous NIMS page is available. Use Show NIMS sessions."
                }
            }
            choices.addView(show)
            choices.addView(specific)
            choices.addView(all)
            choices.addView(portalBack)
            top.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled=true
                addView(choices)
            })
        }
        root.addView(top)

        networkNotice = TextView(this).apply {
            visibility = View.GONE
            textSize = 14f
            setTextColor(Color.rgb(110, 38, 27))
            setPadding(dp(18), dp(20), dp(18), dp(18))
        }
        root.addView(networkNotice)
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
                if (url.startsWith("https://www.nimsts.edu.in/") ||
                    url.startsWith("https://nimsts.edu.in/")) {
                    portalUnavailable = false
                    networkNotice.visibility = View.GONE
                    webView.visibility = View.VISIBLE
                }
                injected = false
                continueButton.isEnabled = false
                authCompletionScheduled = false
                status.text = "NIMS page is loading. Your session and page are kept inside this app."
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                if (!request.isForMainFrame) return
                val host = request.url.host.orEmpty().lowercase()
                if (host !in NimsUrls.HOSTS) return
                val code = error.errorCode
                val alternate = NimsLoginRecovery.alternateLoginUrl(
                    request.url.toString(), code, alternateAttempted
                )
                if (alternate != null) {
                    alternateAttempted = true
                    injected = false
                    status.text = "NIMS $host cannot be resolved. Trying the hospital alternate host…"
                    view.post { if (!isFinishing) view.loadUrl(alternate) }
                    return
                }
                portalUnavailable = true
                networkErrorCode = code
                failedHostname = host
                injected = false
                continueButton.isEnabled = false
                signOutButton.isEnabled = false
                status.text = "NIMS hospital portal is unreachable. Login was not attempted."
                networkNotice.text = NimsLoginRecovery.errorDescription(code) +
                    "\n\nHospital host: $host" +
                    "\n\nThis is a network/server availability problem, not a password or CAPTCHA error." +
                    "\nUse Retry connection when NIMS works in your phone's Chrome browser." +
                    "\nThe app will retain existing saved session cookies and will not clear patient data."
                networkNotice.visibility = View.VISIBLE
                webView.visibility = View.INVISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (portalUnavailable || url.startsWith("chrome-error://")) return
                CookieManager.getInstance().flush()
                injectAndProbe()
            }
        }
    }

    private fun injectAndProbe() {
        if (portalUnavailable) return
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
                  if (/^(log\s*out|logout|sign\s*out)$/i.test(label)) {
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

    /**
     * Session controls are owned by NIMS. Reveal its existing session list;
     * do not invent logout URLs or remove DOM elements to bypass login.
     */
    private fun showPortalSessions() {
        val js = """
          (() => {
            const docs=[document], seen=new Set();
            for(let i=0;i<docs.length && i<10;i++) {
              const doc=docs[i]; if(seen.has(doc)) continue;
              seen.add(doc);
              for(const frame of doc.querySelectorAll('iframe,frame')) {
                try { if(frame.contentDocument) docs.push(frame.contentDocument); }
                catch(_) {}
              }
            }
            function label(node) {
              return (node.innerText || node.textContent || node.value ||
                node.getAttribute('aria-label') || '').replace(/\s+/g,' ').trim();
            }
            const controls=[];
            for(const doc of docs) {
              for(const element of doc.querySelectorAll(
                'a,button,input[type=button],input[type=submit],[role=button]')) {
                const text=label(element);
                if(/session|active\s*login|logged\s*in|log\s*out\s*(?:all|one|selected)/i.test(text))
                  controls.push({element,text});
              }
            }
            if(!controls.length) return 'not_found';
            // The user's session list is already on the hospital page.
            // Never click "logout all" from the Show button.
            const safe=controls.find(x =>
              /^(?:manage|view|show|select|active)\b.*(?:session|login)/i.test(x.text));
            const target=safe || controls.find(x =>
              /log\s*out\s*(?:all|one|selected)|logout\s*(?:all|one|selected)/i.test(x.text));
            if(!target) return 'not_found';
            // If the portal's login popup overlays its existing session list,
            // dismiss it via its own Close/Cancel control only.
            const doc=target.element.ownerDocument;
            for(const dialog of doc.querySelectorAll('.modal.show,[role=dialog]')) {
              if(dialog.contains(target.element)) continue;
              const heading=label(dialog).slice(0,350);
              if(!/login|log\s*in|sign\s*in|password/i.test(heading)) continue;
              const close=dialog.querySelector(
                '[data-bs-dismiss=modal],[data-dismiss=modal],[aria-label=Close],.modal-header .close');
              if(close) { try { close.click(); } catch(_) {} }
            }
            if(safe) {
              safe.element.click();
              return 'opened';
            }
            target.element.scrollIntoView({block:'center',behavior:'auto'});
            target.element.focus();
            return 'visible';
          })()
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            status.text=when(decodeJsString(raw)) {
                "opened","visible" ->
                    "NIMS sessions are displayed in the portal. Select the exact login to logout, or use Logout all."
                else ->
                    "NIMS did not expose its session list on this page. Use Portal back or the portal's own session menu; no logout was performed."
            }
        }
    }

    /** Only invoke the hospital's explicitly labelled Logout all control
     * after the user approves it in the native confirmation dialog. */
    private fun logoutAllPortalSessions() {
        val js = """
          (() => {
            const docs=[document],seen=new Set();
            for(let i=0;i<docs.length && i<10;i++) {
              const doc=docs[i];if(seen.has(doc))continue;seen.add(doc);
              for(const f of doc.querySelectorAll('iframe,frame')) {
                try { if(f.contentDocument) docs.push(f.contentDocument); } catch(_) {}
              }
            }
            const matches=[];
            for(const doc of docs) for(const node of doc.querySelectorAll(
              'a,button,input[type=button],input[type=submit],[role=button]')) {
              const text=(node.innerText||node.textContent||node.value||'')
                .replace(/\s+/g,' ').trim();
              if(/^(?:log\s*out|logout|sign\s*out)\s+all(?:\s+(?:sessions?|logins?))?$/i.test(text))
                matches.push(node);
            }
            if(matches.length!==1) return 'ambiguous';
            matches[0].click();return 'requested';
          })()
        """.trimIndent()
        webView.evaluateJavascript(js) { raw ->
            if(decodeJsString(raw)=="requested") {
                signOutPending=true
                status.text="Requested Logout all using the NIMS portal. Confirm the portal's response before signing in again."
                probe()
            } else {
                status.text="NIMS Logout all control was not uniquely accessible. Open the hospital session list and use its own button; no logout was performed."
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
            "network.portalUnavailable=$portalUnavailable",
            "network.lastErrorCode=$networkErrorCode",
            "network.failedHost=$failedHostname",
            "network.alternateAttempted=$alternateAttempted",
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
