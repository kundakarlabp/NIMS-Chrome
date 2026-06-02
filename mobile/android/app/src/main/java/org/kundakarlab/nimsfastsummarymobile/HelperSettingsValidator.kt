package org.kundakarlab.nimsfastsummarymobile

object HelperSettingsValidator {
    @Suppress("UNUSED_PARAMETER")
    fun normalizeUrl(value: String, debugBuild: Boolean = BuildConfig.DEBUG): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) throw IllegalArgumentException("Set Railway helper URL first.")
        if (!trimmed.startsWith("https://")) {
            throw IllegalArgumentException("Helper URL must start with https://.")
        }
        return trimmed
    }
}
