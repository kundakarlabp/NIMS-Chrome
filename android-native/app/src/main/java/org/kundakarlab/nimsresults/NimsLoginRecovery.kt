package org.kundakarlab.nimsresults

import java.net.URI

/**
 * Hospital-scoped HTTPS failover after a main-frame DNS error only.
 * Never follow arbitrary URLs, IP addresses, non-HTTPS links or login
 * redirects to unrelated domains. This does not circumvent hospital access.
 */
object NimsLoginRecovery {
    const val HOST_LOOKUP_ERROR = -2 // WebViewClient.ERROR_HOST_LOOKUP
    private val loginPath = "/AHIMSG5/hissso/loginLogin.action"

    fun alternateLoginUrl(failedUrl: String, errorCode: Int, alreadyTried: Boolean): String? {
        if (errorCode != HOST_LOOKUP_ERROR || alreadyTried) return null
        val uri = runCatching { URI(failedUrl) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.port != -1 ||
            uri.path != loginPath || uri.query != null || uri.fragment != null) return null
        return when (uri.host?.lowercase()) {
            "www.nimsts.edu.in" -> NimsUrls.APEX_ORIGIN + loginPath
            "nimsts.edu.in" -> NimsUrls.WWW_ORIGIN + loginPath
            else -> null
        }
    }

    fun errorDescription(code: Int): String = when (code) {
        HOST_LOOKUP_ERROR -> "NIMS hospital hostname could not be resolved (DNS)."
        -6 -> "Could not establish a connection to the NIMS hospital server."
        -8 -> "The NIMS hospital connection timed out."
        -11 -> "NIMS could not complete a secure authentication handshake."
        else -> "NIMS hospital page could not be loaded (WebView error $code)."
    }
}
