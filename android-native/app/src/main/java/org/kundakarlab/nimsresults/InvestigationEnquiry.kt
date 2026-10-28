package org.kundakarlab.nimsresults

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * NIMS Enquiry order ledger. This is NOT a result, a specimen accession,
 * a PDF or a claim that a Custom Sheet result belongs to this order.
 * Every identified field retains its original HTML column and raw value.
 */
data class EnquiryOrder(
    val testName: String,
    val status: String,
    val sampleNumber: String = "",
    val labNumber: String = "",
    val requestDateTime: String = "",
    val requisitionNumber: String = "",
    val packingListNumber: String = "",
    val packingListDate: String = "",
    val billNumber: String = "",
    val department: String = "",
    val collectionDateTime: String = "",
    val sourceFieldsJson: String = "{}"
)

data class EnquiryCapture(
    val rows: List<EnquiryOrder>,
    val capturedAt: Long = System.currentTimeMillis(),
    val source: String = "nims_authenticated_manual_cr_wise_enquiry"
)

/** Extracts only a positively identified CR-wise Enquiry table.
 * Rejections are preferable to cross-patient or invented lab-number matches.
 */
object InvestigationEnquiryParser {
    private val crRegex = Regex("""\b\d{15}\b""")
    private val headerCrRegex = Regex("""(?i)\b(?:CR\s*(?:No\.?|Number)?|Pat(?:ient)?\s*CR\s*(?:No\.?)?)\s*[:#=\-]?\s*(\d{15})\b""")
    private fun clean(s: String) = s.replace(Regex("[\\s\\u00a0]+"), " ").trim()
    private fun header(s: String) = clean(s).lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]"), "")

    private fun field(headers: List<String>, cells: List<String>, vararg names: String): String {
        val aliases = names.toSet()
        val i = headers.indexOfFirst { it in aliases }
        return if (i in cells.indices) cells[i] else ""
    }

    /** CR must appear in a rendered result heading or row, not just a
     * hidden/search input. Detect any conflicting visible CR before parsing.
     */
    fun parseHtml(html: String, expectedCr: String): EnquiryCapture {
        require(Regex("""\d{15}""").matches(expectedCr))
        require(html.length <= 900_000) { "Enquiry page exceeds local capture limit." }
        val doc = Jsoup.parse(html)
        doc.select("script,style,template,input,select,textarea").remove()
        val visible = clean(doc.body()?.text().orEmpty())
        val declared = headerCrRegex.findAll(visible).map { it.groupValues[1] }.toSet()
        if (declared.isEmpty() || expectedCr !in declared || declared.any { it != expectedCr })
            throw IdentityMismatchException("The visible NIMS Enquiry result does not verify the selected patient CR.")
        val tables = doc.select("table")
        val results = mutableListOf<EnquiryOrder>()
        for (table in tables) {
            val tr = table.select("tr")
            var headerIndex = -1
            var heads: List<String> = emptyList()
            for ((i, row) in tr.withIndex()) {
                val columns = row.select("th,td").map { header(it.text()) }
                val hasTest = columns.any { it in setOf("testname","investigationname","test","investigation") }
                val hasStatus = columns.any { it in setOf("status","investigationstatus","teststatus","reportstatus") }
                val hasIdentity = columns.any { it in setOf("sampleno","samplenumber","labno","labnumber","requestdatetime","requestdate","requisitiondate","packinglistno","department") }
                if (hasTest && hasStatus && hasIdentity) {
                    headerIndex = i
                    heads = columns
                    break
                }
            }
            if (headerIndex < 0) continue
            for (row in tr.drop(headerIndex + 1)) {
                val cells = row.select("td").map { clean(it.text()) }
                if (cells.isEmpty() || cells.size < heads.size.coerceAtMost(3)) continue
                val test = field(heads,cells,"testname","investigationname","test","investigation")
                val status = field(heads,cells,"status","investigationstatus","teststatus","reportstatus")
                if (test.isBlank() && status.isBlank()) continue
                if (header(test) in setOf("testname","investigationname")) continue
                if (cells.any { crRegex.find(it)?.value?.let { v -> v != expectedCr } == true })
                    throw IdentityMismatchException("Enquiry contains a row associated with a different patient.")
                val mapped = JSONObject()
                heads.forEachIndexed { index, name ->
                    mapped.put(name.ifBlank { "column$index" }, cells.getOrElse(index) { "" })
                }
                results += EnquiryOrder(
                    testName=test, status=status,
                    sampleNumber=field(heads,cells,"sampleno","samplenumber","specimenno","specimennumber"),
                    labNumber=field(heads,cells,"labno","labnumber","labstudyno","labstudynumber"),
                    requestDateTime=field(heads,cells,"requestdatetime","requestdate","requisitiondatetime","requisitiondate","orderdatetime","orderdate"),
                    requisitionNumber=field(heads,cells,"requisitionno","requisitionnumber","reqno","orderno","orderid"),
                    packingListNumber=field(heads,cells,"packinglistno","packinglistnumber"),
                    packingListDate=field(heads,cells,"packinglistdate","packingdate"),
                    billNumber=field(heads,cells,"billno","billnumber"),
                    department=field(heads,cells,"department","departmentname","labname","laboratory"),
                    collectionDateTime=field(heads,cells,"collectiondatetime","collectiondate","samplecollectiondate"),
                    sourceFieldsJson=mapped.toString()
                )
            }
        }
        require(results.isNotEmpty()) {
            "No verified CR-wise Enquiry table was found. Navigate to CR-wise Enquiry, search the CR, and import the displayed result table."
        }
        require(results.size <= 3000)
        return EnquiryCapture(results)
    }

    fun toJson(capture: EnquiryCapture): String = JSONObject().apply {
        put("capturedAt",capture.capturedAt)
        put("source",capture.source)
        put("rows",JSONArray().apply { capture.rows.forEach { row ->
            put(JSONObject().apply {
                put("testName",row.testName);put("status",row.status)
                put("sampleNumber",row.sampleNumber);put("labNumber",row.labNumber)
                put("requestDateTime",row.requestDateTime)
                put("requisitionNumber",row.requisitionNumber)
                put("packingListNumber",row.packingListNumber)
                put("packingListDate",row.packingListDate)
                put("billNumber",row.billNumber)
                put("department",row.department)
                put("collectionDateTime",row.collectionDateTime)
                put("sourceFieldsJson",row.sourceFieldsJson)
            })
        } })
    }.toString()

    fun fromJson(value: String): EnquiryCapture {
        val root=JSONObject(value)
        val rows=root.getJSONArray("rows")
        require(rows.length() in 1..3000)
        return EnquiryCapture((0 until rows.length()).map { i ->
            val j=rows.getJSONObject(i)
            fun f(k:String)=j.optString(k)
            EnquiryOrder(f("testName"),f("status"),f("sampleNumber"),f("labNumber"),
                f("requestDateTime"),f("requisitionNumber"),f("packingListNumber"),
                f("packingListDate"),f("billNumber"),f("department"),
                f("collectionDateTime"),f("sourceFieldsJson"))
        },root.optLong("capturedAt"),root.optString("source"))
    }

    /** No identifiable patient data, values, cookies, tokens, or URL. */
    fun schemaSummary(html: String): String {
        val doc = Jsoup.parse(html.take(900_000))
        val tables=doc.select("table")
        return buildString {
            appendLine("enquiry.schema=v1")
            appendLine("enquiry.tableCount=${tables.size}")
            appendLine("enquiry.tableRows=${tables.sumOf { it.select("tr").size }}")
            appendLine("enquiry.headerLabels=" + tables.flatMap { t ->
                t.select("tr").take(2).flatMap { r ->
                    r.select("th,td").map { col -> header(col.text()) }
                }
            }.distinct().filter { it.length in 3..40 &&
                    it in setOf("testname","investigationname","status",
                        "sampleno","labno","department","requestdate",
                        "requestdatetime","packinglistno","billno","collectiondate",
                        "requisitionno") }.take(20).joinToString(","))
            appendLine("enquiry.schemaPrivacy=no_cr_no_names_no_values_no_url")
        }
    }
}
