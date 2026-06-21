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
    }
}
