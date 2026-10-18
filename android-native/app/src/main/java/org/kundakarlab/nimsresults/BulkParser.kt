package org.kundakarlab.nimsresults

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.util.Locale

object BulkParser {
    private val crPattern = Regex("""\d{15}""")
    private val months = mapOf(
        "jan" to "01", "feb" to "02", "mar" to "03", "apr" to "04",
        "may" to "05", "jun" to "06", "jul" to "07", "aug" to "08",
        "sep" to "09", "oct" to "10", "nov" to "11", "dec" to "12"
    )

    fun parse(
        rawJson: String,
        expectedCr: String,
        patient: PatientInfo,
        reports: List<ReportRef>
    ): ClinicalBundle {
        require(crPattern.matches(expectedCr)) { "Enter the 15-digit NIMS CR number." }
        val root: Any = when {
            rawJson.trim().startsWith("[") -> JSONArray(rawJson)
            else -> JSONObject(rawJson)
        }
        val bulk = findBulk(root) ?: throw IllegalArgumentException("NIMS Custom Sheet did not return structured groups.")
        val status = bulk.opt("status")?.toString()?.trim()
        if (!status.isNullOrBlank() && status != "1") {
            throw IllegalArgumentException("NIMS Custom Sheet request failed.")
        }
        val returnedCr = bulk.optString("crNo").filter(Char::isDigit)
        if (returnedCr.isNotBlank() && returnedCr != expectedCr) {
            throw IllegalStateException("NIMS returned a different CR number. Results were rejected.")
        }

        val results = mutableListOf<LabResult>()
        val groups = bulk.optJSONArray("groups") ?: JSONArray()
        for (g in 0 until groups.length()) {
            val group = groups.optJSONObject(g) ?: continue
            val test = clean(group.optString("testName"))
            val rows = group.optJSONArray("rows") ?: JSONArray()
            for (r in 0 until rows.length()) {
                val row = rows.optJSONObject(r) ?: continue
                val parameter = clean(row.optString("parameterName"))
                val unit = clean(row.optString("unit"))
                val refRange = clean(row.optString("refRange"))
                val values = row.optJSONObject("valuesByDate") ?: JSONObject()
                val flags = firstObject(row, "flagsByDate", "abnormalByDate", "flagByDate")
                val keys = values.keys()
                while (keys.hasNext()) {
                    val rawDate = keys.next()
                    val valueText = cleanValue(values.opt(rawDate)?.toString())
                    if (valueText.isBlank()) continue
                    val date = isoDate(rawDate)
                    val abnormal = clean(flags?.opt(rawDate)?.toString())
                        .ifBlank { clean(row.opt("abnormal")?.toString()) }
                        .ifBlank { clean(row.opt("flag")?.toString()) }
                    results += LabResult(
                        id = "nims-${results.size}",
                        test = test,
                        parameter = parameter,
                        date = date,
                        valueText = valueText,
                        numericValue = parseNumber(valueText),
                        unit = unit,
                        refRange = refRange,
                        abnormal = abnormal,
                        sourceKey = sourceKey(test, parameter, date, unit, valueText)
                    )
                }
            }
        }
        if (results.isEmpty()) throw IllegalArgumentException("NIMS Custom Sheet returned no result values.")

        val mergedPatient = patient.copy(
            crNo = expectedCr,
            name = patient.name.ifBlank { clean(bulk.optString("patientName")) }
        )
        return ClinicalBundle(
            patient = mergedPatient,
            results = results.sortedWith(compareByDescending<LabResult> { dateKey(it.date) }.thenBy { it.test }.thenBy { it.parameter }),
            reports = reports
        )
    }

    private fun findBulk(node: Any?, depth: Int = 0): JSONObject? {
        if (node == null || depth > 6) return null
        return when (node) {
            is JSONObject -> {
                if (node.optJSONArray("groups") != null) node
                else {
                    val keys = node.keys()
                    var found: JSONObject? = null
                    while (keys.hasNext() && found == null) found = findBulk(node.opt(keys.next()), depth + 1)
                    found
                }
            }
            is JSONArray -> {
                var found: JSONObject? = null
                var i = 0
                while (i < node.length() && found == null) {
                    found = findBulk(node.opt(i), depth + 1)
                    i += 1
                }
                found
            }
            else -> null
        }
    }

    private fun firstObject(row: JSONObject, vararg names: String): JSONObject? {
        for (name in names) row.optJSONObject(name)?.let { return it }
        return null
    }

    fun isoDate(value: String): String {
        val text = value.trim()
        val match = Regex("""^(\d{1,2})-([A-Za-z]{3})-(\d{4})$""").matchEntire(text) ?: return text
        val month = months[match.groupValues[2].lowercase(Locale.US)] ?: return text
        return "${match.groupValues[3]}-$month-${match.groupValues[1].padStart(2, '0')}"
    }

    fun dateKey(value: String): Long {
        val parts = value.split("-")
        if (parts.size == 3 && parts[0].length == 4) {
            return (parts[0].toLongOrNull() ?: 0L) * 10000L +
                (parts[1].toLongOrNull() ?: 0L) * 100L +
                (parts[2].toLongOrNull() ?: 0L)
        }
        return 0L
    }

    private fun parseNumber(value: String): Double? {
        // A number inside a culture narrative, detection limit or comment is
        // not a quantitative measurement. Preserve text but exclude it from trends.
        val text = value.trim().replace(",", "")
        return text.takeIf {
            Regex("""[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?""").matches(it)
        }?.toDoubleOrNull()
    }

    private fun sourceKey(test: String, parameter: String, date: String, unit: String, value: String): String =
        listOf("nims_bulk_api", canonical(test), canonical(parameter), date, canonical(unit), value.trim()).joinToString("|")

    private fun canonical(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "")
    private fun clean(value: String?): String = value.orEmpty().replace(Regex("\\s+"), " ").trim()

    // Preserve susceptibility table layout instead of merging all its rows.
    private fun cleanValue(value: String?): String {
        val withBreaks = value.orEmpty()
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(?:p|div|tr|li)>"), "\n")
            .replace(Regex("(?i)<td[^>]*>"), " | ")
            .replace(Regex("(?i)</?[a-z][^>]*>"), " ")
        return Parser.unescapeEntities(withBreaks, false)
            .replace("\r\n", "\n")
            .split("\n")
            .joinToString("\n") { line -> line.replace(Regex("[ \\t]+"), " ").trim() }
            .trim()
    }

}
