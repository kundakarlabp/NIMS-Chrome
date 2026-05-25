package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class HelperApiClient(
    private val helperUrl: String,
    private val apiKeyProvider: () -> String
) {
    fun health(): JSONObject {
        return request("GET", "/health", null, false)
    }

    fun version(): JSONObject {
        return request("GET", "/version", null, false)
    }

    fun parseReport(payload: JSONObject): JSONObject {
        return request("POST", "/parse-report", payload, true)
    }

    fun summarize(payload: JSONObject): JSONObject {
        return request("POST", "/summarize", payload, true)
    }

    private fun request(method: String, path: String, body: JSONObject?, requiresKey: Boolean): JSONObject {
        val url = URL(helperUrl.trimEnd('/') + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = if (body == null) 15_000 else 60_000
            setRequestProperty("Accept", "application/json")
            if (requiresKey) {
                val key = apiKeyProvider()
                if (key.isBlank()) throw IllegalStateException("Set Railway helper API key first.")
                setRequestProperty("X-NIMS-HELPER-KEY", key)
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        val data = stream?.use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toString(Charsets.UTF_8.name())
        }.orEmpty()
        if (connection.responseCode >= 400) {
            val error = runCatching { JSONObject(data).optString("error") }.getOrNull().orEmpty()
            val suffix = if (error.isBlank()) "" else ": $error"
            throw IllegalStateException("Helper returned ${connection.responseCode}$suffix")
        }
        return if (data.isBlank()) JSONObject() else JSONObject(data)
    }
}
