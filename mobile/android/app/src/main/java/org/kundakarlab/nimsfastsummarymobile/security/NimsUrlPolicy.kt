package org.kundakarlab.nimsfastsummarymobile.security

import android.net.Uri
import java.net.URI

object NimsUrlPolicy {
    private val allowedHosts = setOf("nimsts.edu.in", "www.nimsts.edu.in")
    private val allowedPathPrefixes = listOf("/AHIMSG5/", "/HISInvestigationG5/")
    fun isAllowed(uri: Uri): Boolean {
        return isAllowedParts(uri.scheme, uri.host, uri.userInfo, uri.path.orEmpty())
    }
    fun isAllowedUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return isAllowedParts(uri.scheme, uri.host, uri.rawUserInfo, uri.path.orEmpty())
    }

    fun safeSourceForHelper(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return ""
        if (!isAllowedParts(uri.scheme, uri.host, uri.rawUserInfo, uri.path.orEmpty())) return ""
        return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), -1, uri.path, null, null).toString()
    }

    private fun isAllowedParts(scheme: String?, host: String?, userInfo: String?, path: String): Boolean {
        if (!scheme.equals("https", ignoreCase = true)) return false
        if (userInfo != null) return false
        if (host?.lowercase() !in allowedHosts) return false
        return allowedPathPrefixes.any { path.startsWith(it) }
    }
}
