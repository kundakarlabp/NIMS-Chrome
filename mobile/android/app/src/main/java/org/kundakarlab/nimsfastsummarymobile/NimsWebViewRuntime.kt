package org.kundakarlab.nimsfastsummarymobile

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

internal data class NimsRuntimeInstallResult(
    val supported: Boolean,
    val scriptHandler: ScriptHandler? = null,
    val error: String = ""
)

internal object NimsWebViewRuntime {
    internal val allowedOrigins = setOf(
        "https://www.nimsts.edu.in",
        "https://nimsts.edu.in"
    )

    fun install(
        webView: WebView,
        onMessage: (String) -> Unit,
        onLog: (String) -> Unit
    ): NimsRuntimeInstallResult {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            return NimsRuntimeInstallResult(false, error = "Update Chrome and Android System WebView: secure message bridge unavailable.")
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return NimsRuntimeInstallResult(false, error = "Update Chrome and Android System WebView: document-start scripts unavailable.")
        }

        val assets = webView.context.assets
        val jquery = readAsset(assets, "jquery-3.7.1.min.js")
            ?: return NimsRuntimeInstallResult(false, error = "Missing jQuery runtime asset.")
        val shim = readAsset(assets, "nimsWebviewShim.js")
            ?: return NimsRuntimeInstallResult(false, error = "Missing WebView compatibility asset.")
        val core = readAsset(assets, "nimsReportCore.js")
            ?: return NimsRuntimeInstallResult(false, error = "Missing report core asset.")
        val utils = readAsset(assets, "contentUtils.js")
            ?: return NimsRuntimeInstallResult(false, error = "Missing report utility asset.")
        val bridge = readAsset(assets, "nimsAndroidFrameBridge.js")
            ?: return NimsRuntimeInstallResult(false, error = "Missing frame bridge asset.")

        return runCatching {
            WebViewCompat.addWebMessageListener(webView, "nimsAndroidBridge", allowedOrigins) { _, message, _, _, _ ->
                message.data?.let(onMessage)
            }
            val handler = WebViewCompat.addDocumentStartJavaScript(
                webView,
                buildPayload(jquery, shim, core, utils, bridge),
                allowedOrigins
            )
            onLog("NIMS document-start runtime registered")
            NimsRuntimeInstallResult(true, handler)
        }.getOrElse { error ->
            NimsRuntimeInstallResult(false, error = "Runtime registration failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    internal fun buildPayload(
        jquery: String,
        shim: String,
        core: String,
        utils: String,
        bridge: String
    ): String = buildString {
        appendLine("(function(w){")
        appendLine("try{")
        appendLine("var h=String(w.location&&w.location.hostname||'');")
        appendLine("var p=String(w.location&&w.location.pathname||'');")
        appendLine("if(!/^(www\\.)?nimsts\\.edu\\.in$/i.test(h))return;")
        appendLine("if(/^\\/HISInvestigationG5\\//i.test(p)&&typeof w.jQuery==='undefined'){")
        appendLine(jquery)
        appendLine("w.__nimsBundledJqueryVersion=w.jQuery&&w.jQuery.fn?String(w.jQuery.fn.jquery||'unknown'):'missing';")
        appendLine("}")
        appendLine("}catch(e){if(w.console&&w.console.error)w.console.error('NIMS jQuery bootstrap failed',e);}")
        appendLine("})(window);")
        appendLine("try{")
        appendLine(shim)
        appendLine("}catch(e){if(window.console&&console.error)console.error('NIMS compatibility shim failed',e);}")
        appendLine("try{")
        appendLine(core)
        appendLine(utils)
        appendLine(bridge)
        appendLine("}catch(e){if(window.console&&console.error)console.error('NIMS report reader injection failed',e);}")
    }

    private fun readAsset(assetManager: android.content.res.AssetManager, name: String): String? =
        runCatching { assetManager.open(name).bufferedReader().use { it.readText() } }.getOrNull()
}
