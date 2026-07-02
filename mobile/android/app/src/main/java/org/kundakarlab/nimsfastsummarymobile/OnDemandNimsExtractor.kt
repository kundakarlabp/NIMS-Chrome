package org.kundakarlab.nimsfastsummarymobile

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

internal class OnDemandNimsExtractor(private val webView: WebView) {
    private val script: String by lazy {
        webView.context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
    }

    fun extract(callback: (Result<JSONObject>) -> Unit) {
        webView.evaluateJavascript(script) { raw -> callback(decodeResult(raw)) }
    }

    companion object {
        internal const val ASSET_NAME = "nimsOnDemandExtractor.js"

        internal fun decodeResult(raw: String?): Result<JSONObject> = runCatching {
            val value = raw.orEmpty().trim()
            require(value.isNotBlank() && value != "null")
            JSONObject(JSONArray("[$value]").getString(0))
        }
    }
}
