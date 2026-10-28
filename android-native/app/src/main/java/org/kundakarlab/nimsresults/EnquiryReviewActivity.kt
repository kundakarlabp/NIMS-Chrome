package org.kundakarlab.nimsresults

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONTokener
import java.net.URI

/**
 * Read-only capture of a manually searched authenticated CR-wise Enquiry page.
 * The hospital UI performs the normal search; no unverified NIMS POST is made.
 * No JavascriptInterface and no external website is granted access to results.
 */
class EnquiryReviewActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var notice: TextView
    private var rendererGone = false
    private var pendingRead = false
    private val expectedCr: String by lazy { intent.getStringExtra(EXTRA_EXPECTED_CR).orEmpty() }

    companion object {
        const val EXTRA_EXPECTED_CR = "expected_cr"
        const val EXTRA_CAPTURE_JSON = "enquiry_capture"
        const val EXTRA_CAPTURE_CR = "enquiry_capture_cr"
        private const val URL = "https://www.nimsts.edu.in/HISUtilities/dashboard/dashBoardACTION.cnt"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!expectedCr.matches(Regex("""\d{15}"""))) { finish(); return }
        SessionVault(this).restoreToWebView()
        val layout=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val title=TextView(this).apply {
            text="NIMS Enquiry · manual CR-wise verification"
            textSize=18f
            setTextColor(Color.rgb(10,48,76))
            setPadding(18,16,18,10)
        }
        layout.addView(title)
        notice=TextView(this).apply {
            text="In NIMS, open Investigation Enquiry → CR wise. Search the selected CR, " +
                "confirm the displayed patient and order rows, then tap Import visible Enquiry. " +
                "This does not alter hospital records."
            textSize=12f
            setTextColor(Color.rgb(60,75,85))
            setPadding(18,8,18,8)
        }
        layout.addView(notice)
        val actions=LinearLayout(this)
        val importButton=Button(this).apply {
            text="Import visible Enquiry"
            setOnClickListener { capture(import=true) }
        }
        val schemaButton=Button(this).apply {
            text="Copy redacted schema"
            setOnClickListener { capture(import=false) }
        }
        val close=Button(this).apply {
            text="Close"
            setOnClickListener { finish() }
        }
        actions.addView(importButton,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,2f))
        actions.addView(schemaButton,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,2f))
        actions.addView(close,LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
        layout.addView(actions)
        web=WebView(this).apply {
            settings.javaScriptEnabled=true
            settings.domStorageEnabled=true
            settings.allowFileAccess=false
            settings.allowContentAccess=false
            settings.javaScriptCanOpenWindowsAutomatically=false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.userAgentString=NimsUrls.DESKTOP_UA
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web.webViewClient=object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u=request.url
                return u.scheme != "https" || u.host?.lowercase() !in NimsUrls.HOSTS
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest,
                                         error: WebResourceError) {
                if (request.isForMainFrame)
                    notice.text=NimsLoginRecovery.errorDescription(error.errorCode)
            }
            override fun onRenderProcessGone(view: WebView,
                                             detail: RenderProcessGoneDetail): Boolean {
                rendererGone=true
                (view.parent as? ViewGroup)?.removeView(view)
                runCatching { view.destroy() }
                notice.text="NIMS browser renderer stopped. Close and reopen Enquiry."
                return true
            }
        }
        layout.addView(web,LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,0,1f))
        setContentView(layout)
        web.loadUrl(URL)
    }

    private fun capture(import: Boolean) {
        if (pendingRead || rendererGone) return
        val location=runCatching { URI(web.url.orEmpty()) }.getOrNull()
        if (location?.scheme != "https" ||
            location.host?.lowercase() !in NimsUrls.HOSTS ||
            !location.path.orEmpty().startsWith("/HISUtilities/")) {
            notice.text="Open the authenticated NIMS Enquiry screen before importing."
            return
        }
        pendingRead=true
        notice.text="Inspecting the visible NIMS order table locally…"
        // Read same-origin rendered documents only. Never POST a guessed action,
        // load a PDF, copy credentials, or send raw page HTML to an external service.
        val js="""(function(){
            const docs=[document];
            try { for(const f of document.querySelectorAll('iframe,frame')) {
                try {if(f.contentDocument) docs.push(f.contentDocument)} catch(_){}
            }} catch(_){}
            const selected=docs.find(d=>{
                const text=(d.body && d.body.innerText || '').toLowerCase();
                return text.includes('sample') && text.includes('status') &&
                    (text.includes('investigation')||text.includes('test name'));
            }) || document;
            return selected.documentElement.outerHTML;
        })()"""
        web.evaluateJavascript(js) { encoded ->
            pendingRead=false
            val html=runCatching { JSONTokener(encoded).nextValue() as String }.getOrNull()
            if (html.isNullOrBlank() || html.length > 900_000) {
                notice.text="The Enquiry table is unavailable or too large for safe import."
                return@evaluateJavascript
            }
            if (!import) {
                val summary=InvestigationEnquiryParser.schemaSummary(html)
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("NIMS Enquiry schema",summary))
                notice.text="Redacted table schema copied; no patient values were copied."
                return@evaluateJavascript
            }
            runCatching {
                val capture=InvestigationEnquiryParser.parseHtml(html,expectedCr)
                val payload=InvestigationEnquiryParser.toJson(capture)
                require(payload.length < 650_000) { "Enquiry capture exceeds safe transfer size" }
                setResult(RESULT_OK, Intent().putExtra(EXTRA_CAPTURE_JSON,payload)
                    .putExtra(EXTRA_CAPTURE_CR,expectedCr))
                finish()
            }.onFailure {
                notice.text=it.message.orEmpty().take(260) +
                    " If the NIMS table format differs, copy its redacted schema."
            }
        }
    }

    override fun onDestroy() {
        if (::web.isInitialized && !rendererGone) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }
}
