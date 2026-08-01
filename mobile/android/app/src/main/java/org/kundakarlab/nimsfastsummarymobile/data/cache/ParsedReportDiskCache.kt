package org.kundakarlab.nimsfastsummarymobile.data.cache

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.kundakarlab.nimsfastsummarymobile.domain.model.*
import java.io.File
import java.security.MessageDigest

class ParsedReportDiskCache(context: Context) {
    private val directory = File(context.cacheDir, "parsed_reports_v3").apply { mkdirs() }

    @Synchronized
    fun get(reportId: String): ParsedReport? {
        val file = fileFor(reportId)
        if (!file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt("parser_version") != PARSER_VERSION) {
                file.delete()
                return null
            }
            file.setLastModified(System.currentTimeMillis())
            ParsedReportJsonCodec.decode(root.getJSONObject("report"))
        }.getOrElse {
            file.delete()
            null
        }
    }

    @Synchronized
    fun put(report: ParsedReport) {
        prune()
        val target = fileFor(report.reportId)
        val temp = File(target.parentFile, target.name + ".tmp")
        val root = JSONObject()
            .put("parser_version", PARSER_VERSION)
            .put("report", ParsedReportJsonCodec.encode(report))
        temp.writeText(root.toString())
        if (!temp.renameTo(target)) {
            target.writeText(root.toString())
            temp.delete()
        }
    }

    @Synchronized
    fun clear() {
        directory.listFiles()?.forEach(File::delete)
    }

    private fun prune() {
        val files = directory.listFiles()?.filter(File::isFile).orEmpty()
        files.sortedByDescending(File::lastModified).drop(MAX_ENTRIES).forEach(File::delete)
    }

    private fun fileFor(reportId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(reportId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, "$digest.json")
    }

    companion object {
        const val PARSER_VERSION = 3
        private const val MAX_ENTRIES = 256
    }
}

object ParsedReportJsonCodec {
    fun encode(report: ParsedReport): JSONObject = JSONObject()
        .put("report_id", report.reportId)
        .put("report_name", report.reportName)
        .put("date_sent", report.dateSent)
        .put("report_type", report.reportType)
        .put("processor", report.processorName)
        .put("warnings", JSONArray(report.warnings))
        .put("labs", JSONArray().also { array -> report.labs.forEach { array.put(encodeLab(it)) } })
        .put("cultures", JSONArray().also { array -> report.cultures.forEach { array.put(encodeCulture(it)) } })

    fun decode(json: JSONObject): ParsedReport = ParsedReport(
        reportId = json.optString("report_id"),
        reportName = json.optString("report_name"),
        dateSent = json.optString("date_sent"),
        reportType = json.optString("report_type"),
        labs = json.optJSONArray("labs").objects().map(::decodeLab),
        cultures = json.optJSONArray("cultures").objects().map(::decodeCulture),
        warnings = json.optJSONArray("warnings").strings(),
        processorName = json.optString("processor", "disk-cache")
    )

    private fun encodeLab(value: ParsedLabValue): JSONObject = JSONObject()
        .put("code", value.canonicalCode)
        .put("name", value.displayName)
        .put("source", value.sourceName)
        .put("numeric", value.numericValue ?: JSONObject.NULL)
        .put("text", value.textValue ?: JSONObject.NULL)
        .put("unit", value.unit ?: JSONObject.NULL)
        .put("low", value.referenceLow ?: JSONObject.NULL)
        .put("high", value.referenceHigh ?: JSONObject.NULL)
        .put("range", value.referenceRangeText ?: JSONObject.NULL)
        .put("abnormality", value.abnormality.name)
        .put("date", value.resultDate ?: JSONObject.NULL)
        .put("confidence", value.confidence.name)
        .put("comparator", value.comparator.name)

    private fun decodeLab(json: JSONObject) = ParsedLabValue(
        canonicalCode = json.optString("code"),
        displayName = json.optString("name"),
        sourceName = json.optString("source"),
        numericValue = json.optNullableDouble("numeric"),
        textValue = json.optNullableString("text"),
        unit = json.optNullableString("unit"),
        referenceLow = json.optNullableDouble("low"),
        referenceHigh = json.optNullableDouble("high"),
        referenceRangeText = json.optNullableString("range"),
        abnormality = enumValueOr(json.optString("abnormality"), Abnormality.UNKNOWN),
        resultDate = json.optNullableString("date"),
        confidence = enumValueOr(json.optString("confidence"), ParseConfidence.MEDIUM),
        comparator = enumValueOr(json.optString("comparator"), NumericComparator.EQUAL)
    )

    private fun encodeCulture(value: ParsedCultureValue): JSONObject = JSONObject()
        .put("specimen", value.specimen ?: JSONObject.NULL)
        .put("site", value.site ?: JSONObject.NULL)
        .put("collection_date", value.collectionDate ?: JSONObject.NULL)
        .put("organism", value.organism ?: JSONObject.NULL)
        .put("growth_status", value.growthStatus.name)
        .put("markers", JSONArray(value.explicitResistanceMarkers.toList()))
        .put("comments", JSONArray(value.comments))
        .put("confidence", value.confidence.name)
        .put("lab_no", value.labStudyNumber ?: JSONObject.NULL)
        .put("reporting_date", value.reportingDate ?: JSONObject.NULL)
        .put("stage", value.reportStage ?: JSONObject.NULL)
        .put("bottle_name", value.bottleName ?: JSONObject.NULL)
        .put("set_number", value.setNumber ?: JSONObject.NULL)
        .put("bottle_number", value.bottleNumber ?: JSONObject.NULL)
        .put("isolate_number", value.isolateNumber ?: JSONObject.NULL)
        .put("gram", value.gramStain ?: JSONObject.NULL)
        .put("organism_raw", value.organismRaw ?: JSONObject.NULL)
        .put("susceptibility", JSONArray().also { array ->
            value.susceptibility.forEach { result ->
                array.put(JSONObject()
                    .put("antibiotic", result.antibiotic)
                    .put("interpretation", result.interpretation)
                    .put("confidence", result.confidence.name)
                    .put("mic", result.micValue ?: JSONObject.NULL)
                    .put("mic_comparator", result.micComparator ?: JSONObject.NULL)
                    .put("mic_unit", result.micUnit ?: JSONObject.NULL))
            }
        })

    private fun decodeCulture(json: JSONObject) = ParsedCultureValue(
        specimen = json.optNullableString("specimen"),
        site = json.optNullableString("site"),
        collectionDate = json.optNullableString("collection_date"),
        organism = json.optNullableString("organism"),
        growthStatus = enumValueOr(json.optString("growth_status"), GrowthStatus.UNKNOWN),
        susceptibility = json.optJSONArray("susceptibility").objects().map { result ->
            AntibioticResult(
                antibiotic = result.optString("antibiotic"),
                interpretation = result.optString("interpretation"),
                confidence = enumValueOr(result.optString("confidence"), ParseConfidence.MEDIUM),
                micValue = result.optNullableDouble("mic"),
                micComparator = result.optNullableString("mic_comparator"),
                micUnit = result.optNullableString("mic_unit")
            )
        },
        explicitResistanceMarkers = json.optJSONArray("markers").strings().toSet(),
        comments = json.optJSONArray("comments").strings(),
        confidence = enumValueOr(json.optString("confidence"), ParseConfidence.MEDIUM),
        labStudyNumber = json.optNullableString("lab_no"),
        reportingDate = json.optNullableString("reporting_date"),
        reportStage = json.optNullableString("stage"),
        bottleName = json.optNullableString("bottle_name"),
        setNumber = json.optNullableInt("set_number"),
        bottleNumber = json.optNullableInt("bottle_number"),
        isolateNumber = json.optNullableInt("isolate_number"),
        gramStain = json.optNullableString("gram"),
        organismRaw = json.optNullableString("organism_raw")
    )

    private inline fun <reified T : Enum<T>> enumValueOr(value: String, fallback: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(fallback)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull(::optJSONObject)

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf(String::isNotBlank) }

    private fun JSONObject.optNullableString(name: String): String? =
        takeUnless { isNull(name) }?.optString(name)?.takeIf(String::isNotBlank)

    private fun JSONObject.optNullableDouble(name: String): Double? =
        takeUnless { isNull(name) }?.optDouble(name)?.takeUnless(Double::isNaN)

    private fun JSONObject.optNullableInt(name: String): Int? =
        takeUnless { isNull(name) }?.optInt(name)
}
