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
 * Installs a small passive all-frame observer at document start.
 *
 * Heavy report utilities are deliberately not injected while the clinician is
 * logging in or navigating the legacy portal. MainActivity loads the canonical
 * report core on demand only after the genuine result list has been detected.
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

        val observer = readAsset(webView.context.assets, "nimsPassiveObserver.js")
            ?: return NimsRuntimeInstallResult(false, error = "Missing passive observer asset.")

        return runCatching {
            WebViewCompat.addWebMessageListener(webView, "nimsAndroidBridge", allowedOrigins) { _, message, _, _, _ ->
                message.data?.let(onMessage)
            }
            val handler = WebViewCompat.addDocumentStartJavaScript(
                webView,
                buildPayload(observer),
                allowedOrigins
            )
            onLog("Lightweight NIMS frame observer registered")
            NimsRuntimeInstallResult(true, handler)
        }.getOrElse { error ->
            NimsRuntimeInstallResult(false, error = "Runtime registration failed: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    internal fun buildPayload(observer: String): String = buildString {
        appendLine("(function(w){")
        appendLine("try{")
        appendLine("var h=String(w.location&&w.location.hostname||'');")
        appendLine("if(!/^(www\\.)?nimsts\\.edu\\.in$/i.test(h))return;")
        appendLine("}catch(e){return;}")
        appendLine("try{")
        appendLine(observer)
        appendLine("}catch(e){if(w.console&&w.console.error)w.console.error('NIMS passive observer failed',e);}")
        appendLine("})(window);")
    }

    private fun readAsset(assetManager: android.content.res.AssetManager, name: String): String? =
        runCatching { assetManager.open(name).bufferedReader().use { it.readText() } }.getOrNull()
}
