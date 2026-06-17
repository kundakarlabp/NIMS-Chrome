package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.domain.model.ProcessingSummary

object RemoteSummaryMapper {
    fun toProcessingSummary(response: JSONObject, reportCount: Int): ProcessingSummary {
        val text = response.optJSONArray("interpretation")?.let { array ->
            buildList { for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let(::add) }.joinToString("\n")
        }.orEmpty()
        return ProcessingSummary(text = text, reportsProcessed = reportCount, warnings = emptyList(), helperJson = response)
    }
}
