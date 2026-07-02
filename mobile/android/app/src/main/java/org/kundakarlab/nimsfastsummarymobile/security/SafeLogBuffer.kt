package org.kundakarlab.nimsfastsummarymobile.security

// Per-entry length was 240 chars and the UI additionally showed only the last
// 1200 chars of the whole buffer -- between the two, the actual crash/error
// text (which is often the LAST and longest part of a log line, e.g. a JS
// exception message) kept getting chopped before a person reading the
// on-screen panel or a screenshot of it could ever see the part that matters.
// installErrorCapture's window.onerror chain (nimsReportCore.js) exists
// specifically to capture full, untruncated error text -- there is no point
// having that if this buffer just re-truncates it on the way to the screen.
// Raised generously; LogSanitizer.message still redacts cookies/API keys/CR
// numbers regardless of length, so this is not a privacy tradeoff.
class SafeLogBuffer(private val maxEntries: Int = 400, private val maxEntryLength: Int = 4000) {
    private val entries = ArrayDeque<String>()
    fun add(message: String): String {
        val safe = LogSanitizer.message(message, maxEntryLength)
        entries += safe
        while (entries.size > maxEntries) entries.removeFirst()
        return entries.joinToString("\n")
    }
    fun fullText(): String = entries.joinToString("\n")
}
