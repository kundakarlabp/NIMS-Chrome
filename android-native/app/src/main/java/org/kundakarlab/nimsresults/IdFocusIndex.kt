package org.kundakarlab.nimsresults

import java.util.Locale

/**
 * One-pass, source-only ID biomarker index. Does not infer quantitative
 * results from culture narratives or interpret qualitative/code values.
 * CRP and hsCRP are separate markers and are never combined into one trend.
 */
object IdFocusIndex {
    data class Marker(val name: String, val aliases: Set<String>)
    val markers = listOf(
        Marker("CRP", setOf("crp", "creactiveprotein")),
        Marker("hsCRP", setOf("hscrp", "highsensitivitycrp", "highsensitivitycreactiveprotein")),
        Marker("ESR", setOf("esr", "erythrocytesedimentationrate")),
        Marker("Procalcitonin", setOf("procalcitonin", "pct")),
        Marker("WBC / TLC", setOf("wbc", "tlc", "totalleukocytecount",
            "totalleucocytecount", "whitebloodcellcount")),
        Marker("ANC", setOf("anc", "absoluteneutrophilcount"))
    )
    private val generic = setOf("result", "value", "observation")
    private fun key(s: String) = s.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    private fun find(row: LabResult): Int {
        if (row.numericValue?.isFinite() != true || ClinicalPresentation.isCulture(row.test) ||
            ClinicalReportRenderer.isUninterpretedCode(row) ||
            ClinicalPresentation.isTechnical(row)) return -1
        val param=key(row.parameter)
        val test=key(row.test)
        // A report explicitly labelled high-sensitivity CRP must not be indexed
        // as standard CRP merely because its inner row is labelled "CRP".
        // hsCRP is a distinct assay, not an interchangeable measurement.
        val highSensitivity = markers[1].aliases
        if (param in highSensitivity || test in highSensitivity ||
            highSensitivity.any { test == it + "quantitative" ||
                test == it + "result" || test == it + "test" }) return 1
        // Explicit parameter identity is strongest after assay specificity.
        for ((index, marker) in markers.withIndex()) {
            if (param in marker.aliases) return index
        }
        if (param !in generic) return -1
        // Only use an explicitly named test when the parameter is a generic
        // numeric result. A generic 'Result' inside an unrelated panel does
        // not identify the biomarker.
        for ((index, marker) in markers.withIndex()) {
            if (test in marker.aliases ||
                marker.aliases.any { alias ->
                    test == alias + "result" || test == alias + "quantitative" ||
                        test == alias + "test"
                }) return index
        }
        return -1
    }

    fun series(results: List<LabResult>): List<Pair<String, List<LabResult>>> {
        val buckets=Array(markers.size) { mutableListOf<LabResult>() }
        results.forEach { row ->
            val index=find(row)
            if (index>=0) buckets[index].add(row)
        }
        return markers.mapIndexed { index, marker ->
            marker.name to buckets[index].sortedWith(
                compareByDescending<LabResult> { BulkParser.dateKey(it.date) }
                    .thenBy { it.sourceKey }.thenBy { it.id })
        }
    }

    data class CultureEpisode(val key: String, val label: String,
        val date: String, val rows: List<LabResult>)
    fun cultureEpisodes(results: List<LabResult>): List<CultureEpisode> =
        results.asSequence().filter { ClinicalPresentation.isCulture(it.test) }
            .groupBy { listOf(it.test, it.date, it.labNumber.trim(),
                it.sampleNumber.trim(), it.reportStage.trim(), it.sourceOrderId.trim()) }
            .map { (parts, rows) ->
                CultureEpisode(
                    key=parts.joinToString("|"),
                    label=parts[0], date=parts[1], rows=rows
                )
            }.sortedWith(compareByDescending<CultureEpisode> {
                BulkParser.dateKey(it.date)
            }.thenBy { it.label }.thenBy { it.key })
}
