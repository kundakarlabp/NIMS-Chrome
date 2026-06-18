package org.kundakarlab.nimsfastsummarymobile.data.processing

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
        return ProcessingSummary(text = interpretation.joinToString("\n"), reportsProcessed = reportCount, warnings = warnings, helperJson = response)
    }
}
