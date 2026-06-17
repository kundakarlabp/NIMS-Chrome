package org.kundakarlab.nimsfastsummarymobile.security

class SafeLogBuffer(private val maxEntries: Int = 150, private val maxEntryLength: Int = 240) {
    private val entries = ArrayDeque<String>()
    fun add(message: String): String {
        val safe = LogSanitizer.message(message, maxEntryLength)
        entries += safe
        while (entries.size > maxEntries) entries.removeFirst()
        return entries.joinToString("\n")
    }
}
