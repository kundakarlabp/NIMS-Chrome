package org.kundakarlab.nimsfastsummarymobile

import java.net.URI

object SafeUrl {
    fun hostPath(url: String): String {
        return try {
            val uri = URI(url)
            "${uri.host ?: ""}${uri.path ?: ""}"
        } catch (_: Exception) {
            ""
        }
    }

    fun stripQuery(url: String): String = hostPath(url)
}
