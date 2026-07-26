package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.json.JSONArray
import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.BuildConfig
import org.kundakarlab.nimsfastsummarymobile.domain.model.*

class LocalSummaryBuilder {
    fun build(reports: List<ParsedReport>, mode: SummaryMode): ProcessingSummary {
        val warnings = mutableListOf<String>()
        val unsupportedCount = reports.count { it.labs.isEmpty() && it.cultures.isEmpty() }
        val lowConfidenceLabCount = reports.sumOf { report -> report.labs.count { it.confidence == ParseConfidence.LOW } }
        val lines = mutableListOf("Auto-parsed summary. Verify with source NIMS reports before clinical decisions.")
        if (unsupportedCount > 0) lines += "Failed or unsupported reports: $unsupportedCount."
        if (lowConfidenceLabCount > 0) warnings += "Some low-confidence laboratory values were excluded; review source reports."
        val sortedReports = reports.withIndex().sortedWith(compareBy<IndexedValue<ParsedReport>>(
            { DateNormalizer.normalize(it.value.dateSent).sortEpoch == null },
            { DateNormalizer.normalize(it.value.dateSent).sortEpoch ?: 0L },
            { it.index }
        )).map { it.value }
        if (reports.any { it.dateSent.isNotBlank() && DateNormalizer.normalize(it.dateSent).sortEpoch == null }) warnings += "Some report dates could not be normalized; trend ordering may be uncertain."
        when (mode) {
            SummaryMode.FAST -> addLabTrends(lines, sortedReports, keyOnly = true, warnings = warnings).also { addCultures(lines, sortedReports, concise = true) }
            SummaryMode.CULTURES_ONLY -> addCultures(lines, sortedReports, concise = false)
            SummaryMode.FULL -> {
                lines += "Reports processed: ${reports.size}; failed/unsupported: $unsupportedCount."
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
        val keyCodes = setOf(
            "HB", "WBC", "PLT", "CREAT", "UREA", "NA", "K", "TBIL",
            "CRP", "HSCRP", "ESR", "PCT", "GM", "BDG",
            "INR", "ALT", "AST", "ALB"
        )
        reports.flatMap { report -> report.labs.filter { it.confidence != ParseConfidence.LOW && (!keyOnly || CanonicalLabCodes.normalize(it.canonicalCode) in keyCodes) }.map { report to it } }
            .groupBy { CanonicalLabCodes.normalize(it.second.canonicalCode) }.values.forEach { rows ->
                val datedRows = rows.mapNotNull { row -> DateNormalizer.normalize(row.first.dateSent).sortEpoch?.let { Triple(it, row.first, row.second) } }.sortedBy { it.first }
                val undatedRows = rows.filter { DateNormalizer.normalize(it.first.dateSent).sortEpoch == null }
                if (datedRows.isEmpty()) {
                    rows.lastOrNull()?.second?.let { lab ->
                        val flag = when (lab.abnormality) { Abnormality.HIGH -> " [HIGH]"; Abnormality.LOW -> " [LOW]"; Abnormality.CRITICAL -> " [CRITICAL]"; else -> "" }
                        lines += "Recorded ${lab.displayName}: ${lab.valueText()}$flag; report date unavailable."
                    }
                    if (undatedRows.isNotEmpty()) warnings += "Undated ${rows.first().second.displayName} value(s) were not used for trend calculation."
                    return@forEach
                }
                val latest = datedRows.last().third
                val previous = datedRows.dropLast(1).lastOrNull()?.third
                val latestValue = latest.valueText()
                val flag = when (latest.abnormality) { Abnormality.HIGH -> " [HIGH]"; Abnormality.LOW -> " [LOW]"; Abnormality.CRITICAL -> " [CRITICAL ⚠]"; else -> "" }
                if (previous?.numericValue != null && latest.numericValue != null) {
                    val direction = when { latest.numericValue > previous.numericValue -> "increased"; latest.numericValue < previous.numericValue -> "decreased"; else -> "unchanged" }
                    lines += "${latest.displayName} $direction from ${previous.valueText()} to $latestValue$flag."
                } else if (latestValue.isNotBlank()) lines += "Latest ${latest.displayName}: $latestValue$flag."
                if (undatedRows.isNotEmpty()) warnings += "Additional undated ${latest.displayName} value(s) were not used for trend calculation."
            }
    }

    private fun addCultures(lines: MutableList<String>, reports: List<ParsedReport>, concise: Boolean) {
        val cultures = reports.flatMap { it.cultures }
        cultures.filter { it.growthStatus == GrowthStatus.GROWTH_DETECTED }.forEach {
            val identity = listOfNotNull(
                it.specimen,
                it.bottleNumber?.let { n -> "bottle $n" },
                it.isolateNumber?.let { n -> "isolate $n" },
                it.reportStage?.takeUnless { stage -> stage == "unspecified" }
            ).joinToString(" · ").ifBlank { "Culture" }
            lines += "$identity: ${it.organism ?: "growth reported"}."
            if (!concise && it.explicitResistanceMarkers.isNotEmpty()) lines += "Explicit resistance markers: ${it.explicitResistanceMarkers.joinToString(", ")}."
            if (!concise && it.susceptibility.isNotEmpty()) lines += "Susceptibility: ${it.susceptibility.joinToString("; ") { s -> s.displayText() }}."
        }
        val noGrowth = cultures.filter { it.growthStatus == GrowthStatus.NO_GROWTH }
        if (noGrowth.isNotEmpty()) lines += "No-growth cultures: ${noGrowth.mapNotNull { it.specimen }.ifEmpty { listOf(noGrowth.size.toString()) }.joinToString(", ")}."
        val pending = cultures.count { it.growthStatus == GrowthStatus.PENDING }
        if (pending > 0) lines += "Preliminary/pending culture observations: $pending."
    }

    private fun dateRange(reports: List<ParsedReport>): String? = reports.mapNotNull { report -> DateNormalizer.normalize(report.dateSent).sortEpoch?.let { it to report.dateSent } }.sortedBy { it.first }.map { it.second }.let { dates -> if (dates.isEmpty()) null else "${dates.first()} to ${dates.last()}" }
    private fun ParsedLabValue.valueText(): String = ((if (comparator == NumericComparator.LESS_THAN) "<" else if (comparator == NumericComparator.GREATER_THAN) ">" else "") + (numericValue?.toString() ?: textValue.orEmpty()) + " " + unit.orEmpty()).trim()
    private fun AntibioticResult.displayText(): String {
        val mic = micValue?.let { value -> " (MIC ${micComparator.orEmpty()}$value ${micUnit.orEmpty()})" }.orEmpty()
        return "$antibiotic $interpretation$mic".trim()
    }

    private fun toSummaryJson(reports: List<ParsedReport>, lines: List<String>, warnings: List<String>): JSONObject {
        val normalizedDates = reports.map { it.dateSent to DateNormalizer.normalize(it.dateSent) }
            .filter { it.second.sortEpoch != null }
            .distinctBy { it.second.sortEpoch }
            .sortedByDescending { it.second.sortEpoch }
        val dateColumns = normalizedDates.map { it.first }
        val dateByEpoch = normalizedDates.associate { it.second.sortEpoch to it.first }
        val rowsByCode = linkedMapOf<String, MutableMap<String, IndexedLab>>()
        reports.forEachIndexed { reportIndex, report ->
            val reportDate = dateByEpoch[DateNormalizer.normalize(report.dateSent).sortEpoch] ?: return@forEachIndexed
            report.labs.forEachIndexed { labIndex, lab ->
                if (lab.confidence == ParseConfidence.LOW) return@forEachIndexed
                val canonicalCode = CanonicalLabCodes.normalize(lab.canonicalCode)
                val byDate = rowsByCode.getOrPut(canonicalCode) { linkedMapOf() }
                val candidate = IndexedLab(lab, reportIndex, labIndex)
                val current = byDate[reportDate]
                if (current == null || isPreferred(candidate, current)) byDate[reportDate] = candidate
            }
        }
        return JSONObject()
            .put("schema_version", "1.0")
            .put("parser_version", "android-${BuildConfig.VERSION_NAME}")
            .put("generated_at_epoch_ms", System.currentTimeMillis())
            .put("source_reports", JSONArray().also { a -> reports.forEach { r ->
                val lowNote = if (r.labs.any { it.confidence == ParseConfidence.LOW }) "Low-confidence laboratory value(s) excluded; review source report." else ""
                val notes = (r.warnings + lowNote).filter { it.isNotBlank() }.joinToString("; ")
                a.put(JSONObject().put("report_id", r.reportId).put("date_sent", r.dateSent).put("report_name", r.reportName).put("type", r.reportType)
                    .put("status", if (r.labs.isEmpty() && r.cultures.isEmpty()) "unsupported" else "parsed").put("notes", notes)
                    .put("action", "Open source report in NIMS")
                    .put("processor", r.processorName))
            } })
            .put("interpretation", JSONArray(lines))
            .put("culture_table", JSONArray().also { a -> reports.forEach { report -> report.cultures.forEach { c ->
                val commentParts = buildList {
                    if (c.explicitResistanceMarkers.isNotEmpty()) add("Markers: " + c.explicitResistanceMarkers.joinToString(", "))
                    addAll(c.comments)
                }
                a.put(JSONObject()
                    .put("report_id", report.reportId)
                    .put("collection_date", c.collectionDate.orEmpty()).put("reporting_date", c.reportingDate.orEmpty())
                    .put("lab_study_number", c.labStudyNumber.orEmpty()).put("specimen", c.specimen.orEmpty()).put("site", c.site.orEmpty())
                    .put("organism", c.organism.orEmpty()).put("organism_raw", c.organismRaw.orEmpty()).put("status", c.growthStatus.name.lowercase())
                    .put("confidence", c.confidence.name.lowercase())
                    .put("report_stage", c.reportStage.orEmpty()).put("bottle_name", c.bottleName.orEmpty())
                    .put("set_number", c.setNumber ?: JSONObject.NULL).put("bottle_number", c.bottleNumber ?: JSONObject.NULL)
                    .put("isolate_number", c.isolateNumber ?: JSONObject.NULL).put("gram_stain", c.gramStain.orEmpty())
                    .put("report_name", report.reportName)
                    .put("susceptibility", JSONArray().also { results -> c.susceptibility.forEach { s ->
                        results.put(JSONObject().put("antibiotic", s.antibiotic).put("interpretation", s.interpretation)
                            .put("mic_value", s.micValue ?: JSONObject.NULL).put("mic_comparator", s.micComparator.orEmpty()).put("mic_unit", s.micUnit.orEmpty()))
                    } })
                    .put("sensitivity_summary", c.susceptibility.joinToString("; ") { s -> s.displayText() })
                    .put("comment", commentParts.joinToString(" | ")))
            } } })
            .put("lab_trend_table", JSONObject().put("columns", JSONArray(dateColumns)).put("rows", JSONArray().also { rows ->
                rowsByCode.toSortedMap().forEach { (_, labsByDate) ->
                    val firstLab = labsByDate.values.first().lab
                    rows.put(JSONObject().put("parameter", firstLab.displayName).put("trend", "auto-parsed").put("values", JSONArray(dateColumns.map { date -> labsByDate[date]?.lab?.valueText().orEmpty() })))
                }
            }))
            .put("warnings", JSONArray(warnings))
    }

    private fun isPreferred(candidate: IndexedLab, current: IndexedLab): Boolean = compareValuesBy(candidate, current, { confidenceRank(it.lab.confidence) }, { -it.reportIndex }, { -it.labIndex }, { stableLabKey(it.lab) }) > 0
    private fun confidenceRank(value: ParseConfidence): Int = when (value) { ParseConfidence.HIGH -> 2; ParseConfidence.MEDIUM -> 1; ParseConfidence.LOW -> 0 }
    private fun stableLabKey(lab: ParsedLabValue): String = listOf(lab.numericValue?.toString().orEmpty(), lab.textValue.orEmpty(), lab.unit.orEmpty(), lab.comparator.name).joinToString("|")
    private data class IndexedLab(val lab: ParsedLabValue, val reportIndex: Int, val labIndex: Int)
}
