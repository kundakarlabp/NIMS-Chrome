package org.kundakarlab.nimsfastsummarymobile

import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class NimsWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        if (NimsReportTemplate.isAllowedNimsUrl(url)) return false
        view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return true
    }
}
