package org.kundakarlab.nimsfastsummarymobile.security

import android.net.Uri
import java.net.URI

object NimsUrlPolicy {
    private val allowedHosts = setOf("nimsts.edu.in", "www.nimsts.edu.in")
    private val allowedPathPrefixes = listOf("/AHIMSG5/", "/HISInvestigationG5/")

    fun isAllowed(uri: Uri): Boolean = isAllowedParts(uri.scheme, uri.host, uri.userInfo, uri.encodedPath.orEmpty(), uri.port)

    fun isAllowedUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return isAllowedParts(uri.scheme, uri.host, uri.rawUserInfo, uri.rawPath.orEmpty(), uri.port)
    }

    fun safeSourceForHelper(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return ""
        if (!isAllowedParts(uri.scheme, uri.host, uri.rawUserInfo, uri.rawPath.orEmpty(), uri.port)) return ""
        return URI("https", null, uri.host.lowercase(), -1, uri.path, null, null).toString()
    }

    private fun isAllowedParts(scheme: String?, host: String?, userInfo: String?, rawPath: String, port: Int): Boolean {
        if (!scheme.equals("https", ignoreCase = true)) return false
        if (userInfo != null) return false
        if (port != -1 && port != 443) return false
        val normalizedHost = host?.lowercase() ?: return false
        if (normalizedHost !in allowedHosts) return false
        if (isMalformedPath(rawPath)) return false
        return allowedPathPrefixes.any { rawPath.startsWith(it) }
    }

    private fun isMalformedPath(rawPath: String): Boolean {
        if (rawPath.isBlank() || rawPath.contains('\\') || rawPath.contains("..") || rawPath.contains("//")) return true
        val lower = rawPath.lowercase()
        return listOf("%2e", "%2f", "%5c").any { lower.contains(it) }
    }
}
