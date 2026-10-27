package org.kundakarlab.nimsresults

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Privacy-safe last-duration data; no URLs, names, patient IDs or clinical text. */
object PdfPerformanceTrace {
    private val last = ConcurrentHashMap<String, AtomicLong>()
    private val allowed = setOf("download_ms", "identity_and_extract_ms",
        "render_page_ms", "full_pdf_open_ms")
    fun record(key: String, elapsedMs: Long) {
        if (key in allowed) last.getOrPut(key) { AtomicLong(0) }
            .set(elapsedMs.coerceIn(0L, 600000L))
    }
    fun describe(): String = last.toSortedMap()
        .entries.joinToString(",") { it.key + "=" + it.value.get() }
}
