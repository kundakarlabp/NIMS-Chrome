package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.json.JSONArray
import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.domain.model.ProcessingSummary

object RemoteSummaryMapper {
    fun toProcessingSummary(response: JSONObject, reportCount: Int): ProcessingSummary {
        val interpretation = response.optJSONArray("interpretation")?.let { array ->
            buildList { for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
        }.orEmpty()
        val warnings = response.optJSONArray("warnings")?.let { array ->
            buildList { for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
        }.orEmpty()
        val hasStructured = response.has("source_reports") || response.has("lab_trend_table") || response.has("culture_table")
        if (interpretation.isEmpty() && !hasStructured) throw IllegalArgumentException("REMOTE_INVALID_RESPONSE")
        return ProcessingSummary(
            text = interpretation.joinToString("\n"),
            reportsProcessed = reportCount,
            warnings = warnings,
            helperJson = sanitizeForPersistence(response)
        )
    }

    private fun sanitizeForPersistence(source: JSONObject): JSONObject =
        sanitizeObject(JSONObject(source.toString()))

    private fun sanitizeObject(value: JSONObject): JSONObject {
        val keys = value.keys().asSequence().toList()
        for (key in keys) {
            if (key.lowercase() in forbiddenKeys) {
                value.remove(key)
                continue
            }
            when (val child = value.opt(key)) {
                is JSONObject -> sanitizeObject(child)
                is JSONArray -> sanitizeArray(child)
            }
        }
        return value
    }

    private fun sanitizeArray(value: JSONArray) {
        for (index in 0 until value.length()) {
            when (val child = value.opt(index)) {
                is JSONObject -> sanitizeObject(child)
                is JSONArray -> sanitizeArray(child)
            }
        }
    }

    private val forbiddenKeys = setOf(
        "raw_text",
        "raw_text_preview",
        "raw_section_text",
        "raw_html",
        "pdf_base64"
    )
}
