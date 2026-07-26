package org.kundakarlab.nimsfastsummarymobile.ui.mappers

import org.json.JSONArray
import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.data.processing.DateNormalizer
import org.kundakarlab.nimsfastsummarymobile.ui.models.Abnormality
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiCultureRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiLabTrendRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSourceReport
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary

object SummaryJsonMapper {
    fun parseSummaryJsonToUiSummary(summary: JSONObject, editableNote: String = ""): UiSummary = UiSummary(
        sourceReports = sourceReports(summary.optJSONArray("source_reports")),
        labTrends = labTrends(summary.optJSONObject("lab_trend_table")),
        cultures = cultures(summary.optJSONArray("culture_table")),
        interpretation = strings(summary.optJSONArray("interpretation")),
        editableNote = editableNote,
        rawJson = summary
    )

    private fun sourceReports(rows: JSONArray?): List<UiSourceReport> {
        if (rows == null) return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val status = row.optString("status", "unknown")
                add(UiSourceReport(
                    sourceKey = row.optString("report_id"),
                    dateSent = row.optString("date_sent"),
                    reportName = row.optString("report_name", "Unnamed report"),
                    type = row.optString("type", row.optString("report_type", "other")),
                    status = status,
                    notes = row.optString("notes"),
                    hasError = status.equals("error", true) || status.equals("unsupported", true),
                    sourceAction = row.optString("action", "Open source report in NIMS")
                ))
            }
        }.sortedByDescending { DateNormalizer.normalize(it.dateSent).sortEpoch ?: Long.MIN_VALUE }
    }

    private fun labTrends(table: JSONObject?): List<UiLabTrendRow> {
        if (table == null) return emptyList()
        val columns = strings(table.optJSONArray("columns"))
        val rows = table.optJSONArray("rows") ?: return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val values = stringsPreserveBlanks(row.optJSONArray("values"))
                if (values.size != columns.size) continue
                val history = columns.zip(values).filter { it.second.isNotBlank() }
                val latest = history.firstOrNull()
                val previous = history.drop(1).firstOrNull()
                add(UiLabTrendRow(
                    parameter = row.optString("parameter", "Parameter"),
                    latestValue = latest?.second.orEmpty(), latestDate = latest?.first.orEmpty(),
                    previousValue = previous?.second, previousDate = previous?.first,
                    trendText = row.optString("trend", "insufficient data"),
                    abnormality = abnormality(latest?.second.orEmpty()), history = history
                ))
            }
        }
    }

    private fun cultures(rows: JSONArray?): List<UiCultureRow> {
        if (rows == null) return emptyList()
        val observations = buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                add(UiCultureRow(
                    sourceKey = row.optString("report_id"),
                    collectionDate = firstNonBlank(row, "collection_date", "date_sent", "reporting_date"),
                    cultureNo = firstNonBlank(row, "lab_study_number", "culture_no", "culture_number", "specimen_no"),
                    specimen = firstNonBlank(row, "specimen", "sample_type", "site_specimen", "specimen_no"),
                    site = firstNonBlank(row, "site", "bottle_name", "site_specimen"),
                    organism = firstNonBlank(row, "organism", "organism_name", "growth"),
                    growth = firstNonBlank(row, "growth", "growth_quantity", "result"),
                    status = normalizeCultureStatus(firstNonBlank(row, "result", "status", "report_status", "reporting_status", fallback = "unknown")),
                    sensitivitySummary = susceptibilitySummary(row).ifBlank { sirSummary(row).ifBlank { stringField(row, "sensitivity_summary") } },
                    comment = cultureComment(row),
                    sourceReportName = row.optString("report_name"),
                    reportingDate = row.optString("reporting_date"),
                    reportStage = row.optString("report_stage"),
                    bottleName = row.optString("bottle_name"),
                    setNumber = intField(row, "set_number"),
                    bottleNumber = intField(row, "bottle_number"),
                    isolateNumber = intField(row, "isolate_number"),
                    gramStain = row.optString("gram_stain"),
                    confidence = row.optString("confidence", "unknown"),
                    antibiogramCompleteness = when {
                        row.optJSONArray("susceptibility")?.length()?.let { it > 0 } == true -> "available"
                        row.optString("sensitivity_summary").isNotBlank() -> "available"
                        else -> "unavailable"
                    }
                ))
            }
        }
        return observations
            .groupBy { row ->
                listOf(
                    row.cultureNo.ifBlank { row.collectionDate },
                    row.specimen,
                    row.site,
                    row.setNumber?.toString().orEmpty(),
                    row.bottleNumber?.toString().orEmpty(),
                    row.isolateNumber?.toString().orEmpty()
                ).joinToString("|").lowercase()
            }
            .values
            .map { episode ->
                val preferred = episode.maxByOrNull { stageRank(it.reportStage) } ?: episode.last()
                preferred.copy(
                    sourceKey = preferred.sourceKey.ifBlank {
                        episode
                            .sortedByDescending { stageRank(it.reportStage) }
                            .firstNotNullOfOrNull { it.sourceKey.takeIf(String::isNotBlank) }
                            .orEmpty()
                    },
                    comment = episode.map { it.comment }.filter(String::isNotBlank).distinct().joinToString(" | "),
                    timeline = episode.sortedBy { stageRank(it.reportStage) }.map {
                        listOf(
                            it.reportStage.ifBlank { "observation" },
                            it.reportingDate.ifBlank { it.collectionDate },
                            it.status.replace("_", " ")
                        ).filter(String::isNotBlank).joinToString(" · ")
                    }.distinct()
                )
            }
            .sortedWith(
                compareByDescending<UiCultureRow> { statusRank(it.status) }
                    .thenByDescending { DateNormalizer.normalize(it.collectionDate).sortEpoch ?: Long.MIN_VALUE }
            )
    }

    private fun stageRank(value: String): Int = when {
        value.equals("final", true) -> 3
        value.contains("48-hour", true) -> 2
        value.contains("preliminary", true) -> 1
        else -> 0
    }

    private fun statusRank(value: String): Int = when {
        value.equals("growth_detected", true) -> 4
        value.equals("pending", true) -> 3
        value.equals("unknown", true) -> 2
        value.equals("no_growth", true) -> 1
        else -> 0
    }

    private fun normalizeCultureStatus(value: String): String {
        val normalized = value.trim().lowercase().replace(' ', '_')
        return when {
            normalized in setOf("positive", "growth", "growth_present", "growth_detected", "isolated") -> "growth_detected"
            normalized in setOf("negative", "sterile", "no_growth", "no_growth_detected") -> "no_growth"
            normalized.contains("prelim") || normalized.contains("pending") -> "pending"
            normalized.isBlank() -> "unknown"
            else -> normalized
        }
    }

    private fun susceptibilitySummary(row: JSONObject): String {
        val values = row.optJSONArray("susceptibility") ?: return ""
        return buildList {
            for (index in 0 until values.length()) {
                val result = values.optJSONObject(index) ?: continue
                val drug = result.optString("antibiotic").trim()
                val interpretation = result.optString("interpretation").trim()
                if (drug.isBlank() || interpretation.isBlank()) continue
                val micValue = result.opt("mic_value").takeUnless { it == null || it == JSONObject.NULL }?.toString().orEmpty()
                val mic = if (micValue.isBlank()) "" else " (MIC ${result.optString("mic_comparator")}$micValue ${result.optString("mic_unit")})"
                add("$drug $interpretation$mic".trim())
            }
        }.joinToString("; ")
    }

    private fun sirSummary(row: JSONObject): String {
        val summaryObject = row.optJSONObject("sensitivity_summary")
        val susceptible = valuesFromKeys(row, "sensitive", "susceptible", "sensitive_drugs", "susceptible_antibiotics") + valuesFromKeys(summaryObject, "sensitive", "susceptible")
        val intermediate = valuesFromKeys(row, "intermediate", "intermediate_drugs", "intermediate_antibiotics") + valuesFromKeys(summaryObject, "intermediate")
        val resistant = valuesFromKeys(row, "resistant", "resistant_drugs", "resistant_antibiotics") + valuesFromKeys(summaryObject, "resistant")
        return listOf("S" to susceptible, "I" to intermediate, "R" to resistant).mapNotNull { (label, values) ->
            values.distinctBy { it.lowercase() }.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "$label: ")
        }.joinToString("; ")
    }

    private fun valuesFromKeys(row: JSONObject?, vararg keys: String): List<String> {
        if (row == null) return emptyList()
        return buildList { for (key in keys) addAll(stringValues(row.opt(key))) }
    }

    private fun stringValues(value: Any?): List<String> = when (value) {
        null, JSONObject.NULL -> emptyList()
        is JSONArray -> strings(value)
        is String -> value.split(',', ';', '\n', '\r', '|').map(String::trim).filter(String::isNotBlank)
        else -> value.toString().trim().takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
    }

    private fun cultureComment(row: JSONObject): String {
        val notes = buildList {
            firstNonBlank(row, "comment", "microbiology_note", "note").takeIf { it.isNotBlank() }?.let(::add)
            if (booleanField(row, "clinical_review_flag")) add("Clinical-significance review required; verify with source report and bedside context.")
        }
        return notes.distinct().joinToString(" | ")
    }

    private fun intField(row: JSONObject, key: String): Int? = when (val value = row.opt(key)) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private fun booleanField(row: JSONObject, key: String): Boolean = when (val value = row.opt(key)) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        is String -> value.trim().lowercase() in setOf("true", "1", "yes", "y")
        else -> false
    }

    private fun strings(values: JSONArray?): List<String> {
        if (values == null) return emptyList()
        return buildList { for (index in 0 until values.length()) if (!values.isNull(index)) values.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add) }
    }

    private fun stringsPreserveBlanks(values: JSONArray?): List<String> {
        if (values == null) return emptyList()
        return buildList { for (index in 0 until values.length()) add(if (values.isNull(index)) "" else values.optString(index)) }
    }

    private fun stringField(row: JSONObject, key: String): String = (row.opt(key) as? String)?.trim().orEmpty()

    private fun abnormality(value: String): Abnormality {
        val lower = value.lowercase()
        return when {
            "critical" in lower -> Abnormality.CRITICAL
            "[high]" in lower || " high" in lower -> Abnormality.HIGH
            "[low]" in lower || " low" in lower -> Abnormality.LOW
            value.isBlank() -> Abnormality.UNKNOWN
            "[normal]" in lower -> Abnormality.NORMAL
            else -> Abnormality.UNKNOWN
        }
    }

    private fun firstNonBlank(row: JSONObject, vararg keys: String, fallback: String = ""): String {
        for (key in keys) {
            val value = row.optString(key).trim()
            if (value.isNotBlank() && !value.equals("null", true)) return value
        }
        return fallback
    }
}
