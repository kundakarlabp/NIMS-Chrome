package org.kundakarlab.nimsfastsummarymobile.domain.model

import org.json.JSONArray
import org.json.JSONObject

enum class ProcessingMode { AUTO, LOCAL_ONLY, REMOTE_ONLY }
enum class ProcessingCapability { HTML, PLAIN_TEXT, PDF, LABS, CULTURES, SUMMARY }
enum class SummaryMode { FAST, CULTURES_ONLY, FULL }
enum class ParseConfidence { HIGH, MEDIUM, LOW }
enum class Abnormality { NORMAL, HIGH, LOW, CRITICAL, UNKNOWN }
enum class GrowthStatus { NO_GROWTH, GROWTH_DETECTED, PENDING, UNKNOWN }
enum class NumericComparator { LESS_THAN, GREATER_THAN, EQUAL }

data class ReportInput(
    val reportId: String,
    val reportName: String,
    val dateSent: String,
    val reportType: String,
    val contentType: String,
    val bytes: ByteArray,
    val safeSource: String = ""
)

data class ParsedLabValue(
    val canonicalCode: String,
    val displayName: String,
    val sourceName: String,
    val numericValue: Double?,
    val textValue: String?,
    val unit: String?,
    val referenceLow: Double?,
    val referenceHigh: Double?,
    val referenceRangeText: String? = null,
    val abnormality: Abnormality,
    val resultDate: String?,
    val confidence: ParseConfidence,
    val comparator: NumericComparator = NumericComparator.EQUAL
)

data class AntibioticResult(
    val antibiotic: String,
    val interpretation: String,
    val confidence: ParseConfidence
)

data class ParsedCultureValue(
    val specimen: String?,
    val site: String?,
    val collectionDate: String?,
    val organism: String?,
    val growthStatus: GrowthStatus,
    val susceptibility: List<AntibioticResult>,
    val explicitResistanceMarkers: Set<String>,
    val comments: List<String>,
    val confidence: ParseConfidence
)

data class ParsedReport(
    val reportId: String,
    val reportName: String,
    val dateSent: String,
    val reportType: String,
    val labs: List<ParsedLabValue> = emptyList(),
    val cultures: List<ParsedCultureValue> = emptyList(),
    val warnings: List<String> = emptyList(),
    val processorName: String
) {
    fun toHelperJson(): JSONObject = JSONObject()
        .put("report_id", reportId)
        .put("report_name", reportName)
        .put("date_sent", dateSent)
        .put("report_type", reportType.ifBlank { if (cultures.isNotEmpty()) "culture" else "lab" })
        .put("report_tags", JSONArray().put(if (cultures.isNotEmpty()) "culture" else "lab"))
        .put("parameters", JSONArray().also { array -> labs.forEach { lab -> array.put(lab.toJson()) } })
        .put("culture_results", JSONArray().also { array -> cultures.forEach { culture -> array.put(culture.toJson()) } })
        .put("errors", JSONArray())
        .put("processing", JSONObject().put("processor", processorName))
}

data class ProcessingSummary(
    val text: String,
    val reportsProcessed: Int,
    val warnings: List<String> = emptyList(),
    val helperJson: JSONObject? = null
)

private fun ParsedLabValue.toJson(): JSONObject = JSONObject()
    .put("name", displayName)
    .put("canonical_name", canonicalCode)
    .put("source_name", sourceName)
    .put("value", numericValue ?: textValue.orEmpty())
    .put("unit", unit.orEmpty())
    .put("date", resultDate.orEmpty())
    .put("reference_range", referenceRangeText.orEmpty())
    .put("abnormality", abnormality.name.lowercase())
    .put("confidence", confidence.name.lowercase())

private fun ParsedCultureValue.toJson(): JSONObject = JSONObject()
    .put("specimen", specimen.orEmpty())
    .put("site", site.orEmpty())
    .put("collection_date", collectionDate.orEmpty())
    .put("organism", organism.orEmpty())
    .put("status", growthStatus.name.lowercase())
    .put("susceptibility", JSONArray().also { array -> susceptibility.forEach { array.put(JSONObject().put("antibiotic", it.antibiotic).put("interpretation", it.interpretation)) } })
    .put("explicit_resistance_markers", JSONArray(explicitResistanceMarkers.toList()))
    .put("comments", JSONArray(comments))
    .put("confidence", confidence.name.lowercase())
