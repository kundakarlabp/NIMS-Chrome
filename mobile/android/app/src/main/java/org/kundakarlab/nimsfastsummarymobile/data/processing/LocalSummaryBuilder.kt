package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.json.JSONArray
import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.domain.model.*

class LocalSummaryBuilder {
    fun build(reports: List<ParsedReport>, mode: SummaryMode): ProcessingSummary {
        val warnings = mutableListOf<String>()
        val lines = mutableListOf("Auto-parsed summary. Verify with source NIMS reports before clinical decisions.")
        val sortedReports = reports.withIndex().sortedWith(compareBy({ DateNormalizer.normalize(it.value.dateSent).sortEpoch ?: Long.MAX_VALUE }, { it.index })).map { it.value }
        if (reports.any { it.dateSent.isNotBlank() && DateNormalizer.normalize(it.dateSent).sortEpoch == null }) warnings += "Some report dates could not be normalized; trend ordering may be uncertain."
        when (mode) {
            SummaryMode.FAST -> addLabTrends(lines, sortedReports, keyOnly = true, warnings = warnings).also { addCultures(lines, sortedReports, concise = true) }
            SummaryMode.CULTURES_ONLY -> addCultures(lines, sortedReports, concise = false)
            SummaryMode.FULL -> {
                lines += "Reports processed: ${reports.size}."
                dateRange(sortedReports)?.let { lines += "Date range: $it." }
                addLabTrends(lines, sortedReports, keyOnly = false, warnings = warnings)
                addCultures(lines, sortedReports, concise = false)
                (reports.flatMap { it.warnings } + warnings).distinct().forEach { lines += "Warning: $it" }
            }
        }
        val helperJson = toSummaryJson(sortedReports, lines, warnings)
        return ProcessingSummary(lines.joinToString("\n"), reports.size, warnings.distinct(), helperJson)
    }

    private fun addLabTrends(lines: MutableList<String>, reports: List<ParsedReport>, keyOnly: Boolean, warnings: MutableList<String>) {
        val keyCodes = setOf("HB", "WBC", "PLT", "CREAT", "NA", "K", "TBIL", "CRP", "PCT", "INR")
        reports.flatMap { report -> report.labs.filter { it.confidence != ParseConfidence.LOW && (!keyOnly || it.canonicalCode in keyCodes) }.map { report to it } }
            .groupBy { it.second.canonicalCode }.values.forEach { rows ->
                val ordered = rows.sortedWith(compareBy({ DateNormalizer.normalize(it.first.dateSent).sortEpoch ?: Long.MAX_VALUE }))
                val latest = ordered.lastOrNull()?.second ?: return@forEach
                val previous = ordered.dropLast(1).lastOrNull()?.second
                val latestValue = latest.valueText()
                if (previous?.numericValue != null && latest.numericValue != null) {
                    val direction = when { latest.numericValue > previous.numericValue -> "increased"; latest.numericValue < previous.numericValue -> "decreased"; else -> "was unchanged" }
                    lines += "${latest.displayName} $direction from ${previous.valueText()} to $latestValue."
                } else if (latestValue.isNotBlank()) lines += "Latest ${latest.displayName}: $latestValue."
            }
    }

    private fun addCultures(lines: MutableList<String>, reports: List<ParsedReport>, concise: Boolean) {
        val cultures = reports.flatMap { it.cultures }
        cultures.filter { it.growthStatus == GrowthStatus.GROWTH_DETECTED }.forEach {
            lines += "${it.specimen ?: "Culture"}: ${it.organism ?: "growth reported"}."
            if (!concise && it.explicitResistanceMarkers.isNotEmpty()) lines += "Explicit resistance markers: ${it.explicitResistanceMarkers.joinToString(", ")}."
            if (!concise && it.susceptibility.isNotEmpty()) lines += "Susceptibility: ${it.susceptibility.joinToString("; ") { s -> "${s.antibiotic} ${s.interpretation}" }}."
        }
        val noGrowth = cultures.filter { it.growthStatus == GrowthStatus.NO_GROWTH }
        if (noGrowth.isNotEmpty()) lines += "No-growth cultures: ${noGrowth.mapNotNull { it.specimen }.ifEmpty { listOf(noGrowth.size.toString()) }.joinToString(", ")}."
    }

    private fun dateRange(reports: List<ParsedReport>): String? = reports.map { it.dateSent }.filter { it.isNotBlank() }.let { dates -> if (dates.isEmpty()) null else "${dates.first()} to ${dates.last()}" }
    private fun ParsedLabValue.valueText(): String = ((if (comparator == NumericComparator.LESS_THAN) "<" else if (comparator == NumericComparator.GREATER_THAN) ">" else "") + (numericValue?.toString() ?: textValue.orEmpty()) + " " + unit.orEmpty()).trim()

    private fun toSummaryJson(reports: List<ParsedReport>, lines: List<String>, warnings: List<String>): JSONObject = JSONObject()
        .put("source_reports", JSONArray().also { a -> reports.forEach { r -> a.put(JSONObject().put("date_sent", r.dateSent).put("report_name", r.reportName).put("type", r.reportType).put("status", "parsed").put("notes", r.warnings.joinToString("; ")).put("processor", r.processorName)) } })
        .put("interpretation", JSONArray(lines))
        .put("culture_table", JSONArray().also { a -> reports.flatMap { it.cultures }.forEach { c -> a.put(JSONObject().put("collection_date", c.collectionDate.orEmpty()).put("specimen", c.specimen.orEmpty()).put("organism", c.organism.orEmpty()).put("status", c.growthStatus.name.lowercase()).put("sensitivity_summary", c.susceptibility.joinToString("; ") { s -> "${s.antibiotic} ${s.interpretation}" }).put("comment", c.explicitResistanceMarkers.joinToString(", "))) } })
        .put("lab_trend_table", JSONObject().put("columns", JSONArray(reports.map { it.dateSent }.distinct())).put("rows", JSONArray().also { rows -> reports.flatMap { it.labs }.groupBy { it.displayName }.forEach { (name, labs) -> rows.put(JSONObject().put("parameter", name).put("trend", "auto-parsed").put("values", JSONArray(labs.map { it.valueText() }))) } }))
        .put("warnings", JSONArray(warnings))
}
