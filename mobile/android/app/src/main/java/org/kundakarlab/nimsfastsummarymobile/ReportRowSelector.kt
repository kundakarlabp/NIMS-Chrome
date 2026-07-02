package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONArray
import org.json.JSONObject

internal enum class NimsAnalysisMode {
    FAST,
    CULTURES_ONLY,
    FULL
}

internal object ReportRowSelector {
    fun select(rows: JSONArray, mode: NimsAnalysisMode): List<JSONObject> {
        val all = buildList {
            for (index in 0 until rows.length()) {
                rows.optJSONObject(index)?.let(::add)
            }
        }
        return when (mode) {
            NimsAnalysisMode.FULL -> all
            NimsAnalysisMode.CULTURES_ONLY -> all.filter { it.hasTag("culture") }
            NimsAnalysisMode.FAST -> selectFast(all)
        }
    }

    private fun selectFast(rows: List<JSONObject>): List<JSONObject> {
        val selected = mutableListOf<JSONObject>()
        var cbc = 0
        var chemistry = 0
        for (row in rows) {
            if (selected.size >= 20) break
            when {
                row.hasTag("culture") || row.hasTag("inflammatory") -> selected += row
                row.hasTag("cbc") && cbc < 3 -> {
                    selected += row
                    cbc += 1
                }
                (row.hasTag("rft") || row.hasTag("lft") || row.hasTag("electrolytes")) && chemistry < 3 -> {
                    selected += row
                    chemistry += 1
                }
            }
        }
        return if (selected.isNotEmpty()) selected else rows.take(1)
    }

    private fun JSONObject.hasTag(tag: String): Boolean {
        val tags = optJSONArray("report_tags") ?: return optString("report_type").equals(tag, ignoreCase = true)
        for (index in 0 until tags.length()) {
            if (tags.optString(index).equals(tag, ignoreCase = true)) return true
        }
        return false
    }
}
