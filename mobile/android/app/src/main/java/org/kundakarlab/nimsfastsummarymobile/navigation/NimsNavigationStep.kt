package org.kundakarlab.nimsfastsummarymobile.navigation

import org.json.JSONObject

data class NimsNavigationStep(
    val ok: Boolean,
    val stage: String,
    val action: String,
    val done: Boolean,
    val errorCode: String
) {
    companion object {
        fun fromJson(json: JSONObject): NimsNavigationStep = NimsNavigationStep(
            ok = json.optBoolean("ok", false),
            stage = json.optString("stage", "unknown"),
            action = json.optString("action", "none"),
            done = json.optBoolean("done", false),
            errorCode = json.optString("errorCode", "")
        )

        fun controlledError(errorCode: String): NimsNavigationStep = NimsNavigationStep(
            ok = false,
            stage = "unknown",
            action = "none",
            done = false,
            errorCode = errorCode
        )

        fun fromRawJson(rawJson: String?): NimsNavigationStep {
            val trimmed = rawJson?.trim().orEmpty()
            if (trimmed.isBlank() || trimmed == "null") return controlledError("navigation_js_empty_result")
            return runCatching { fromJson(JSONObject(trimmed)) }
                .getOrElse { controlledError("navigation_js_decode_failed") }
        }
    }
}
