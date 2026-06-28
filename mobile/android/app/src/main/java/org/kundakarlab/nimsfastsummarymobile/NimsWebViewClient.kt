package org.kundakarlab.nimsfastsummarymobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.kundakarlab.nimsfastsummarymobile.security.NimsUrlPolicy
import org.kundakarlab.nimsfastsummarymobile.security.UrlClassification

class NimsWebViewClient(
    private val onPageChanged: (String) -> Unit = {},
    private val onBlockedInternalNavigation: (String) -> Unit = {},
    private val onResourceError: (String) -> Unit = {}
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val requestedUrl = request.url.toString()

        // NIMS is a legacy framed application and some menu anchors use
        // javascript: pseudo-links. They execute in the already trusted NIMS
        // document and must not be mistaken for an external navigation.
        if (NimsUrlPolicy.isTrustedLegacyPageScript(view.url.orEmpty(), requestedUrl)) {
            return false
        }

        when (NimsUrlPolicy.classify(request.url)) {
            UrlClassification.ALLOWED_NIMS -> return false
            UrlClassification.BLOCKED_NIMS -> {
                val frame = if (request.isForMainFrame) "main" else "frame"
                onResourceError("NIMS URL POLICY($frame): ${SafeUrl.stripQuery(requestedUrl)}")
                onBlockedInternalNavigation("blocked_internal_nims_path")
                return true
            }
            UrlClassification.EXTERNAL_HTTPS -> {
                if (!request.isForMainFrame || !request.hasGesture()) return true
                return try {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    true
                } catch (_: ActivityNotFoundException) {
                    true
                }
            }
            UrlClassification.BLOCKED_SCHEME,
            UrlClassification.BLOCKED_UNSAFE -> return true
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        val url = request.url?.toString().orEmpty()
        if (NimsResourceErrorPolicy.shouldSurface(url, request.isForMainFrame)) {
            val frame = if (request.isForMainFrame) "main" else "frame"
            onResourceError("NET ERROR($frame ${error.errorCode}): ${SafeUrl.stripQuery(url)} — ${error.description}")
        }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        val url = request.url?.toString().orEmpty()
        if (NimsResourceErrorPolicy.shouldSurface(url, request.isForMainFrame)) {
            val frame = if (request.isForMainFrame) "main" else "frame"
            onResourceError("HTTP ERROR($frame ${errorResponse.statusCode}): ${SafeUrl.stripQuery(url)}")
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        onPageChanged(SafeUrl.stripQuery(url))
    }
}
