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
    val dateSent: String,
    val reportName: String,
    val type: String,
    val status: String,
    val notes: String,
    val hasError: Boolean
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
    val collectionDate: String,
    val cultureNo: String,
    val specimen: String,
    val site: String,
    val organism: String,
    val growth: String,
    val status: String,
    val sensitivitySummary: String,
    val comment: String,
    val sourceReportName: String = ""
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
    val dateRange: String
        get() {
            val dates = sourceReports.map { it.dateSent }.filter { it.isNotBlank() }.distinct()
            return when {
                dates.isEmpty() -> "No dates"
                dates.size == 1 -> dates.first()
                else -> "${dates.last()} to ${dates.first()}"
            }
        }
}
