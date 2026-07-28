package org.kundakarlab.nimsfastsummarymobile.ui.models

import org.json.JSONObject

enum class Abnormality {
    HIGH,
    LOW,
    CRITICAL,
    NORMAL,
    UNKNOWN
}

data class UiSourceReport(
    val sourceKey: String,
    val dateSent: String,
    val reportName: String,
    val type: String,
    val status: String,
    val notes: String,
    val hasError: Boolean,
    val results: List<UiReportResult> = emptyList(),
    val cultureCount: Int = 0
)

data class UiReportResult(
    val name: String,
    val value: String,
    val unit: String,
    val referenceRange: String,
    val abnormality: Abnormality,
    val confidence: String
)

data class UiLabTrendRow(
    val parameter: String,
    val latestValue: String,
    val latestDate: String,
    val previousValue: String?,
    val previousDate: String?,
    val trendText: String,
    val abnormality: Abnormality,
    val history: List<Pair<String, String>>
)

data class UiCultureRow(
    val sourceKey: String = "",
    val collectionDate: String,
    val cultureNo: String,
    val specimen: String,
    val site: String,
    val organism: String,
    val growth: String,
    val status: String,
    val sensitivitySummary: String,
    val comment: String,
    val sourceReportName: String = "",
    val reportingDate: String = "",
    val reportStage: String = "",
    val bottleName: String = "",
    val setNumber: Int? = null,
    val bottleNumber: Int? = null,
    val isolateNumber: Int? = null,
    val gramStain: String = "",
    val timeline: List<String> = emptyList(),
    val confidence: String = "unknown",
    val antibiogramCompleteness: String = "unavailable"
)

data class UiSummary(
    val sourceReports: List<UiSourceReport> = emptyList(),
    val labTrends: List<UiLabTrendRow> = emptyList(),
    val cultures: List<UiCultureRow> = emptyList(),
    val interpretation: List<String> = emptyList(),
    val editableNote: String = "",
    val rawJson: JSONObject = JSONObject()
) {
    val failedReportCount: Int get() = sourceReports.count { it.hasError }
    val parsedReportCount: Int get() = sourceReports.count { !it.hasError }
    val positiveCultureCount: Int get() = cultures.count { it.status == "growth_detected" }
    val pendingCultureCount: Int get() = cultures.count { it.status == "pending" }
    val noGrowthCultureCount: Int get() = cultures.count { it.status == "no_growth" }
    val reviewCultureCount: Int get() = cultures.count { it.status !in setOf("growth_detected", "pending", "no_growth") }
    val dateRange: String
        get() {
            val dates = sourceReports.mapNotNull { report ->
                org.kundakarlab.nimsfastsummarymobile.data.processing.DateNormalizer
                    .normalize(report.dateSent).sortEpoch?.let { it to report.dateSent }
            }.distinctBy { it.first }.sortedBy { it.first }.map { it.second }
            return when {
                dates.isEmpty() -> "No dates"
                dates.size == 1 -> dates.first()
                else -> "${dates.first()} to ${dates.last()}"
            }
        }
}
