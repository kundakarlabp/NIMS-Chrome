package org.kundakarlab.nimsfastsummarymobile

import org.kundakarlab.nimsfastsummarymobile.data.processing.DateNormalizer

/** Pure queue optimizer: one deterministic, clinically prioritized, deduplicated run. */
object ReportRequestOptimizer {
    fun optimize(requests: List<PreparedReportRequest>): List<PreparedReportRequest> {
        val bestByEpisode = linkedMapOf<String, PreparedReportRequest>()
        requests.forEach { request ->
            val key = episodeKey(request)
            val existing = bestByEpisode[key]
            if (existing == null || stageRank(request) > stageRank(existing)) bestByEpisode[key] = request
        }
        return bestByEpisode.values.sortedWith(
            compareBy<PreparedReportRequest> { clinicalPriority(it) }
                .thenByDescending { DateNormalizer.normalize(it.row.optString("date_sent")).sortEpoch ?: Long.MIN_VALUE }
                .thenByDescending { stageRank(it) }
                .thenBy { it.reportId }
        )
    }

    fun isCulture(request: PreparedReportRequest): Boolean {
        val text = searchableText(request)
        return listOf("culture", "microbiology", "bacteriology", "fungal").any { text.contains(it) }
    }

    private fun episodeKey(request: PreparedReportRequest): String {
        val row = request.row
        val stableLabId = sequenceOf(
            "lab_study_number", "lab_no", "study_no", "culture_no", "requisition_no", "report_id"
        ).map { row.optString(it).trim() }.firstOrNull(String::isNotBlank)
        return if (stableLabId != null) {
            listOf(stableLabId, row.optString("date_sent")).joinToString("|").lowercase()
        } else {
            request.reportId
        }
    }

    private fun stageRank(request: PreparedReportRequest): Int {
        val text = listOf(
            request.row.optString("report_stage"),
            request.row.optString("report_name"),
            request.row.optString("status")
        ).joinToString(" ")
        return when {
            text.contains("final", true) -> 3
            text.contains("48", true) && text.contains("prelim", true) -> 2
            text.contains("prelim", true) || text.contains("interim", true) -> 1
            else -> 0
        }
    }

    /**
     * Make the first usable clinical view available before the full historical
     * queue completes. Positive/pending cultures cannot be known until parsing,
     * so all culture rows are fetched first, followed by core current labs,
     * molecular tests and then lower-yield historical reports.
     */
    private fun clinicalPriority(request: PreparedReportRequest): Int {
        val text = searchableText(request)
        return when {
            isCulture(request) -> 0
            listOf("cbc", "complete blood", "hemogram", "haemogram", "tlc", "platelet").any(text::contains) -> 1
            listOf("crp", "hscrp", "hs-crp", "esr", "procalcitonin", "galactomannan", "beta-d-glucan", "beta d glucan").any(text::contains) -> 2
            listOf("renal", "kidney", "creatinine", "urea", "electrolyte", "sodium", "potassium").any(text::contains) -> 3
            listOf("liver", "hepatic", "bilirubin", "sgot", "sgpt", "albumin", "alkaline phosphatase").any(text::contains) -> 4
            listOf("rbs", "random blood", "blood glucose", "glucose random", "grbs").any(text::contains) -> 5
            listOf("pcr", "viral load", "cbnaat", "gene xpert", "genexpert", "molecular").any(text::contains) -> 6
            listOf("urine", "cue", "urinalysis").any(text::contains) -> 7
            else -> 8
        }
    }

    private fun searchableText(request: PreparedReportRequest): String {
        val row = request.row
        return listOf(
            row.optJSONArray("report_tags")?.toString().orEmpty(),
            row.optString("report_type"),
            row.optString("report_name"),
            row.optString("department"),
            row.optString("test_name"),
            row.optString("sample_name")
        ).joinToString(" ").lowercase()
    }
}
