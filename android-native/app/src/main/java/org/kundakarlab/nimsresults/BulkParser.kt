package org.kundakarlab.nimsresults

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.parser.Parser
import java.util.Locale

object BulkParser {
    private val crPattern = Regex("""\d{15}""")
    private val months = mapOf(
        "jan" to "01", "feb" to "02", "mar" to "03", "apr" to "04",
        "may" to "05", "jun" to "06", "jul" to "07", "aug" to "08",
        "sep" to "09", "oct" to "10", "nov" to "11", "dec" to "12"
    )

    fun parse(
        rawJson: String,
        expectedCr: String,
        patient: PatientInfo,
        reports: List<ReportRef>
    ): ClinicalBundle {
        require(crPattern.matches(expectedCr)) { "Enter the 15-digit NIMS CR number." }
        val root: Any = when {
            rawJson.trim().startsWith("[") -> JSONArray(rawJson)
            else -> JSONObject(rawJson)
        }
        val bulk = findBulk(root) ?: throw IllegalArgumentException("NIMS Custom Sheet did not return structured groups.")
        val status = bulk.opt("status")?.toString()?.trim()
        if (!status.isNullOrBlank() && status != "1") {
            throw IllegalArgumentException("NIMS Custom Sheet request failed.")
        }
        val returnedCr = bulk.optString("crNo").filter(Char::isDigit)
        if (returnedCr.isNotBlank() && returnedCr != expectedCr) {
            throw IllegalStateException("NIMS returned a different CR number. Results were rejected.")
        }

        val results = mutableListOf<LabResult>()
        val groups = bulk.optJSONArray("groups") ?: JSONArray()
        for (g in 0 until groups.length()) {
            val group = groups.optJSONObject(g) ?: continue
            val test = clean(group.optString("testName"))
            val rows = group.optJSONArray("rows") ?: JSONArray()
            for (r in 0 until rows.length()) {
                val row = rows.optJSONObject(r) ?: continue
                val parameter = clean(row.optString("parameterName"))
                val unit = clean(row.optString("unit"))
                val refRange = clean(row.optString("refRange"))
                // Only metadata explicitly supplied by NIMS may be labelled
                // specimen collection or reporting time. The chart/date key
                // itself is not necessarily the actual collection date.
                val values = row.optJSONObject("valuesByDate") ?: JSONObject()
                val flags = firstObject(row, "flagsByDate", "abnormalByDate", "flagByDate")
                val keys = values.keys()
                while (keys.hasNext()) {
                    val rawDate = keys.next()
                    val valueText = cleanValue(ResultTableFormats.format(values.opt(rawDate)?.toString().orEmpty()))
                    if (valueText.isBlank()) continue
                    val date = isoDate(rawDate)
                    // NIMS sometimes provides a separate metadata value per
                    // date. Never copy an entire JSON object into a lab number
                    // or timestamp, and never relabel the chart date as a
                    // collection or report timestamp.
                    val sent = metadataAt(row, group, rawDate,
                        "collectionDate", "collection_date", "sampleCollectionDate",
                        "sampleDate", "sample_date", "dateSent", "date_sent")
                    val ordered = metadataAt(row, group, rawDate,
                        "requisitionDate", "requisition_date", "orderDate", "order_date",
                        "orderedDate", "ordered_date")
                    val reported = metadataAt(row, group, rawDate,
                        "reportingDate", "reporting_date", "reportedDate", "reported_date",
                        "validationDate", "validation_date")
                    val lab = metadataAt(row, group, rawDate,
                        "labNo", "lab_no", "labNumber", "lab_number",
                        "labStudyNo", "labStudyNumber", "accessionNo", "accession_no")
                    val abnormal = clean(flags?.opt(rawDate)?.toString())
                        .ifBlank { clean(row.opt("abnormal")?.toString()) }
                        .ifBlank { clean(row.opt("flag")?.toString()) }
                    results += LabResult(
                        id = "nims-${results.size}",
                        test = test,
                        parameter = parameter,
                        date = date,
                        valueText = valueText,
                        numericValue = parseNumber(valueText),
                        unit = unit,
                        refRange = refRange,
                        abnormal = abnormal,
                        sourceKey = sourceKey(expectedCr, row, g, r, rawDate),
                        sentDate = sent,
                        reportedDate = reported,
                        labNumber = lab,
                        orderDate = ordered,
                        sourceOrderId = metadataAt(row, group, rawDate, "sourceOrderId", "orderId", "requisitionNo", "reqNo"),
                        sampleNumber = metadataAt(row, group, rawDate, "sampleNo", "sampleNumber", "specimenNo"),
                        reportNumber = metadataAt(row, group, rawDate, "reportNo", "reportNumber"),
                        reportStage = metadataAt(row, group, rawDate, "reportStage", "stage"),
                        documentReference = metadataAt(row, group, rawDate, "documentRef", "reportRef"),
                        packingListNumber = metadataAt(row, group, rawDate, "packingListNo", "packingListNumber"),
                        sourceRow = row.toString()
                    )
                }
            }
        }
        if (results.isEmpty()) throw IllegalArgumentException("NIMS Custom Sheet returned no result values.")

        val mergedPatient = patient.copy(
            crNo = expectedCr,
            name = patient.name.ifBlank { clean(bulk.optString("patientName")) }
        )
        return ClinicalBundle(
            patient = mergedPatient,
            results = results.sortedWith(compareByDescending<LabResult> { dateKey(it.date) }.thenBy { it.test }.thenBy { it.parameter }),
            reports = reports
        )
    }

    private fun findBulk(node: Any?, depth: Int = 0): JSONObject? {
        if (node == null || depth > 6) return null
        return when (node) {
            is JSONObject -> {
                if (node.optJSONArray("groups") != null) node
                else {
                    val keys = node.keys()
                    var found: JSONObject? = null
                    while (keys.hasNext() && found == null) found = findBulk(node.opt(keys.next()), depth + 1)
                    found
                }
            }
            is JSONArray -> {
                var found: JSONObject? = null
                var i = 0
                while (i < node.length() && found == null) {
                    found = findBulk(node.opt(i), depth + 1)
                    i += 1
                }
                found
            }
            else -> null
        }
    }

    private fun metadataAt(row: JSONObject, group: JSONObject, rawDate: String,
                           vararg keys: String): String {
        for (source in listOf(row, group)) {
            for (name in keys) {
                val raw = source.opt(name) ?: continue
                val text = when (raw) {
                    is JSONObject -> raw.optString(rawDate)
                        .ifBlank { raw.optString(isoDate(rawDate)) }
                    is JSONArray -> "" // Ambiguous; do not infer a date index.
                    else -> raw.toString()
                }.trim()
                if (text.isNotBlank() && !text.equals("null", true) &&
                    text != "-1" && text != "--") return text
            }
        }
        return ""
    }

    private fun firstObject(row: JSONObject, vararg names: String): JSONObject? {
        for (name in names) row.optJSONObject(name)?.let { return it }
        return null
    }

    fun isoDate(value: String): String {
        val text = value.trim()
        val match = Regex("""^(\d{1,2})-([A-Za-z]{3})-(\d{4})$""").matchEntire(text) ?: return text
        val month = months[match.groupValues[2].lowercase(Locale.US)] ?: return text
        return "${match.groupValues[3]}-$month-${match.groupValues[1].padStart(2, '0')}"
    }

    fun dateKey(value: String): Long {
        val parts = value.split("-")
        if (parts.size == 3 && parts[0].length == 4) {
            return (parts[0].toLongOrNull() ?: 0L) * 10000L +
                (parts[1].toLongOrNull() ?: 0L) * 100L +
                (parts[2].toLongOrNull() ?: 0L)
        }
        return 0L
    }

    private fun parseNumber(value: String): Double? {
        // A number inside a culture narrative, detection limit or comment is
        // not a quantitative measurement. Preserve text but exclude it from trends.
        val text = value.trim().replace(",", "")
        return text.takeIf {
            Regex("""[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?""").matches(it)
        }?.toDoubleOrNull()
    }

    // Amended values must retain their event identity; preserve repeated specimens.
    // A source ordinal is scoped to the patient and exact source table layout.
    private fun sourceKey(cr: String, row: JSONObject, groupIndex: Int,
                          rowIndex: Int, rawDate: String): String {
        val eventId = listOf("observationId", "resultId", "eventId")
            .firstNotNullOfOrNull { key ->
                row.optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
            }
        return if (eventId != null) listOf("nims_bulk_api", cr, "event", eventId, rawDate).joinToString("|")
        else listOf("nims_bulk_api", cr, "group", groupIndex.toString(),
            "row", rowIndex.toString(), "date", rawDate).joinToString("|")
    }

    private fun canonical(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "")
    private fun clean(value: String?): String = value.orEmpty().replace(Regex("\\s+"), " ").trim()

    // HTML entities are decoded, but clinical comparison signs like <1.0,
    // <=0.5 and < 6 must remain literal. Only known markup tags are removed.
    // NEVER feed values through a generic HTML sanitizer that eats "<" notes.
    private fun cleanValue(value: String?): String {
        val raw = value.orEmpty()
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)</(?:p|div|tr|li|table|ul|ol)>"""), "\n")
            .replace(Regex("""(?i)<(?:td|th)[^>]*>"""), " | ")
            .replace(Regex("""(?i)</(?:td|th)>"""), " ")
            .replace(Regex("""(?i)</?(?:b|strong|i|em|span|p|div|tr|table|ul|ol|li)(?:\s+[^>]*)?>"""), " ")
        return Parser.unescapeEntities(raw, false)
            // Some HIS output has malformed literal 'lt;1.0' or double-encoded
            // '&amp;lt;1.0' instead of a proper less-than character.
            .replace(Regex("(?i)&lt;(?=[=\\d])"), "<")
            .replace(Regex("(?i)(?<![a-z0-9&])lt;\\s*(?=[=\\d])"), "<")
            .replace(Regex("(?i)(?<![a-z0-9&])gt;\\s*(?=[=\\d])"), ">")
            .replace('\u00a0', ' ')
            .replace("\r\n", "\n")
            .split("\n")
            .joinToString("\n") { line -> line.replace(Regex("[ \\t]+"), " ").trim() }
            .trim()
    }

}


// Keep domain classification independent of UI so it can be unit tested.
object ClinicalResultRules {
    private fun key(value: String) = value.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")
    private val allowed = setOf(
        "wbc", "tlc", "totalleucocytecount", "totalleukocytecount",
        "whitebloodcellcount", "absoluteneutrophilcount", "anc",
        "hemoglobin", "haemoglobin", "hemoglobinresult", "haemoglobinresult", "hb", "hgb",
        "platelets", "plateletcount", "plateletcounts",
        "serumcreatinine", "creatinine", "bloodurea", "serumurea", "urea", "bun",
        "sodium", "serumsodium", "potassium", "serumpotassium",
        "serumchloride", "chloride", "bicarbonate", "serumbicarbonate",
        "crp", "creactiveprotein", "hscrp", "highsensitivitycrp",
        "highsensitivitycreactiveprotein", "procalcitonin", "pct", "esr",
        "erythrocytesedimentationrate",
        "glucose", "bloodglucose", "randomglucose", "fastingglucose", "hba1c",
        "glycosylatedhba1c", "glycatedhemoglobin", "glycatedhaemoglobin",
        "sgot", "ast", "sgpt", "alt", "alp", "alkalinephosphatase",
        "totalbilirubin", "directbilirubin", "indirectbilirubin", "serumalbumin",
        "albumin", "totalprotein", "lactate", "serumlactate",
        "ldh", "ferritin", "inr", "ptinr", "ph", "paco2", "pao2",
        "neutrophils", "lymphocytes", "monocytes", "eosinophils",
        "neutrophilpercentage", "lymphocytepercentage", "mcv", "mch", "mchc",
        "rbc", "redbloodcellcount", "hematocrit", "haematocrit", "pcv"
    )
    // Technical source codes can look numerical; never treat them as labs.
    fun isAdministrativeParameter(parameter: String): Boolean =
        Regex(
            """(?i)^(?:sample\s*processed|test\s*done\s*by|machine\s*(?:id|no)|processed\s*by|technician)\s*:?$"""
        ).matches(parameter.trim())

    fun isNarrativeOrQualitative(row: LabResult): Boolean =
        row.numericValue == null || isAdministrativeParameter(row.parameter)

    fun isSerology(row: LabResult): Boolean {
        val test = (row.test + " " + row.parameter).lowercase(Locale.US)
        return Regex(
            """serology|dengue|ns1|igm|igg|hiv|hbsag|hepatitis|anti.hcv|scrub\s*typhus|leptospir|widal|brucella|rpr|vdrl|elisa|immunoassay|antibody|antigen"""
        ).containsMatchIn(test)
    }

    fun isTrendEligible(row: LabResult): Boolean {
        val param = key(row.parameter)
        return param in allowed && row.numericValue?.isFinite() == true &&
            BulkParser.dateKey(row.date) > 0L
    }
    fun parameterKey(row: LabResult): String =
        key(row.parameter) + "|" + key(row.unit)
    fun inWindow(date: String, latest: String, days: Long?): Boolean {
        if (days == null) return true
        return runCatching {
            val delta = java.time.temporal.ChronoUnit.DAYS.between(
                java.time.LocalDate.parse(date), java.time.LocalDate.parse(latest))
            delta in 0..days
        }.getOrDefault(false)
    }
}

data class CultureSections(
    val culture: String,
    val susceptible: String,
    val resistant: String,
    val advice: String,
    val intermediate: String = ""
)

object CultureNarrative {
    // Only section words in an actual laboratory report act as delimiters.
    // Values are copied verbatim; no organism, MIC, or S/I/R is inferred.
    private val headings = Regex(
        """(?i)\b(CULTURE\s+REPORT|SENSITIVITY\s+REPORT|SUSCEPTIBILITY\s+REPORT|RESISTANCE\s+REPORT|INTERMEDIATE\s+REPORT|ADVICE\s*(?::|TO\b)|COMMENTS\s*:|REMARKS\s*:|NOTE\s*:|NOTES\s*:|INTERPRETATION\s*:)"""
    )
    fun parse(text: String): CultureSections {
        val matches = headings.findAll(text).toList()
        if (matches.isEmpty()) return CultureSections(text.trim(), "", "", "")
        var culture = text.substring(0, matches.first().range.first).trim()
        var sensitivity = ""
        var resistance = ""
        var advice = ""
        var intermediate = ""
        matches.forEachIndexed { i, match ->
            val end = matches.getOrNull(i + 1)?.range?.first ?: text.length
            val value = text.substring(match.range.last + 1, end).trim(' ', ':', '-', '\n')
            when {
                match.value.contains("SENSITIVITY", true) ||
                    match.value.contains("SUSCEPTIBILITY", true) -> sensitivity =
                    listOf(sensitivity, value).filter { it.isNotBlank() }.joinToString("\n")
                match.value.contains("RESISTANCE", true) -> resistance =
                    listOf(resistance, value).filter { it.isNotBlank() }.joinToString("\n")
                match.value.contains("INTERMEDIATE", true) -> intermediate =
                    listOf(intermediate, value).filter { it.isNotBlank() }.joinToString("\n")
                match.value.startsWith("ADVICE", true) ||
                    match.value.startsWith("COMMENTS", true) ||
                    match.value.startsWith("REMARKS", true) ||
                    match.value.startsWith("NOTE", true) ||
                    match.value.startsWith("INTERPRETATION", true) -> advice =
                    listOf(advice, value).filter { it.isNotBlank() }.joinToString("\n")
                else -> culture = listOf(culture, value).filter { it.isNotBlank() }.joinToString("\n")
            }
        }
        return CultureSections(culture, sensitivity, resistance, advice, intermediate)
    }
}



// Report classification is conservative. A positive organism name in an
// administrative note is not sufficient proof of culture growth.
enum class CultureStatus { POSITIVE, NEGATIVE, PENDING, OTHER }

object CultureReview {
    fun status(rows: List<LabResult>): CultureStatus {
        val narratives = rows.filter {
            val p = it.parameter.trim().trimEnd(':')
            p.equals("Result", true) ||
                p.equals("Aerobic Culture", true) ||
                p.equals("Fungal Culture", true) ||
                p.contains("organism", true)
        }.map { it.valueText }.filter { it.isNotBlank() }
        if (narratives.isEmpty()) return CultureStatus.OTHER
        val text = narratives.joinToString(" ").lowercase(Locale.US)
        if (Regex("""\b(no\s+growth|culture\s+sterile|no\s+organism\s+isolated)\b""").containsMatchIn(text)) {
            return CultureStatus.NEGATIVE
        }
        if (Regex("""\b(culture\s+shows?\s+growth|growth\s+of|organism\s+isolated|isolate\s+identified|culture\s+positive)\b""").containsMatchIn(text)) {
            return CultureStatus.POSITIVE
        }
        if (Regex("""\b(pending|preliminary|interim|under\s+incubation|final\s+report\s+awaited)\b""").containsMatchIn(text)) {
            return CultureStatus.PENDING
        }
        return CultureStatus.OTHER
    }
}
