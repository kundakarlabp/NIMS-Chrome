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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manual-login launcher gate.
 *
 * Credentials and captcha are entered only into NIMS. The app never reads or
 * stores them. NIMS's login form is allowed to submit normally; once the form
 * disappears, the app deliberately verifies the resulting cookie session by
 * opening the protected CR-results endpoint. That verification is user-driven
 * (Continue to results) and also starts automatically after a short settle
 * period, avoiding both the old premature redirect and the newer infinite wait.
 */
class LoginGateActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var secureSettings: SecureSettings
    private val handler = Handler(Looper.getMainLooper())
    private val diagnostics = StringBuilder()

    private var status by mutableStateOf("Opening NIMS login…")
    private var loginFormSeen = false
    private var loginFormGoneAt = 0L
    private var protectedVerificationStarted = false
    private var launched = false
    private var lastFinishedUrl = ""
    private var lastLoginNavigationAt = 0L
    private var lastAutofillUrl = ""
    private var savedUsername by mutableStateOf("")
    private var credentialUsernameInput by mutableStateOf("")
    private var credentialPasswordInput by mutableStateOf("")
    private var pendingCr = ""
    private var relayJobId = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secureSettings = SecureSettings(this)
        savedUsername = secureSettings.nimsUsername()
        credentialUsernameInput = savedUsername
        pendingCr = intent.getStringExtra(EXTRA_PENDING_CR).orEmpty().filter(Char::isDigit).take(20)
        relayJobId = intent.getStringExtra(EXTRA_RELAY_JOB_ID).orEmpty()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.userAgentString = DESKTOP_CHROME_UA
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

                override fun onPageFinished(view: WebView, url: String) {
                    lastFinishedUrl = url
                    log("PAGE ${safeStage(url)}")
                    handler.postDelayed({ inspectAndAdvance(0) }, 220L)
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
                    savedUsername = savedUsername,
                    credentialUsername = credentialUsernameInput,
                    credentialPassword = credentialPasswordInput,
                    onCredentialUsernameChange = { credentialUsernameInput = it },
                    onCredentialPasswordChange = { credentialPasswordInput = it },
                    onSaveCredentials = ::saveCredentials,
                    onClearCredentials = ::clearCredentials,
                    onAutofill = ::autofillSavedCredentials,
                    onAuthenticate = ::authenticateLogin,
                    onContinue = ::verifyLogin,
                    onLogoutOtherSessions = ::logoutOtherSessions,
                    onReloadLogin = ::beginFreshLogin,
                    onCopyLogs = ::copyLogs,
                    onOpenBridge = { startActivity(Intent(this, NimsRelayPairingActivity::class.java)) }
                )
            }
        }

        log("BUILD versionName=${BuildConfig.VERSION_NAME} versionCode=${BuildConfig.VERSION_CODE}")
        resumeExistingSession()
    }

    private fun resumeExistingSession() {
        if (launched) return
        status = "Checking the existing NIMS session…"
        protectedVerificationStarted = false
        lastFinishedUrl = ""
        lastAutofillUrl = ""
        webView.stopLoading()
        webView.loadUrl(CR_RESULTS_URL)
    }

    private fun beginFreshLogin() {
        if (launched) return
        loginFormSeen = false
        loginFormGoneAt = 0L
        protectedVerificationStarted = false
        lastFinishedUrl = ""
        lastLoginNavigationAt = 0L
        lastAutofillUrl = ""
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

    private fun saveCredentials() {
        val username = credentialUsernameInput.trim()
        val password = credentialPasswordInput
        if (username.isBlank() || password.isBlank()) {
            status = "Enter the NIMS user ID and password before saving."
            return
        }
        runCatching { secureSettings.saveNimsCredentials(username, password) }
            .onSuccess {
                savedUsername = username
                credentialUsernameInput = username
                credentialPasswordInput = ""
                lastAutofillUrl = ""
                status = "Login saved securely on this phone. Enter the CAPTCHA, then tap Authenticate."
                autofillSavedCredentials()
            }
            .onFailure {
                status = "Could not save the login securely on this phone."
            }
    }

    private fun clearCredentials() {
        secureSettings.clearNimsCredentials()
        savedUsername = ""
        credentialUsernameInput = ""
        credentialPasswordInput = ""
        lastAutofillUrl = ""
        status = "Saved NIMS login removed from this phone."
    }

    private fun autofillSavedCredentials() {
        val username = secureSettings.nimsUsername()
        val password = secureSettings.nimsPassword()
        if (username.isBlank() || password.isBlank()) {
            status = "No saved NIMS login. Enter it once below and tap Save login."
            return
        }
        webView.evaluateJavascript(NimsCredentialAutofill.fillScript(username, password)) { raw ->
            val result = decodeObject(raw)
            when {
                result.optBoolean("ok") && result.optBoolean("captchaFound") ->
                    status = "ID and password filled. Enter the fresh CAPTCHA, then tap Authenticate."
                result.optBoolean("ok") ->
                    status = "ID and password filled. Complete any NIMS verification shown, then tap Authenticate."
                else -> status = "Waiting for the NIMS login form…"
            }
        }
    }

    private fun authenticateLogin() {
        if (launched) return
        webView.evaluateJavascript(NimsCredentialAutofill.authenticateScript) { raw ->
            val result = decodeObject(raw)
            when (result.optString("reason")) {
                "captcha_required" -> status = "Enter the fresh CAPTCHA shown by NIMS, then tap Authenticate."
                "clicked_login", "submitted_form" -> {
                    loginFormSeen = true
                    status = "Submitting NIMS login…"
                    handler.postDelayed({ inspectAndAdvance(0, userRequestedVerification = true) }, 650L)
                }
                "login_form_not_found" -> verifyLogin()
                else -> status = "The NIMS login form is not ready. Reload login if needed."
            }
        }
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
            val protectedRoute = isCrResultsRoute(lastFinishedUrl)

            log(
                "AUTH login=$loginVisible public=$publicLanding leftLogin=$leftLoginRoute " +
                    "protected=$protectedRoute verify=$protectedVerificationStarted " +
                    "cr=$crReady rows=$reportRows logout=$logoutVisible expired=$sessionExpired"
            )

            if (!loginVisible && !sessionExpired && protectedRoute && (crReady || reportRows > 0 || logoutVisible)) {
                log("AUTH reused existing protected session")
                openResultsWorkflow()
                return@evaluateJavascript
            }

            if (sessionExpired) {
                protectedVerificationStarted = false
                loginFormGoneAt = 0L
                status = "NIMS session expired. Reload login and sign in again."
                return@evaluateJavascript
            }

            if (loginVisible) {
                loginFormSeen = true
                loginFormGoneAt = 0L
                if (protectedVerificationStarted) protectedVerificationStarted = false
                if (secureSettings.hasNimsCredentials()) {
                    if (lastAutofillUrl != lastFinishedUrl) {
                        lastAutofillUrl = lastFinishedUrl
                        autofillSavedCredentials()
                    } else {
                        status = "Enter the fresh CAPTCHA, then tap Authenticate."
                    }
                } else {
                    status = "Enter the NIMS user ID/password once below, save them securely, then enter the CAPTCHA."
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

            if (protectedVerificationStarted) {
                if (
                    LoginGatePolicy.canAcceptProtectedVerification(
                        loginFormSeen = loginFormSeen,
                        loginVisible = loginVisible,
                        sessionExpired = sessionExpired,
                        verificationStarted = protectedVerificationStarted,
                        protectedRoute = protectedRoute
                    ) && attempt >= PROTECTED_ROUTE_SETTLE_PROBES
                ) {
                    log("AUTH protected CR route accepted")
                    openResultsWorkflow()
                    return@evaluateJavascript
                }

                if (attempt < MAX_PROTECTED_VERIFY_ATTEMPTS) {
                    status = "Verifying the NIMS session…"
                    handler.postDelayed(
                        { inspectAndAdvance(attempt + 1, userRequestedVerification = true) },
                        450L
                    )
                } else {
                    protectedVerificationStarted = false
                    status = "NIMS session verification did not complete. Tap Reload login and sign in again."
                }
                return@evaluateJavascript
            }

            if (loginFormSeen) {
                if (loginFormGoneAt == 0L) loginFormGoneAt = SystemClock.elapsedRealtime()
                val settledFor = SystemClock.elapsedRealtime() - loginFormGoneAt
                status = "Login submitted. Finalizing the NIMS session…"

                if (userRequestedVerification || settledFor >= POST_LOGIN_SETTLE_MS) {
                    beginProtectedVerification()
                } else {
                    handler.postDelayed(
                        { inspectAndAdvance(attempt + 1, userRequestedVerification) },
                        350L
                    )
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

    private fun beginProtectedVerification() {
        if (launched || protectedVerificationStarted) return
        protectedVerificationStarted = true
        status = "Verifying the NIMS session…"
        CookieManager.getInstance().flush()
        log("AUTH verify protected CR module")
        webView.loadUrl(CR_RESULTS_URL)
        handler.postDelayed({ inspectAndAdvance(0, userRequestedVerification = true) }, 700L)
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
        CookieManager.getInstance().flush()
        if (relayJobId.isNotBlank()) {
            status = "NIMS login verified. Resuming the encrypted request…"
            log("AUTH verified; resuming relay job")
            NimsRelayService.resumeAfterAuthentication(this, relayJobId)
            finish()
            return
        }
        status = "NIMS login verified. Opening patient results…"
        log("AUTH verified; opening production workflow")
        val handoffUrl = lastFinishedUrl.takeIf(::isAllowedNimsUrl).orEmpty()
        startActivity(
            Intent(this, ProductionWorkflowActivity::class.java)
                .putExtra(EXTRA_VERIFIED_LOGIN, true)
                .putExtra(EXTRA_HANDOFF_URL, handoffUrl)
                .putExtra(EXTRA_PENDING_CR, pendingCr)
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
        diagnostics.append(message.take(280))
    }

    private fun safeStage(url: String): String = when {
        isCrResultsRoute(url) -> "cr_module"
        isLoginRoute(url) -> "login_route"
        isAllowedNimsUrl(url) -> "nims_page"
        else -> "other"
    }

    private fun isCrResultsRoute(url: String): Boolean =
        url.contains("viewcrnowisereportprocess.cnt", ignoreCase = true)

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
        internal const val EXTRA_PENDING_CR = "nims_pending_cr"
        internal const val EXTRA_RELAY_JOB_ID = "nims_relay_job_id"

        private const val NIMS_LOGIN_URL = "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action"
        private const val CR_RESULTS_URL = "https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt"
        private const val DESKTOP_CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val MAX_LOGIN_NAVIGATION_ATTEMPTS = 24
        private const val MAX_PROTECTED_VERIFY_ATTEMPTS = 24
        private const val PROTECTED_ROUTE_SETTLE_PROBES = 2
        private const val POST_LOGIN_SETTLE_MS = 1_500L
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

    fun canAcceptProtectedVerification(
        loginFormSeen: Boolean,
        loginVisible: Boolean,
        sessionExpired: Boolean,
        verificationStarted: Boolean,
        protectedRoute: Boolean
    ): Boolean = loginFormSeen &&
        verificationStarted &&
        protectedRoute &&
        !loginVisible &&
        !sessionExpired
}

@Composable
private fun LoginGateScreen(
    webView: WebView,
    status: String,
    savedUsername: String,
    credentialUsername: String,
    credentialPassword: String,
    onCredentialUsernameChange: (String) -> Unit,
    onCredentialPasswordChange: (String) -> Unit,
    onSaveCredentials: () -> Unit,
    onClearCredentials: () -> Unit,
    onAutofill: () -> Unit,
    onAuthenticate: () -> Unit,
    onContinue: () -> Unit,
    onLogoutOtherSessions: () -> Unit,
    onReloadLogin: () -> Unit,
    onCopyLogs: () -> Unit,
    onOpenBridge: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("NIMS login", style = MaterialTheme.typography.headlineMedium)
        if (savedUsername.isBlank()) {
            Text("Save the NIMS login once on this phone. It is encrypted with Android Keystore and never sent to the dashboard or ChatGPT.")
            OutlinedTextField(
                value = credentialUsername,
                onValueChange = onCredentialUsernameChange,
                label = { Text("NIMS user ID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = credentialPassword,
                onValueChange = onCredentialPasswordChange,
                label = { Text("NIMS password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = onSaveCredentials, modifier = Modifier.fillMaxWidth()) { Text("Save login on this phone") }
        } else {
            Text("Saved login: $savedUsername")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onAutofill, modifier = Modifier.weight(1f)) { Text("Use saved login") }
                OutlinedButton(onClick = onClearCredentials, modifier = Modifier.weight(1f)) { Text("Forget login") }
            }
        }
        Text("CAPTCHA stays manual and is never read or stored by the app.")
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onAuthenticate, modifier = Modifier.weight(1f)) { Text("Authenticate") }
            OutlinedButton(onClick = onContinue, modifier = Modifier.weight(1f)) { Text("Check login") }
        }
        OutlinedButton(onClick = onReloadLogin, modifier = Modifier.fillMaxWidth()) { Text("Reload login") }
        OutlinedButton(onClick = onOpenBridge, modifier = Modifier.fillMaxWidth()) { Text("Dashboard / ChatGPT bridge") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onLogoutOtherSessions, modifier = Modifier.weight(1f)) { Text("Logout other sessions") }
            OutlinedButton(onClick = onCopyLogs, modifier = Modifier.weight(1f)) { Text("Copy logs") }
        }
    }
}
