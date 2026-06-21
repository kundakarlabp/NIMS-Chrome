package org.kundakarlab.nimsfastsummarymobile.security

import android.net.Uri
import java.net.URI

enum class UrlClassification {
    ALLOWED_NIMS,
    BLOCKED_NIMS,
    EXTERNAL_HTTPS,
    BLOCKED_SCHEME,
    BLOCKED_UNSAFE
}

object NimsUrlPolicy {
    private val allowedHosts = setOf("nimsts.edu.in", "www.nimsts.edu.in")
    private val allowedPathPrefixes = listOf(
        "/AHIMSG5/",
        "/HISInvestigationG5/",
        "/HIS/",
        "/hislogin/",
        "/HISUtilities/",
        "/HBIMS/"
    )

    fun isAllowed(uri: Uri): Boolean = classify(uri) == UrlClassification.ALLOWED_NIMS

    fun isAllowedUrl(url: String): Boolean = classifyUrl(url) == UrlClassification.ALLOWED_NIMS

    fun classifyUrl(url: String): UrlClassification {
        val uri = runCatching { URI(url) }.getOrNull() ?: return UrlClassification.BLOCKED_UNSAFE
        return classifyParts(uri.scheme, uri.host, uri.rawUserInfo, uri.rawPath.orEmpty(), uri.port)
    }

    fun isTrustedNimsHost(uri: Uri): Boolean = uri.scheme.equals("https", ignoreCase = true) &&
        uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443) &&
        (uri.host?.lowercase() in allowedHosts)

    fun classify(uri: Uri): UrlClassification = classifyParts(
        uri.scheme,
        uri.host,
        uri.userInfo,
        uri.encodedPath.orEmpty(),
        uri.port
    )

    fun safeSourceForHelper(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return ""
        if (classifyParts(uri.scheme, uri.host, uri.rawUserInfo, uri.rawPath.orEmpty(), uri.port) != UrlClassification.ALLOWED_NIMS) return ""
        return URI("https", null, uri.host.lowercase(), -1, uri.path, null, null).toString()
    }

    private fun classifyParts(scheme: String?, host: String?, userInfo: String?, rawPath: String, port: Int): UrlClassification {
        if (!scheme.equals("https", ignoreCase = true)) return UrlClassification.BLOCKED_SCHEME
        if (userInfo != null) return UrlClassification.BLOCKED_UNSAFE
        val normalizedHost = host?.lowercase() ?: return UrlClassification.BLOCKED_UNSAFE
        val trustedHost = normalizedHost in allowedHosts
        if (port != -1 && port != 443) return if (trustedHost) UrlClassification.BLOCKED_NIMS else UrlClassification.BLOCKED_UNSAFE
        if (!trustedHost) return UrlClassification.EXTERNAL_HTTPS
        if (isMalformedPath(rawPath)) return UrlClassification.BLOCKED_NIMS
        return if (allowedPathPrefixes.any { rawPath.startsWith(it) }) UrlClassification.ALLOWED_NIMS else UrlClassification.BLOCKED_NIMS
    }

    private fun isMalformedPath(rawPath: String): Boolean {
        if (rawPath.isBlank() || rawPath.contains('\\') || rawPath.contains("..") || rawPath.contains("//")) return true
        val lower = rawPath.lowercase()
        return listOf("%2e", "%2f", "%5c").any { lower.contains(it) }
    }
}
