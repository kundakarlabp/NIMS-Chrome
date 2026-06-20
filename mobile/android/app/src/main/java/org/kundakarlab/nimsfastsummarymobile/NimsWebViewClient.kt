package org.kundakarlab.nimsfastsummarymobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.kundakarlab.nimsfastsummarymobile.security.NimsUrlPolicy
import org.kundakarlab.nimsfastsummarymobile.security.UrlClassification

class NimsWebViewClient(
    private val onPageChanged: (String) -> Unit = {},
    private val onBlockedInternalNavigation: (String) -> Unit = {}
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        when (NimsUrlPolicy.classify(request.url)) {
            UrlClassification.ALLOWED_NIMS -> return false
            UrlClassification.BLOCKED_NIMS -> {
                onBlockedInternalNavigation("blocked_internal_nims_path")
                return true
            }
            UrlClassification.EXTERNAL_HTTPS -> {
                if (!request.isForMainFrame) return true
                try {
                    view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                } catch (_: ActivityNotFoundException) {
                    // No raw URL logging.
                }
                return true
            }
            UrlClassification.BLOCKED_SCHEME -> return true
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        onPageChanged(SafeUrl.stripQuery(url))
    }
}
