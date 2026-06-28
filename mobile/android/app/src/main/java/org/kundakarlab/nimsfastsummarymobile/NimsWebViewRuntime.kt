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

/**
 * Installs the passive, all-frame NIMS observer at document start.
 *
 * The runtime deliberately does not inject jQuery, define NIMS globals, wrap
 * portal functions, click menus, submit forms, or navigate. NIMS owns login,
 * navigation, and rendering; Android only observes page state and report rows.
 */
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
        val core = readAsset(assets, "nimsReportCore.js")
            ?: return NimsRuntimeInstallResult(false, error = "Missing report core asset.")
        val utils = readAsset(assets, "contentUtils.js")
            ?: return NimsRuntimeInstallResult(false, error = "Missing report utility asset.")
        val observer = readAsset(assets, "nimsPassiveObserver.js")
            ?: return NimsRuntimeInstallResult(false, error = "Missing passive observer asset.")

        return runCatching {
            WebViewCompat.addWebMessageListener(webView, "nimsAndroidBridge", allowedOrigins) { _, message, _, _, _ ->
                message.data?.let(onMessage)
            }
            val handler = WebViewCompat.addDocumentStartJavaScript(
                webView,
                buildPayload(core, utils, observer),
                allowedOrigins
            )
            onLog("NIMS passive all-frame observer registered")
            NimsRuntimeInstallResult(true, handler)
        }.getOrElse { error ->
            NimsRuntimeInstallResult(false, error = "Runtime registration failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    internal fun buildPayload(
        core: String,
        utils: String,
        observer: String
    ): String = buildString {
        appendLine("(function(w){")
        appendLine("try{")
        appendLine("var h=String(w.location&&w.location.hostname||'');")
        appendLine("if(!/^(www\\.)?nimsts\\.edu\\.in$/i.test(h))return;")
        appendLine("}catch(e){return;}")
        appendLine("try{")
        appendLine(core)
        appendLine(utils)
        appendLine(observer)
        appendLine("}catch(e){if(w.console&&w.console.error)w.console.error('NIMS passive observer injection failed',e);}")
        appendLine("})(window);")
    }

    private fun readAsset(assetManager: android.content.res.AssetManager, name: String): String? =
        runCatching { assetManager.open(name).bufferedReader().use { it.readText() } }.getOrNull()
}
