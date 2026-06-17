package org.kundakarlab.nimsfastsummarymobile.security

object LogSanitizer {
    fun urlHostPath(value: String): String = value.substringBefore('?').replace(Regex("CR\\d{4,}", RegexOption.IGNORE_CASE), "CR_REDACTED")
    fun message(value: String, maxLength: Int = 500): String = value
        .replace(Regex("(?i)(cookie|api[_-]?key|authorization)\\s*[:=]\\s*\\S+"), "$1=<redacted>")
        .replace(Regex("CR\\d{4,}", RegexOption.IGNORE_CASE), "CR_REDACTED")
        .let { it.substring(0, minOf(maxLength, it.length)) }
}
