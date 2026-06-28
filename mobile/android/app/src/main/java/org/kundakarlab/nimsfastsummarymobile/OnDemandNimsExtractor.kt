package org.kundakarlab.nimsfastsummarymobile

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * Executes the report extractor only when the clinician taps Analyze.
 *
 * Nothing is installed at document start and no long-lived JavaScript bridge is
 * registered. The NIMS page therefore loads and navigates without app scripts.
 */
internal class OnDemandNimsExtractor(
    private val webView: WebView
) {
    private val script: String by lazy {
        webView.context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
    }

    fun extract(callback: (Result<JSONObject>) -> Unit) {
        webView.evaluateJavascript(script) { raw ->
            callback(decodeResult(raw))
        }
    }

    internal fun decodeResult(raw: String?): Result<JSONObject> = runCatching {
        val value = raw.orEmpty().trim()
        require(value.isNotBlank() && value != "null") { "NIMS did not return an extraction result." }
        val decoded = JSONArray("[$value]").getString(0)
        JSONObject(decoded)
    }

    companion object {
        internal const val ASSET_NAME = "nimsOnDemandExtractor.js"
    }
}
