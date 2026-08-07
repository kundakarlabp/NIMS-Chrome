package org.kundakarlab.nimsfastsummarymobile

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manual-login launcher gate.
 *
 * The gate deliberately does not submit credentials, read credentials, or force
 * navigation to a protected NIMS endpoint while the portal is authenticating.
 * A user-visible login form must be observed during this app launch and NIMS
 * must then naturally leave that login route (or expose strong authenticated
 * evidence) before the results workflow can start.
 */
class LoginGateActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private val diagnostics = StringBuilder()

    private var status by mutableStateOf("Opening NIMS login…")
    private var loginFormSeen = false
    private var launched = false
    private var lastFinishedUrl = ""
    private var lastLoginNavigationAt = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.userAgentString = DESKTOP_CHROME_UA
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

                override fun onPageFinished(view: WebView, url: String) {
                    lastFinishedUrl = url
                    log("PAGE ${safeStage(url)}")
                    handler.postDelayed({ inspectAndAdvance(0) }, 180L)
                }
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF006B9E))) {
                LoginGateScreen(
                    webView = webView,
                    status = status,
                    onContinue = ::verifyLogin,
                    onLogoutOtherSessions = ::logoutOtherSessions,
                    onReloadLogin = ::beginFreshLogin,
                    onCopyLogs = ::copyLogs
                )
            }
        }

        log("BUILD versionName=${BuildConfig.VERSION_NAME} versionCode=${BuildConfig.VERSION_CODE}")
        beginFreshLogin()
    }

    private fun beginFreshLogin() {
        if (launched) return
        loginFormSeen = false
        lastFinishedUrl = ""
        lastLoginNavigationAt = 0L
        status = "Opening the NIMS login page…"
        handler.removeCallbacksAndMessages(null)
        WebStorage.getInstance().deleteAllData()
        webView.stopLoading()
        webView.clearCache(true)
        webView.clearHistory()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            log("Fresh login session started")
            webView.loadUrl(NIMS_LOGIN_URL)
        }
    }

    private fun verifyLogin() {
        if (launched) return
        status = "Checking NIMS login…"
        inspectAndAdvance(0, userRequestedVerification = true)
    }

    private fun inspectAndAdvance(
        attempt: Int,
        userRequestedVerification: Boolean = false
    ) {
        if (launched || isFinishing || isDestroyed) return
        webView.evaluateJavascript(LOGIN_GATE_SCRIPT) { raw ->
            val probe = decodeObject(raw)
            val loginVisible = probe.optBoolean("loginVisible")
            val publicLanding = probe.optBoolean("publicLanding")
            val crReady = probe.optBoolean("crReady")
            val reportRows = probe.optInt("reportRows")
            val logoutVisible = probe.optBoolean("logoutVisible")
            val sessionExpired = probe.optBoolean("sessionExpired")
            val leftLoginRoute = isAllowedNimsUrl(lastFinishedUrl) && !isLoginRoute(lastFinishedUrl)

            log(
                "AUTH login=$loginVisible public=$publicLanding leftLogin=$leftLoginRoute " +
                    "cr=$crReady rows=$reportRows logout=$logoutVisible expired=$sessionExpired"
            )

            if (loginVisible) {
                loginFormSeen = true
                status = if (userRequestedVerification) {
                    "Enter user ID, password and captcha, submit the NIMS form, then wait."
                } else {
                    "Enter user ID, password and captcha, then submit the NIMS form."
                }
                return@evaluateJavascript
            }

            if (
                LoginGatePolicy.canEnterResults(
                    loginFormSeen = loginFormSeen,
                    loginVisible = loginVisible,
                    publicLanding = publicLanding,
                    sessionExpired = sessionExpired,
                    leftLoginRoute = leftLoginRoute,
                    crReady = crReady,
                    reportRows = reportRows,
                    logoutVisible = logoutVisible
                )
            ) {
                openResultsWorkflow()
                return@evaluateJavascript
            }

            if (sessionExpired) {
                status = "NIMS session expired. Reload login and sign in again."
                return@evaluateJavascript
            }

            if (loginFormSeen) {
                // Important: never force-load the CR endpoint here. NIMS may be
                // completing its own SSO redirect and session initialization.
                status = if (isLoginRoute(lastFinishedUrl)) {
                    "Login submitted. Waiting for NIMS to complete authentication…"
                } else {
                    "NIMS login is being verified…"
                }
                if (attempt < MAX_POST_LOGIN_WAIT_ATTEMPTS) {
                    handler.postDelayed(
                        { inspectAndAdvance(attempt + 1, userRequestedVerification) },
                        if (attempt < 5) 450L else 750L
                    )
                } else {
                    status = "NIMS did not complete the login transition. Check the captcha or reload login."
                }
                return@evaluateJavascript
            }

            if (publicLanding) {
                openPortalLoginNavigationIfDue()
                status = "Opening the NIMS credential and captcha form…"
            } else {
                status = "Preparing the NIMS login page…"
            }

            if (attempt < MAX_LOGIN_NAVIGATION_ATTEMPTS) {
                handler.postDelayed(
                    { inspectAndAdvance(attempt + 1, userRequestedVerification) },
                    if (attempt < 4) 400L else 700L
                )
            } else {
                status = "The NIMS login form did not open. Tap Reload login."
            }
        }
    }

    private fun openPortalLoginNavigationIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLoginNavigationAt < LOGIN_NAVIGATION_COOLDOWN_MS) return
        lastLoginNavigationAt = now
        webView.evaluateJavascript(OPEN_LOGIN_NAVIGATION_SCRIPT) { result ->
            val action = runCatching { JSONArray("[$result]").getString(0) }.getOrDefault("none")
            if (action != "none") log("LOGIN_NAV action=$action")
        }
    }

    private fun openResultsWorkflow() {
        if (launched) return
        launched = true
        status = "NIMS login verified. Opening patient results…"
        CookieManager.getInstance().flush()
        log("AUTH verified; opening production workflow")
        val handoffUrl = lastFinishedUrl.takeIf(::isAllowedNimsUrl).orEmpty()
        startActivity(
            Intent(this, ProductionWorkflowActivity::class.java)
                .putExtra(EXTRA_VERIFIED_LOGIN, true)
                .putExtra(EXTRA_HANDOFF_URL, handoffUrl)
        )
        finish()
    }

    private fun logoutOtherSessions() {
        webView.evaluateJavascript(NimsPortalBridge.logoutOtherSessionsScript) { raw ->
            status = if (raw.contains("clicked")) {
                "Other-session logout requested. Complete login again."
            } else {
                "The NIMS page is not showing an other-session logout option."
            }
        }
    }

    private fun copyLogs() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NIMS Results login log", diagnostics.toString()))
        status = "Login diagnostic log copied."
    }

    private fun log(message: String) {
        if (diagnostics.isNotEmpty()) diagnostics.append('\n')
        diagnostics.append(message.take(260))
    }

    private fun safeStage(url: String): String = when {
        url.contains("viewcrnowisereportprocess", ignoreCase = true) -> "cr_module"
        isLoginRoute(url) -> "login_route"
        isAllowedNimsUrl(url) -> "nims_page"
        else -> "other"
    }

    private fun isLoginRoute(url: String): Boolean = url.contains("loginLogin.action", ignoreCase = true)

    private fun isAllowedNimsUrl(url: String): Boolean = runCatching {
        val uri = android.net.Uri.parse(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            (uri.host.equals("www.nimsts.edu.in", ignoreCase = true) || uri.host.equals("nimsts.edu.in", ignoreCase = true))
    }.getOrDefault(false)

    private fun decodeObject(raw: String): JSONObject = runCatching {
        JSONObject(JSONArray("[$raw]").getString(0))
    }.getOrDefault(JSONObject())

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_VERIFIED_LOGIN = "nims_verified_login"
        internal const val EXTRA_HANDOFF_URL = "nims_handoff_url"

        private const val NIMS_LOGIN_URL = "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action"
        private const val DESKTOP_CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val MAX_LOGIN_NAVIGATION_ATTEMPTS = 24
        private const val MAX_POST_LOGIN_WAIT_ATTEMPTS = 30
        private const val LOGIN_NAVIGATION_COOLDOWN_MS = 1_500L

        internal val LOGIN_GATE_SCRIPT: String = """
            (function(){
              function collect(doc,out,seen,depth){
                if(!doc||depth>7||seen.indexOf(doc)>=0)return;
                seen.push(doc);out.push(doc);
                let frames=[];try{frames=doc.querySelectorAll('iframe,frame');}catch(e){}
                for(const frame of frames){try{const child=frame.contentDocument||(frame.contentWindow&&frame.contentWindow.document);if(child)collect(child,out,seen,depth+1);}catch(e){}}
              }
              function visible(el){
                if(!el||el.disabled)return false;
                try{
                  const style=el.ownerDocument.defaultView.getComputedStyle(el);
                  if(style.display==='none'||style.visibility==='hidden'||Number(style.opacity)===0)return false;
                  const rect=el.getBoundingClientRect();
                  return rect.width>0&&rect.height>0;
                }catch(e){return true;}
              }
              function text(el){return String((el&&((el.innerText||el.textContent||el.value||el.title)||(el.getAttribute&&el.getAttribute('aria-label'))))||'').replace(/\s+/g,' ').trim();}
              function actions(doc){try{return [...doc.querySelectorAll('a,button,input[type=button],input[type=submit],[role=button]')];}catch(e){return [];}}
              const docs=[];collect(document,docs,[],0);
              let password=false,username=false,captcha=false,loginAction=false,crReady=false,reportRows=0,logout=false,publicSignals=false,sessionExpired=false;
              for(const d of docs){
                let body='';try{body=String((d.body&&d.body.innerText)||'');}catch(e){}
                let inputs=[];try{inputs=[...d.querySelectorAll('input,textarea')];}catch(e){}
                const pageActions=actions(d);
                if(inputs.some(x=>visible(x)&&String(x.type||'').toLowerCase()==='password'))password=true;
                if(inputs.some(x=>visible(x)&&/loginname|username|userid|user\s*id|user_id/i.test((x.id||'')+' '+(x.name||'')+' '+(x.placeholder||'')+' '+(x.title||''))))username=true;
                if(inputs.some(x=>visible(x)&&/captcha|verification\s*code|security\s*code/i.test((x.id||'')+' '+(x.name||'')+' '+(x.placeholder||'')+' '+(x.title||''))))captcha=true;
                try{if([...d.querySelectorAll('img,canvas')].some(x=>visible(x)&&/captcha|verification/i.test((x.alt||'')+' '+(x.id||'')+' '+(x.className||''))))captcha=true;}catch(e){}
                if(pageActions.some(x=>visible(x)&&/^(?:login|log\s*in|sign\s*in|submit)$/i.test(text(x))))loginAction=true;
                if(inputs.some(x=>visible(x)&&!x.readOnly&&String(x.type||'').toLowerCase()!=='hidden'&&/\bcr\s*(?:no|number)?\b|crno|crnum|patcrno|cr_number/i.test((x.id||'')+' '+(x.name||'')+' '+(x.placeholder||'')+' '+(x.title||''))))crReady=true;
                reportRows=Math.max(reportRows,pageActions.filter(x=>/view\s*report/i.test(text(x))).length);
                if(pageActions.some(x=>visible(x)&&/^(?:logout|log\s*out|sign\s*out)$/i.test(text(x))))logout=true;
                if(/\bstatistics\b/i.test(body)&&/recommended\s+to\s+use\s+firefox|designed\s+and\s+developed\s+by\s+c-?dac/i.test(body))publicSignals=true;
                if(/session\s*(?:has\s*)?expired|invalid\s*session|please\s*login\s*again|session\s*timeout|timed\s*out/i.test(body))sessionExpired=true;
              }
              const loginVisible=password||(username&&(captcha||loginAction));
              return JSON.stringify({
                loginVisible:loginVisible,
                publicLanding:publicSignals&&!loginVisible&&!crReady&&reportRows===0&&!logout,
                crReady:crReady,
                reportRows:reportRows,
                logoutVisible:logout,
                sessionExpired:sessionExpired,
                documentCount:docs.length
              });
            })();
        """.trimIndent()

        internal val OPEN_LOGIN_NAVIGATION_SCRIPT: String = """
            (function(){
              function visible(el){
                if(!el||el.disabled)return false;
                try{const s=el.ownerDocument.defaultView.getComputedStyle(el);const r=el.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&Number(s.opacity)!==0&&r.width>0&&r.height>0;}catch(e){return true;}
              }
              function text(el){return String((el&&((el.innerText||el.textContent||el.value||el.title)||(el.getAttribute&&el.getAttribute('aria-label'))))||'').replace(/\s+/g,' ').trim();}
              const actions=[...document.querySelectorAll('a,button,input[type=button],input[type=submit],[role=button]')];
              const login=/^(?:nims\s+)?(?:user\s+|employee\s+|hospital\s+)?(?:login|log\s*in|sign\s*in)$/i;
              const direct=actions.find(x=>visible(x)&&login.test(text(x))&&!/logout|sign\s*out/i.test(text(x)));
              if(direct){try{direct.click();return 'login_clicked';}catch(e){}}
              const togglers=[...document.querySelectorAll('.navbar-toggler,.menu-toggle,[data-bs-toggle=collapse],[data-toggle=collapse],button[aria-label*=menu i],button[title*=menu i]')];
              const toggle=togglers.find(visible)||actions.find(x=>visible(x)&&/^(?:menu|navigation|☰)$/i.test(text(x)));
              if(toggle){try{toggle.click();setTimeout(function(){const again=[...document.querySelectorAll('a,button,input[type=button],input[type=submit],[role=button]')].find(x=>visible(x)&&login.test(text(x)));if(again)again.click();},300);return 'menu_opened';}catch(e){}}
              return 'none';
            })();
        """.trimIndent()
    }
}

internal object LoginGatePolicy {
    fun canEnterResults(
        loginFormSeen: Boolean,
        loginVisible: Boolean,
        publicLanding: Boolean,
        sessionExpired: Boolean,
        leftLoginRoute: Boolean,
        crReady: Boolean,
        reportRows: Int,
        logoutVisible: Boolean
    ): Boolean = loginFormSeen &&
        !loginVisible &&
        !publicLanding &&
        !sessionExpired &&
        (leftLoginRoute || crReady || reportRows > 0 || logoutVisible)
}

@Composable
private fun LoginGateScreen(
    webView: WebView,
    status: String,
    onContinue: () -> Unit,
    onLogoutOtherSessions: () -> Unit,
    onReloadLogin: () -> Unit,
    onCopyLogs: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("NIMS login", style = MaterialTheme.typography.headlineMedium)
        Text("Enter user ID, password and captcha. Credentials are not stored by this app.")
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onContinue, modifier = Modifier.weight(1f)) { Text("Check login") }
            OutlinedButton(onClick = onReloadLogin, modifier = Modifier.weight(1f)) { Text("Reload login") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onLogoutOtherSessions, modifier = Modifier.weight(1f)) { Text("Logout other sessions") }
            OutlinedButton(onClick = onCopyLogs, modifier = Modifier.weight(1f)) { Text("Copy logs") }
        }
    }
}
