package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.security.MessageDigest

object NimsRestReportListParser {
    private const val NIMS_ORIGIN = "https://www.nimsts.edu.in"

    private val reportNameKeys = listOf(
        "reportName", "report_name", "testName", "test_name",
        "investigationName", "investigation_name", "test", "investigation",
        "investigationNameFormatted", "testNameFormatted"
    )
    private val dateKeys = listOf(
        "reportDate", "report_date", "resultDate", "result_date",
        "dateSent", "date_sent", "sampleDate", "sample_date",
        "requisitionDate", "requisition_date", "date"
    )
    private val urlKeys = listOf(
        "reportUrl", "report_url", "pdfUrl", "pdf_url", "url",
        "downloadUrl", "download_url", "reportLink", "report_link"
    )
    private val tokenKeys = listOf(
        "fileName", "filename", "file_name", "reportToken", "report_token",
        "token", "reportFileName", "report_file_name", "pdfFileName", "pdf_file_name"
    )
    private val crKeys = listOf(
        "crNo", "cr_no", "crNumber", "cr_number", "patientCrNo", "patient_cr_no",
        "patCrNo", "pat_cr_no"
    )

    fun parse(jsonText: String, requestedCrNo: String): BackgroundReportList {
        val root = JSONTokener(jsonText).nextValue()
        val objects = mutableListOf<JSONObject>()
        collectObjects(root, objects)

        val rows = objects.mapNotNull(::toReportRow)
            .distinctBy { it.reportId }

        val identityObjects = objects.sortedByDescending(::identityScore)
        val returnedCrNo = identityObjects.asSequence()
            .map { first(it, crKeys).filter(Char::isDigit) }
            .firstOrNull { it.length == 15 }
            .orEmpty()

        if (returnedCrNo.isNotBlank() && returnedCrNo != requestedCrNo) {
            throw NimsIdentityMismatchException()
        }

        val patientName = identityObjects.asSequence()
            .map { first(it, listOf("patientName", "patient_name", "patName", "pat_name", "name")) }
            .firstOrNull(String::isNotBlank)
            .orEmpty()
        val age = identityObjects.asSequence()
            .map { first(it, listOf("age", "patientAge", "patient_age")) }
            .firstOrNull(String::isNotBlank)
            .orEmpty()
        val sex = identityObjects.asSequence()
            .map { first(it, listOf("sex", "gender", "patientSex", "patient_sex")) }
            .firstOrNull(String::isNotBlank)
            .orEmpty()

        return BackgroundReportList(
            patientName = patientName.take(100),
            returnedCrNo = returnedCrNo,
            age = age.take(40),
            sex = sex.take(20),
            rows = rows,
            source = "nims_rest_api"
        )
    }

    private fun toReportRow(obj: JSONObject): BackgroundReportRow? {
        val name = first(obj, reportNameKeys)
        val rawUrl = first(obj, urlKeys)
        val directUrl = safeNimsUrl(rawUrl)
        val token = safeToken(first(obj, tokenKeys)).ifBlank {
            if (directUrl.isBlank()) safeToken(rawUrl) else ""
        }
        val date = first(obj, dateKeys)
        val department = first(obj, listOf("department", "departmentName", "department_name", "labName", "lab_name", "section"))

        if (name.isBlank() && directUrl.isBlank() && token.isBlank()) return null
        if (directUrl.isBlank() && token.isBlank()) return null

        val title = name.ifBlank { "Investigation report" }.take(180)
        val material = listOf(
            first(obj, listOf("reportId", "report_id", "requisitionNo", "requisition_no", "sampleNo", "sample_no", "labNo", "lab_no")),
            token,
            directUrl,
            title,
            date
        ).firstOrNull(String::isNotBlank).orEmpty()

        return BackgroundReportRow(
            reportId = stableId(material),
            token = token,
            reportName = title,
            dateSent = date.take(80),
            reportType = inferType(title, department),
            directUrl = directUrl
        )
    }

    private fun collectObjects(value: Any?, out: MutableList<JSONObject>) {
        when (value) {
            is JSONObject -> {
                out += value
                val keys = value.keys()
                while (keys.hasNext()) collectObjects(value.opt(keys.next()), out)
            }
            is JSONArray -> {
                for (index in 0 until value.length()) collectObjects(value.opt(index), out)
            }
        }
    }

    private fun identityScore(obj: JSONObject): Int {
        var score = 0
        if (first(obj, crKeys).isNotBlank()) score += 4
        if (first(obj, listOf("patientName", "patient_name", "patName", "pat_name")).isNotBlank()) score += 3
        if (first(obj, listOf("age", "patientAge", "patient_age")).isNotBlank()) score += 1
        if (first(obj, listOf("sex", "gender", "patientSex", "patient_sex")).isNotBlank()) score += 1
        return score
    }

    private fun first(obj: JSONObject, names: List<String>): String {
        val existing = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            existing[key.lowercase()] = key
        }
        for (name in names) {
            val actual = existing[name.lowercase()] ?: continue
            val value = obj.opt(actual)
            if (value == null || value == JSONObject.NULL || value is JSONObject || value is JSONArray) continue
            val text = value.toString().replace(Regex("\\s+"), " ").trim()
            if (text.isNotBlank() && !text.equals("null", true)) return text
        }
        return ""
    }

    private fun safeToken(raw: String): String {
        val token = raw.trim()
        if (!NimsReportTemplate.isSafeTransientReportArg(token)) return ""
        return token
    }

    private fun safeNimsUrl(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        val resolved = runCatching {
            val candidate = URI(value)
            if (candidate.isAbsolute) candidate else URI(NIMS_ORIGIN).resolve(candidate)
        }.getOrNull() ?: return ""
        val url = resolved.toString()
        return if (NimsReportTemplate.isAllowedNimsUrl(url)) url else ""
    }

    private fun inferType(name: String, context: String): String {
        val text = "$name $context".lowercase()
        return when {
            listOf("culture", "bactec", "microbiology", "sensitivity", "gram stain", "fungal").any(text::contains) -> "culture"
            listOf("histopath", "biopsy", "cytology").any(text::contains) -> "pathology"
            else -> "lab"
        }
    }

    private fun stableId(material: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
