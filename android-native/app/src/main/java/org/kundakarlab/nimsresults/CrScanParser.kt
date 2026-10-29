package org.kundakarlab.nimsresults

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Accept only an unambiguous 15-digit NIMS CR; never log scanned payloads.
 * A scanner result is entered into the CR field for user confirmation,
 * never used to trigger a request automatically. */
object CrScanParser {
    private val exact = Regex("^\\d{15}$")
    private val labeled = Regex("^(?i:CR\\s*(?:NO\\.?|NUMBER)?\\s*[:#=-])\\s*(\\d{15})$")
    private val allowedHosts = setOf("nimsts.edu.in","www.nimsts.edu.in",
        "nims.edu.in","www.nims.edu.in")

    fun parse(raw: String): String? {
        val text = raw.trim()
        if (exact.matches(text)) return text
        labeled.matchEntire(text)?.let { return it.groupValues[1] }
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https" ||
            uri.host?.lowercase() !in allowedHosts) return null
        val values = uri.rawQuery.orEmpty().split("&").mapNotNull { item ->
            val pair = item.split("=", limit = 2)
            if (pair.size != 2) return@mapNotNull null
            val key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8)
                .lowercase().replace(Regex("[^a-z]"), "")
            if (key !in setOf("cr","crno","crnumber","patientcr")) return@mapNotNull null
            URLDecoder.decode(pair[1], StandardCharsets.UTF_8)
        }.filter(exact::matches).distinct()
        return values.singleOrNull()
    }
}
