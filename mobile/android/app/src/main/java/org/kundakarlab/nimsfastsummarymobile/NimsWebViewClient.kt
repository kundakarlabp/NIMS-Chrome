package org.kundakarlab.nimsfastsummarymobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.kundakarlab.nimsfastsummarymobile.security.NimsUrlPolicy

class NimsWebViewClient(private val onPageChanged: (String) -> Unit = {}) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        val scheme = uri.scheme?.lowercase().orEmpty()
        if (NimsUrlPolicy.isAllowed(uri)) return false
        if (scheme == "https") {
            try {
                view.context.startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (_: ActivityNotFoundException) {
                // No raw URL logging.
            }
            return true
        }
        return true
    }

    override fun onPageFinished(view: WebView, url: String) {
        onPageChanged(SafeUrl.stripQuery(url))
    }
}
