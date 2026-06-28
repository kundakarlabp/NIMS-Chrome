package org.kundakarlab.nimsfastsummarymobile

import java.net.URI

/**
 * Filters WebView load failures down to document navigation failures and the
 * JavaScript dependencies that are required for the NIMS investigation page.
 */
object NimsResourceErrorPolicy {
    private val documentExtensions = setOf("action", "cnt", "jsp", "do")
    private val staticExtensions = setOf(
        "css", "js", "mjs", "map", "json",
        "png", "jpg", "jpeg", "gif", "svg", "webp", "ico", "bmp",
        "woff", "woff2", "ttf", "otf", "eot",
        "mp3", "mp4", "wav", "webm"
    )
    private val criticalScriptNames = listOf(
        "jquery",
        "additional-methods",
        "validate.email"
    )
    private val contextRoots = setOf(
        "/ahimsg5", "/ahimsg5/",
        "/hisinvestigationg5", "/hisinvestigationg5/",
        "/his", "/his/",
        "/hislogin", "/hislogin/",
        "/hbims", "/hbims/"
    )

    fun shouldSurface(url: String, isMainFrame: Boolean): Boolean {
        if (isMainFrame) return true
        val path = normalizedPath(url)
        if (path.isBlank()) return false
        if (criticalScriptNames.any(path::contains)) return true
        val extension = path.substringAfterLast('.', "")
        if (extension in staticExtensions) return false
        if (extension in documentExtensions) return true
        return path in contextRoots
    }

    private fun normalizedPath(url: String): String {
        val parsed = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        val fallback = url.substringBefore('?').substringBefore('#')
        return (parsed.ifBlank { fallback }).lowercase()
    }
}
