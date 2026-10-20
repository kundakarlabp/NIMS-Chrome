package org.kundakarlab.nimsresults

import org.jsoup.Jsoup
import org.json.JSONArray
import org.json.JSONObject

/**
 * Best-effort table formatting of values actually supplied in the structured
 * HIS feed. Source cells are preserved; unknown assay control codes stay raw.
 */
object ResultTableFormats {
    fun format(value: String): String {
        val source = value.trim()
        if (source.isBlank()) return ""
        if (Regex("""(?i)<table(?:\s|>)""").containsMatchIn(source)) {
            val rows = Jsoup.parseBodyFragment(source).select("table tr").mapNotNull { row ->
                row.select("th,td").map { it.text().trim() }.filter(String::isNotBlank)
                    .takeIf { it.isNotEmpty() }?.joinToString(" | ")
            }
            if (rows.isNotEmpty()) return rows.joinToString("\n")
        }
        if (source.startsWith("[") && source.endsWith("]")) {
            val parsed = runCatching { JSONArray(source) }.getOrNull()
            if (parsed != null && parsed.length() in 1..200) {
                val lines = (0 until parsed.length()).map { idx ->
                    when (val item = parsed.opt(idx)) {
                        is JSONObject -> item.keys().asSequence().take(10).map {
                            key -> key + ": " + item.optString(key)
                        }.joinToString(" | ")
                        else -> item.toString()
                    }
                }
                return lines.joinToString("\n")
            }
        }
        return source
    }
}
