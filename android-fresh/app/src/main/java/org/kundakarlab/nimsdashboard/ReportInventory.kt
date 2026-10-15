package org.kundakarlab.nimsdashboard

import android.webkit.CookieManager
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.concurrent.TimeUnit

data class ReportRef(
    val id: String,
    val title: String,
    val date: String = "",
    val department: String = "",
    val url: String = ""
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("date", date)
        .put("department", department)
        .put("url", url)
}

data class InventoryResult(
    val reports: List<ReportRef>,
    val source: String,
    val linkedCount: Int
)

class NimsAuthRequired(message: String = "NIMS authentication required.") : IllegalStateException(message)
class NimsIdentityMismatch(message: String) : IllegalStateException(message)

class ReportInventory(
    private val cookies: CookieManager = CookieManager.getInstance(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    fun load(crNo: String): InventoryResult {
        require(CR_PATTERN.matches(crNo)) { "Enter the 15-digit NIMS CR number." }

        var restVerified = false
        val rest = try {
            val result = fetchRest(crNo)
            restVerified = result.second
            result.first
        } catch (mismatch: NimsIdentityMismatch) {
            throw mismatch
        } catch (_: Throwable) {
            emptyList()
        }

        val browser = try {
            fetchCrReportList(crNo)
        } catch (mismatch: NimsIdentityMismatch) {
            throw mismatch
        } catch (error: Throwable) {
            if (restVerified && rest.isNotEmpty() && rest.all { it.url.isNotBlank() }) {
                return InventoryResult(rest, "report_list_rest", rest.count { it.url.isNotBlank() })
            }
            if (error is NimsAuthRequired) throw error
            emptyList()
        }

        val merged = merge(rest, browser)
        val source = when {
            browser.isNotEmpty() -> "authenticated_report_inventory"
            rest.isNotEmpty() -> "report_list_rest"
            else -> "report_inventory_unavailable"
        }
        return InventoryResult(merged, source, merged.count { it.url.isNotBlank() })
    }

    private fun fetchRest(crNo: String): Pair<List<ReportRef>, Boolean> {
        val url = REPORT_LIST_API.toHttpUrl().newBuilder()
            .addQueryParameter("crNo", crNo)
            .addQueryParameter("hosCode", HOSPITAL_CODE)
            .build()
        val request = requestBuilder(url.toString())
            .get()
            .header("Accept", "application/json,text/plain,*/*")
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            syncCookies(response.request.url.toString(), response.headers("Set-Cookie"))
            if (response.code == 401 || response.code == 403 || looksLikeLogin(text, response.request.url.toString())) {
                throw NimsAuthRequired()
            }
            if (!response.isSuccessful) error("NIMS report-list API returned ${response.code}.")
            val payload: Any = when {
                text.trim().startsWith("[") -> JSONArray(text)
                else -> JSONObject(text)
            }
            return ReportInventoryParser.parseRest(payload, crNo)
        }
    }

    private fun fetchCrReportList(crNo: String): List<ReportRef> {
        val initial = client.newCall(
            requestBuilder(CR_RESULTS_URL).get()
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .build()
        ).execute().use { response ->
            val body = response.body?.string().orEmpty()
            syncCookies(response.request.url.toString(), response.headers("Set-Cookie"))
            if (response.code == 401 || response.code == 403 || looksLikeLogin(body, response.request.url.toString())) {
                throw NimsAuthRequired()
            }
            if (!response.isSuccessful) error("NIMS CR form returned ${response.code}.")
            body
        }

        val document = Jsoup.parse(initial, CR_RESULTS_URL)
        val form = document.selectFirst("form[name=viewExternalInvFB], form#viewExternalInvFB, form[action*=viewcrnowisereportprocess]")
        val postUrl = form?.absUrl("action")?.takeIf { it.isNotBlank() } ?: CR_RESULTS_URL
        val builder = FormBody.Builder()
        form?.select("input[type=hidden][name]")?.forEach { input ->
            val name = input.attr("name").trim()
            if (name.isNotBlank() && !name.equals("hmode", true) && !name.equals("patCrNo", true)) {
                builder.add(name, input.attr("value"))
            }
        }
        builder.add("hmode", "SHOWPATDETAILS")
        builder.add("patCrNo", crNo)

        val request = requestBuilder(postUrl)
            .post(builder.build())
            .header("Accept", "text/html,application/xhtml+xml,*/*")
            .header("Origin", NIMS_ORIGIN)
            .header("Referer", CR_RESULTS_URL)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            syncCookies(response.request.url.toString(), response.headers("Set-Cookie"))
            if (response.code == 401 || response.code == 403 || looksLikeLogin(body, response.request.url.toString())) {
                throw NimsAuthRequired()
            }
            if (!response.isSuccessful) error("NIMS CR report request returned ${response.code}.")
            return ReportInventoryParser.parseHtml(body, response.request.url.toString(), crNo)
        }
    }

    private fun requestBuilder(url: String): Request.Builder {
        val cookie = cookies.getCookie(url).orEmpty()
        return Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
    }

    private fun syncCookies(url: String, values: List<String>) {
        values.forEach { value -> runCatching { cookies.setCookie(url, value) } }
        runCatching { cookies.flush() }
    }

    private fun looksLikeLogin(body: String, url: String): Boolean {
        val lower = (url + " " + body.take(12000)).lowercase()
        return "loginlogin.action" in lower ||
            "session expired" in lower ||
            "please login again" in lower ||
            ("captcha" in lower && ("password" in lower || "user id" in lower || "username" in lower))
    }

    private fun merge(first: List<ReportRef>, second: List<ReportRef>): List<ReportRef> {
        val merged = LinkedHashMap<String, ReportRef>()
        (first + second).forEach { report ->
            val key = canonical(report)
            val previous = merged[key]
            if (previous == null) {
                merged[key] = report
            } else {
                merged[key] = previous.copy(
                    id = previous.id.ifBlank { report.id },
                    title = previous.title.ifBlank { report.title },
                    date = previous.date.ifBlank { report.date },
                    department = previous.department.ifBlank { report.department },
                    url = previous.url.ifBlank { report.url }
                )
            }
        }
        return merged.values.toList()
    }

    private fun canonical(report: ReportRef): String =
        (normalize(report.title) + "|" + report.date.trim().lowercase()).ifBlank { report.id }

    private fun normalize(value: String): String = value.lowercase().replace(Regex("[^a-z0-9]+"), "")

    companion object {
        private val CR_PATTERN = Regex("""\d{15}""")
        const val NIMS_ORIGIN = "https://www.nimsts.edu.in"
        const val CR_RESULTS_URL = "$NIMS_ORIGIN/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt"
        const val REPORT_LIST_API = "https://nimsts.edu.in/HBIMS/services/restful/invService/reportList"
        const val REPORT_PATH = "/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt"
        private const val HOSPITAL_CODE = "33101"
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}

object ReportInventoryParser {
    private val dateRegex = Regex("""\b\d{1,2}[-/][A-Za-z]{3}[-/]\d{2,4}\b|\b\d{1,2}[-/]\d{1,2}[-/]\d{2,4}\b|\b\d{4}-\d{1,2}-\d{1,2}\b""")
    private val crRegex = Regex("""(?i)(?:CR\s*(?:No|Number)?|patCrNo)\s*[:=\-]?\s*(\d{15})""")
    private val printRegex = Regex("""(?i)printReport\s*\(\s*['"]([^'"]+)['"]\s*\)""")

    fun parseRest(payload: Any, requestedCrNo: String): Pair<List<ReportRef>, Boolean> {
        val arrays = mutableListOf<JSONArray>()
        collectArrays(payload, 0, arrays)
        val rows = arrays.maxByOrNull(::scoreArray) ?: JSONArray()

        val rowObjects = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
        val reports = rowObjects.mapIndexedNotNull { index, row -> normalizeRestRow(row, index) }

        val root = payload as? JSONObject
        val patient = listOf("patient", "patientDetails", "patient_details", "data")
            .firstNotNullOfOrNull { key -> root?.optJSONObject(key) } ?: root

        val returned = first(patient, CR_KEYS).filter(Char::isDigit)
            .ifBlank { first(rowObjects.firstOrNull(), CR_KEYS).filter(Char::isDigit) }
        val rowCrNos = rowObjects.map { first(it, CR_KEYS).filter(Char::isDigit) }.filter { it.isNotBlank() }

        if (returned.isNotBlank() && returned != requestedCrNo) {
            throw NimsIdentityMismatch("NIMS report-list API returned a different CR number.")
        }
        if (rowCrNos.any { it != requestedCrNo }) {
            throw NimsIdentityMismatch("NIMS report-list API returned a conflicting CR number.")
        }
        val verified = returned == requestedCrNo || rowCrNos.any { it == requestedCrNo }
        return reports to verified
    }

    fun parseHtml(html: String, baseUrl: String, requestedCrNo: String): List<ReportRef> {
        val document = Jsoup.parse(html, baseUrl)
        verifyCr(document, requestedCrNo)

        val reports = mutableListOf<ReportRef>()
        document.select("tr").forEachIndexed { index, row ->
            val rowText = compact(row.text())
            if (!rowText.contains("View Report", ignoreCase = true)) return@forEachIndexed
            val cells = row.select("th,td").map { compact(it.text()) }.filter { it.isNotBlank() }
            val onclick = row.select("[onclick]").map { it.attr("onclick") }
                .firstOrNull { it.contains("printReport", ignoreCase = true) }.orEmpty()
            val token = printRegex.find(onclick)?.groupValues?.getOrNull(1).orEmpty()
            val href = row.selectFirst("a[href]")?.absUrl("href").orEmpty()
            val direct = safeReportUrl(href)
            val tokenUrl = verifiedReportUrlForToken(token)
            val url = direct.ifBlank { tokenUrl }
            val title = guessTitle(cells, rowText)
            val date = cells.firstOrNull { dateRegex.containsMatchIn(it) }
                ?: dateRegex.find(rowText)?.value.orEmpty()
            val department = cells.firstOrNull {
                Regex("""(?i)pathology|microbiology|biochemistry|hematology|radiology""").containsMatchIn(it)
            }.orEmpty()
            val id = token.takeIf { it.isNotBlank() }
                ?: Integer.toHexString("$title|$date|$index".hashCode())
            reports += ReportRef(id = id, title = title, date = date, department = department, url = url)
        }
        return reports.distinctBy { normalize(it.title) + "|" + it.date + "|" + it.url }
    }

    fun verifiedReportUrlForToken(raw: String): String {
        val token = raw.trim()
        if (token.isBlank() || token.length > 512 || token.contains("..") || token.contains("://") ||
            token.contains('/') || token.contains('\\') || token.any { it.code < 0x20 || it.code == 0x7f }
        ) return ""
        return "https://www.nimsts.edu.in${ReportInventory.REPORT_PATH}?hmode=PRINTREPORT&fileName=" +
            java.net.URLEncoder.encode(token, Charsets.UTF_8.name()).replace("+", "%20")
    }

    fun safeReportUrl(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            val uri = URI(raw)
            val host = uri.host?.lowercase().orEmpty()
            if (uri.scheme != "https" || host !in setOf("nimsts.edu.in", "www.nimsts.edu.in")) return ""
            if (uri.path != ReportInventory.REPORT_PATH) return ""
            val query = uri.rawQuery.orEmpty()
            if (!Regex("""(?:^|&)hmode=PRINTREPORT(?:&|$)""", RegexOption.IGNORE_CASE).containsMatchIn(query)) return ""
            raw
        }.getOrDefault("")
    }

    private fun verifyCr(document: Document, requested: String) {
        val text = document.text()
        val labelled = crRegex.findAll(text).map { it.groupValues[1] }.toSet()
        if (labelled.any { it != requested }) {
            throw NimsIdentityMismatch("NIMS report page returned a different CR number.")
        }
        if (requested !in text && requested !in labelled) {
            throw NimsIdentityMismatch("NIMS report page did not confirm the requested CR number.")
        }
    }

    private fun normalizeRestRow(row: JSONObject, index: Int): ReportRef? {
        val title = first(row, TITLE_KEYS).ifBlank { "Investigation report" }
        val date = first(row, DATE_KEYS)
        val department = first(row, DEPARTMENT_KEYS)
        val direct = safeReportUrl(first(row, URL_KEYS))
        val rawUrl = first(row, URL_KEYS)
        val token = safeToken(first(row, TOKEN_KEYS)).ifBlank {
            if (direct.isBlank()) safeToken(rawUrl) else ""
        }
        val url = direct.ifBlank { verifiedReportUrlForToken(token) }
        val id = first(row, ID_KEYS).ifBlank { token }.ifBlank { Integer.toHexString("$title|$date|$index".hashCode()) }
        return if (title.isBlank() && url.isBlank()) null else ReportRef(id, title, date, department, url)
    }

    private fun safeToken(value: String): String {
        val token = value.trim()
        if (token.isBlank() || token.length > 512 || token.contains("..") || token.contains("://") ||
            token.contains('/') || token.contains('\\') || token.any { it.code < 0x20 || it.code == 0x7f }
        ) return ""
        return token
    }

    private fun collectArrays(node: Any?, depth: Int, out: MutableList<JSONArray>) {
        if (node == null || depth > 5) return
        when (node) {
            is JSONArray -> {
                out += node
                for (i in 0 until node.length()) collectArrays(node.opt(i), depth + 1, out)
            }
            is JSONObject -> node.keys().forEach { key -> collectArrays(node.opt(key), depth + 1, out) }
        }
    }

    private fun scoreArray(array: JSONArray): Int {
        if (array.length() == 0) return -1
        var score = 0
        for (i in 0 until minOf(array.length(), 20)) {
            val row = array.optJSONObject(i) ?: continue
            val keys = row.keys().asSequence().joinToString(" ").lowercase()
            if (Regex("report|investigation|test").containsMatchIn(keys)) score += 3
            if (Regex("date|sample|requisition|lab").containsMatchIn(keys)) score += 1
            if (Regex("url|filename|token").containsMatchIn(keys)) score += 2
        }
        return score
    }

    private fun first(row: JSONObject?, keys: List<String>): String {
        if (row == null) return ""
        for (key in keys) {
            val value = row.opt(key)
            if (value != null && value != JSONObject.NULL) {
                val text = value.toString().trim()
                if (text.isNotBlank() && !text.equals("null", true)) return text
            }
        }
        return ""
    }

    private fun guessTitle(cells: List<String>, text: String): String {
        val candidates = cells.filter { it.isNotBlank() && !it.contains("View Report", true) && !dateRegex.containsMatchIn(it) }
        return candidates.firstOrNull {
            Regex("""(?i)cbc|blood|renal|rft|liver|lft|culture|electrolyte|coag|crp|procalcitonin|urine|sputum|creatinine|hemoglobin|platelet""").containsMatchIn(it)
        } ?: candidates.getOrNull(minOf(2, maxOf(candidates.size - 1, 0)))
        ?: candidates.lastOrNull()
        ?: text.take(100)
    }

    private fun compact(value: String) = value.replace(Regex("\\s+"), " ").trim()
    private fun normalize(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "")

    private val CR_KEYS = listOf("crNo","cr_no","crNumber","cr_number","patientCrNo","patient_cr_no","patCrNo","pat_cr_no")
    private val TITLE_KEYS = listOf("reportName","report_name","investigationName","investigation_name","testName","test_name","investigation","test","name")
    private val DATE_KEYS = listOf("reportDate","report_date","dateSent","date_sent","requisitionDate","requisition_date","sampleDate","sample_date","date")
    private val DEPARTMENT_KEYS = listOf("department","dept","lab","laboratory","section")
    private val URL_KEYS = listOf("url","reportUrl","report_url","pdfUrl","pdf_url","href","downloadUrl","download_url")
    private val TOKEN_KEYS = listOf("fileName","filename","file_name","reportToken","report_token","token","reportFileName","report_file_name","pdfFileName","pdf_file_name")
    private val ID_KEYS = listOf("reportId","report_id","requisitionNo","requisition_no","reqNo","req_no","labNo","lab_no","accessionNo","accession_no","id")
}
