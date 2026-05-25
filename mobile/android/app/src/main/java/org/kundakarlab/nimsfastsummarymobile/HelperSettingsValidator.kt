package org.kundakarlab.nimsfastsummarymobile

object HelperSettingsValidator {
    fun normalizeUrl(value: String, debugBuild: Boolean = BuildConfig.DEBUG): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) throw IllegalArgumentException("Set Railway helper URL first.")
        val localAllowed = debugBuild && (
            trimmed.startsWith("http://10.0.2.2") || trimmed.startsWith("http://127.0.0.1")
        )
        if (!trimmed.startsWith("https://") && !localAllowed) {
            throw IllegalArgumentException("Helper URL must start with https://.")
        }
        return trimmed
    }
}
