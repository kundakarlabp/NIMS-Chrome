package org.kundakarlab.nimsfastsummarymobile

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class NimsWebViewClient(private val onPageChanged: (String) -> Unit = {}) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (NimsReportTemplate.isAllowedNimsUrl(url)) return false
        view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return true
    }

    override fun onPageFinished(view: WebView, url: String) {
        onPageChanged(SafeUrl.stripQuery(url))
    }
}
