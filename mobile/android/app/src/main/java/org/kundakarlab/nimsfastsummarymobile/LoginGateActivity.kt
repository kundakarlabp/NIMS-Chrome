package org.kundakarlab.nimsfastsummarymobile

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * Deterministic launcher gate.
 *
 * Every cold app launch starts with a clean NIMS WebView session and requires
 * the real user-ID/password/captcha form to be observed before the native
 * results workflow can open. Credentials are never read or stored by the app.
 */
class LoginGateActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())
    private val diagnostics = StringBuilder()

    private var status by mutableStateOf("Opening NIMS login…")
    private var loginFormSeen = false
    private var protectedVerificationStarted = false
    private var launched = false

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
                    log("PAGE ${safeStage(url)}")
                    handler.postDelayed({ inspectAndAdvance(0) }, 150L)
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
        protectedVerificationStarted = false
        status = "Opening the NIMS login page…"
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
        status = "Verifying NIMS login…"
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
            val navigationAction = probe.optString("navigationAction")

            log(
                "AUTH login=$loginVisible public=$publicLanding cr=$crReady " +
                    "rows=$reportRows logout=$logoutVisible action=$navigationAction"
            )

            if (loginVisible) {
                loginFormSeen = true
                protectedVerificationStarted = false
                status = if (userRequestedVerification) {
                    "Submit the NIMS user ID, password and captcha on the page, then continue."
                } else {
                    "Enter user ID, password and captcha, then submit the NIMS form."
                }
                return@evaluateJavascript
            }

            if (LoginGatePolicy.canEnterResults(loginFormSeen, crReady, reportRows, logoutVisible)) {
                openResultsWorkflow()
                return@evaluateJavascript
            }

            if (loginFormSeen && !protectedVerificationStarted) {
                protectedVerificationStarted = true
                status = "Login submitted. Verifying the NIMS session…"
                handler.postDelayed({ webView.loadUrl(CR_RESULTS_URL) }, 250L)
                return@evaluateJavascript
            }

            if (attempt < MAX_LOGIN_NAVIGATION_ATTEMPTS) {
                status = when {
                    publicLanding || navigationAction == "menu_opened" || navigationAction == "login_clicked" ->
                        "Opening the NIMS credential and captcha form…"
                    loginFormSeen -> "Waiting for NIMS session verification…"
                    else -> "Preparing the NIMS login page…"
                }
                handler.postDelayed(
                    { inspectAndAdvance(attempt + 1, userRequestedVerification) },
                    if (attempt < 4) 350L else 650L
                )
            } else {
                status = if (loginFormSeen) {
                    "Login could not be verified. Check the captcha or use Reload login."
                } else {
                    "The NIMS login form did not open. Tap Reload login."
                }
            }
        }
    }

    private fun openResultsWorkflow() {
        if (launched) return
        launched = true
        status = "NIMS login verified. Opening patient results…"
        log("AUTH verified; opening production workflow")
        startActivity(Intent(this, ProductionWorkflowActivity::class.java))
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
        diagnostics.append(message.take(240))
    }

    private fun safeStage(url: String): String = when {
        url.contains("viewcrnowisereportprocess", ignoreCase = true) -> "cr_module"
        url.contains("login", ignoreCase = true) -> "login_route"
        url.contains("nimsts.edu.in", ignoreCase = true) -> "nims_page"
        else -> "other"
    }

    private fun decodeObject(raw: String): JSONObject = runCatching {
        JSONObject(JSONArray("[$raw]").getString(0))
    }.getOrDefault(JSONObject())

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!launched) runCatching { webView.destroy() }
        super.onDestroy()
    }

    companion object {
        private const val NIMS_LOGIN_URL = "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action"
        private const val CR_RESULTS_URL = "https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt"
        private const val DESKTOP_CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val MAX_LOGIN_NAVIGATION_ATTEMPTS = 24

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
              let password=false,username=false,captcha=false,loginAction=false,crReady=false,reportRows=0,logout=false,publicSignals=false;
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
              }
              const loginVisible=password||(username&&(captcha||loginAction));
              let navigationAction='none';
              if(!loginVisible&&!crReady&&reportRows===0&&!logout){
                const loginPattern=/^(?:nims\s+)?(?:user\s+|employee\s+|hospital\s+)?(?:login|log\s*in|sign\s*in)$/i;
                for(const d of docs){
                  const direct=actions(d).find(x=>visible(x)&&loginPattern.test(text(x))&&!/logout|sign\s*out/i.test(text(x)));
                  if(direct){try{direct.click();navigationAction='login_clicked';break;}catch(e){}}
                }
                if(navigationAction==='none'){
                  for(const d of docs){
                    let togglers=[];try{togglers=[...d.querySelectorAll('.navbar-toggler,.menu-toggle,[data-bs-toggle=collapse],[data-toggle=collapse],button[aria-label*=menu i],button[title*=menu i]')];}catch(e){}
                    const toggle=togglers.find(visible)||actions(d).find(x=>visible(x)&&/^(?:menu|navigation|☰)$/i.test(text(x)));
                    if(toggle){try{toggle.click();navigationAction='menu_opened';break;}catch(e){}}
                  }
                }
              }
              return JSON.stringify({
                loginVisible:loginVisible,
                publicLanding:publicSignals&&!loginVisible&&!crReady&&reportRows===0&&!logout,
                crReady:crReady,
                reportRows:reportRows,
                logoutVisible:logout,
                navigationAction:navigationAction,
                documentCount:docs.length
              });
            })();
        """.trimIndent()
    }
}

internal object LoginGatePolicy {
    fun canEnterResults(
        loginFormSeen: Boolean,
        crReady: Boolean,
        reportRows: Int,
        logoutVisible: Boolean
    ): Boolean = loginFormSeen && (crReady || reportRows > 0 || logoutVisible)
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
            Button(onClick = onContinue, modifier = Modifier.weight(1f)) { Text("Continue") }
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
