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
        return request("GET", "/health", null)
    }

    fun parseReport(payload: JSONObject): JSONObject {
        return request("POST", "/parse-report", payload)
    }

    fun summarize(payload: JSONObject): JSONObject {
        return request("POST", "/summarize", payload)
    }

    private fun request(method: String, path: String, body: JSONObject?): JSONObject {
        val url = URL(helperUrl.trimEnd('/') + path)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 45_000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                val key = apiKeyProvider()
                if (key.isNotBlank()) setRequestProperty("X-NIMS-HELPER-KEY", key)
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
            val error = if (data.isBlank()) "Helper returned ${connection.responseCode}" else data
            throw IllegalStateException(error)
        }
        return if (data.isBlank()) JSONObject() else JSONObject(data)
    }
}
