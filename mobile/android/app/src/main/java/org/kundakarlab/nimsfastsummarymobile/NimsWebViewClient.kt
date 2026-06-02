package org.kundakarlab.nimsfastsummarymobile

import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

class NimsWebViewClient(
    private val onPageChanged: (String) -> Unit = {},
    private val onMainFrameError: (String) -> Unit = {}
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (NimsReportTemplate.isAllowedNimsUrl(url)) return false
        view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return true
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) {
            onMainFrameError("NIMS page load failed: ${error.description}")
        }
    }

    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
        if (request.isForMainFrame) {
            onMainFrameError("NIMS page returned HTTP ${errorResponse.statusCode}: ${SafeUrl.stripQuery(request.url.toString())}")
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        onMainFrameError("NIMS SSL error. The app did not bypass the certificate warning; check network or hospital certificate setup.")
    }

    override fun onPageFinished(view: WebView, url: String) {
        onPageChanged(SafeUrl.stripQuery(url))
    }
}
