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
                .thenBy { it.reportId }
        )
    }

    fun isCulture(request: PreparedReportRequest): Boolean {
        val tags = request.row.optJSONArray("report_tags")?.toString().orEmpty()
        val text = listOf(
            tags,
            request.row.optString("report_type"),
            request.row.optString("report_name"),
            request.row.optString("department")
        ).joinToString(" ")
        return listOf("culture", "microbiology", "bacteriology", "fungal").any { text.contains(it, true) }
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

    private fun clinicalPriority(request: PreparedReportRequest): Int {
        if (isCulture(request)) return 0
        val text = listOf(
            request.row.optString("report_name"),
            request.row.optString("report_type"),
            request.row.optString("department")
        ).joinToString(" ").lowercase()
        return when {
            listOf("cbc", "hemogram", "haemogram", "crp", "esr", "procalcitonin").any(text::contains) -> 1
            listOf("renal", "kidney", "creatinine", "electrolyte", "liver", "hepatic").any(text::contains) -> 2
            listOf("urine", "cue", "urinalysis").any(text::contains) -> 3
            else -> 4
        }
    }
}
