package org.kundakarlab.nimsresults

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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ReportRepository(
    private val cookies: CookieManager = CookieManager.getInstance(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    data class Inventory(
        val reports: List<ReportRef>,
        val source: String
    )

    fun load(crNo: String): Inventory {
        require(Regex("""\d{15}""").matches(crNo)) { "Enter the 15-digit NIMS CR number." }

        val rest = runCatching { loadRest(crNo) }.getOrElse { error ->
            if (error is IdentityMismatchException) throw error
            emptyList()
        }

        // REST is the preferred report inventory. Only open the slower NIMS
        // HTML report page if REST has no rows or lacks usable PDF links.
        // A failed optional enrichment must not hide already validated REST rows.
        val shouldFallback = rest.isEmpty() || rest.any { it.url.isBlank() }
        val html = if (shouldFallback) {
            runCatching { loadHtml(crNo) }.getOrElse { error ->
                if (error is IdentityMismatchException) throw error
                if (error is AuthenticationRequiredException && rest.isEmpty()) throw error
                emptyList()
            }
        } else emptyList()

        val merged = merge(rest, html)
        return Inventory(
            reports = merged,
            source = when {
                html.isNotEmpty() -> "authenticated_report_inventory"
                rest.isNotEmpty() -> "report_list_rest"
                else -> "report_inventory_unavailable"
            }
        )
    }

    private fun loadRest(crNo: String): List<ReportRef> {
        val url = NimsUrls.REPORT_LIST_API.toHttpUrl().newBuilder()
            .addQueryParameter("crNo", crNo)
            .addQueryParameter("hosCode", NimsUrls.HOSPITAL_CODE)
            .build()

        client.newCall(
            request(url.toString())
                .header("Accept", "application/json,text/plain,*/*")
                .get()
                .build()
        ).execute().use { response ->
            syncCookies(response.request.url.toString(), response.headers("Set-Cookie"))
            val text = response.body?.string().orEmpty()
            if (isAuthPage(response.code, response.request.url.toString(), text)) throw AuthenticationRequiredException()
            if (!response.isSuccessful) error("NIMS report-list returned HTTP ${response.code}.")
            val payload: Any = if (text.trim().startsWith("[")) JSONArray(text) else JSONObject(text)
            return ReportPayloadParser.parseRest(payload, crNo)
        }
    }

    private fun loadHtml(crNo: String): List<ReportRef> {
        val landing = client.newCall(
            request(NimsUrls.CR_REPORTS)
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .get()
                .build()
        ).execute().use { response ->
            syncCookies(response.request.url.toString(), response.headers("Set-Cookie"))
            val body = response.body?.string().orEmpty()
            if (isAuthPage(response.code, response.request.url.toString(), body)) throw AuthenticationRequiredException()
            if (!response.isSuccessful) error("NIMS CR report page returned HTTP ${response.code}.")
            body
        }

        val doc = Jsoup.parse(landing, NimsUrls.CR_REPORTS)
        val form = doc.selectFirst("form[name=viewExternalInvFB], form#viewExternalInvFB, form[action*=viewcrnowisereportprocess]")
        val action = form?.absUrl("action")?.takeIf { it.isNotBlank() } ?: NimsUrls.CR_REPORTS
        val body = FormBody.Builder()
        form?.select("input[type=hidden][name]")?.forEach { input ->
            val name = input.attr("name").trim()
            if (name.isNotBlank() && !name.equals("hmode", true) && !name.equals("patCrNo", true)) {
                body.add(name, input.attr("value"))
            }
        }
        body.add("hmode", "SHOWPATDETAILS")
        body.add("patCrNo", crNo)

        return client.newCall(
            request(action)
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .header("Origin", NimsUrls.WWW_ORIGIN)
                .header("Referer", NimsUrls.CR_REPORTS)
                .post(body.build())
                .build()
        ).execute().use { response ->
            syncCookies(response.request.url.toString(), response.headers("Set-Cookie"))
            val html = response.body?.string().orEmpty()
            if (isAuthPage(response.code, response.request.url.toString(), html)) throw AuthenticationRequiredException()
            if (!response.isSuccessful) error("NIMS CR report request returned HTTP ${response.code}.")
            ReportPayloadParser.parseHtml(html, response.request.url.toString(), crNo)
        }
    }

    private fun request(url: String): Request.Builder {
        val cookie = cookies.getCookie(url).orEmpty()
        return Request.Builder()
            .url(url)
            .header("User-Agent", NimsUrls.DESKTOP_UA)
            .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
    }

    private fun syncCookies(url: String, values: List<String>) {
        values.forEach { value -> runCatching { cookies.setCookie(url, value) } }
        cookies.flush()
    }

    private fun isAuthPage(code: Int, url: String, text: String): Boolean {
        if (code == 401 || code == 403) return true
        val sample = (url + " " + text.take(14000)).lowercase()
        return "loginlogin.action" in sample ||
            "session expired" in sample ||
            "please login again" in sample ||
            ("captcha" in sample && ("password" in sample || "user id" in sample || "username" in sample))
    }

    private fun merge(a: List<ReportRef>, b: List<ReportRef>): List<ReportRef> {
        val map = linkedMapOf<String, ReportRef>()
        (a + b).forEach { report ->
            val key = canonical(report.title) + "|" + report.date.trim().lowercase()
            val previous = map[key]
            if (previous == null) map[key] = report
            else map[key] = previous.copy(
                id = previous.id.ifBlank { report.id },
                title = previous.title.ifBlank { report.title },
                date = previous.date.ifBlank { report.date },
                department = previous.department.ifBlank { report.department },
                url = previous.url.ifBlank { report.url }
            )
        }
        return map.values.sortedByDescending { BulkParser.dateKey(BulkParser.isoDate(it.date)) }
    }

    private fun canonical(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "")
}

class AuthenticationRequiredException : IllegalStateException("NIMS login is required.")
class IdentityMismatchException(message: String) : IllegalStateException(message)

object ReportPayloadParser {
    private val crKeys = listOf("crNo","cr_no","crNumber","cr_number","patientCrNo","patient_cr_no","patCrNo","pat_cr_no")
    private val titleKeys = listOf("reportName","report_name","investigationName","investigation_name","testName","test_name","investigation","test","name")
    private val dateKeys = listOf("reportDate","report_date","dateSent","date_sent","requisitionDate","requisition_date","sampleDate","sample_date","date")
    private val departmentKeys = listOf("department","dept","lab","laboratory","section")
    private val urlKeys = listOf("url","reportUrl","report_url","pdfUrl","pdf_url","href","downloadUrl","download_url")
    private val tokenKeys = listOf("fileName","filename","file_name","reportToken","report_token","token","reportFileName","report_file_name","pdfFileName","pdf_file_name")
    private val idKeys = listOf("reportId","report_id","requisitionNo","requisition_no","reqNo","req_no","labNo","lab_no","accessionNo","accession_no","id")
    private val dateRegex = Regex("""\b\d{1,2}[-/][A-Za-z]{3}[-/]\d{2,4}\b|\b\d{1,2}[-/]\d{1,2}[-/]\d{2,4}\b|\b\d{4}-\d{1,2}-\d{1,2}\b""")
    private val crRegex = Regex("""(?i)(?:CR\s*(?:No|Number)?|patCrNo)\s*[:=\-]?\s*(\d{15})""")
    private val printReportRegex = Regex("""(?i)printReport\s*\(\s*['"]([^'"]+)['"]\s*\)""")

    fun parseRest(payload: Any, expectedCr: String): List<ReportRef> {
        val arrays = mutableListOf<JSONArray>()
        collectArrays(payload, 0, arrays)
        val rows = arrays.maxByOrNull(::score) ?: JSONArray()
        val objects = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }

        val root = payload as? JSONObject
        val patient = listOf("patient", "patientDetails", "patient_details", "data")
            .firstNotNullOfOrNull { key -> root?.optJSONObject(key) } ?: root

        val returned = first(patient, crKeys).filter(Char::isDigit)
            .ifBlank { first(objects.firstOrNull(), crKeys).filter(Char::isDigit) }
        val rowCrs = objects.map { first(it, crKeys).filter(Char::isDigit) }.filter { it.isNotBlank() }

        if (returned.isNotBlank() && returned != expectedCr) throw IdentityMismatchException("NIMS report-list returned a different CR.")
        if (rowCrs.any { it != expectedCr }) throw IdentityMismatchException("NIMS report-list returned conflicting patient rows.")
        if (returned.isBlank() && rowCrs.isEmpty()) return emptyList()

        return objects.mapIndexedNotNull { index, row ->
            val title = first(row, titleKeys).ifBlank { "Investigation report" }
            val date = first(row, dateKeys)
            val department = first(row, departmentKeys)
            val direct = safeReportUrl(first(row, urlKeys))
            val rawUrl = first(row, urlKeys)
            val token = safeToken(first(row, tokenKeys)).ifBlank { if (direct.isBlank()) safeToken(rawUrl) else "" }
            val url = direct.ifBlank { reportUrlForToken(token) }
            val id = first(row, idKeys).ifBlank { token }.ifBlank { Integer.toHexString("$title|$date|$index".hashCode()) }
            if (title.isBlank() && url.isBlank()) null else ReportRef(id, title, date, department, url)
        }
    }

    fun parseHtml(html: String, baseUrl: String, expectedCr: String): List<ReportRef> {
        val document = Jsoup.parse(html, baseUrl)
        verifyCr(document, expectedCr)
        val reports = mutableListOf<ReportRef>()

        document.select("tr").forEachIndexed { index, row ->
            val rowText = compact(row.text())
            if (!rowText.contains("View Report", true)) return@forEachIndexed
            val cells = row.select("th,td").map { compact(it.text()) }.filter { it.isNotBlank() }
            val onclick = row.select("[onclick]").map { it.attr("onclick") }
                .firstOrNull { it.contains("printReport", true) }.orEmpty()
            val token = printReportRegex.find(onclick)?.groupValues?.getOrNull(1).orEmpty()
            val href = row.selectFirst("a[href]")?.absUrl("href").orEmpty()
            val url = safeReportUrl(href).ifBlank { reportUrlForToken(token) }
            val date = cells.firstOrNull { dateRegex.containsMatchIn(it) } ?: dateRegex.find(rowText)?.value.orEmpty()
            val department = cells.firstOrNull {
                Regex("""(?i)pathology|microbiology|biochemistry|hematology|radiology""").containsMatchIn(it)
            }.orEmpty()
            val title = guessTitle(cells, rowText)
            val id = token.ifBlank { Integer.toHexString("$title|$date|$index".hashCode()) }
            reports += ReportRef(id, title, date, department, url)
        }
        return reports.distinctBy { canonical(it.title) + "|" + it.date + "|" + it.url }
    }

    fun reportUrlForToken(raw: String): String {
        val token = safeToken(raw)
        if (token.isBlank()) return ""
        return NimsUrls.WWW_ORIGIN + NimsUrls.REPORT_PATH +
            "?hmode=PRINTREPORT&fileName=" +
            URLEncoder.encode(token, Charsets.UTF_8.name()).replace("+", "%20")
    }

    fun safeReportUrl(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            val uri = URI(raw)
            val host = uri.host?.lowercase().orEmpty()
            if (uri.scheme != "https" || host !in NimsUrls.HOSTS) return ""
            if (uri.path != NimsUrls.REPORT_PATH) return ""
            if (!Regex("""(?:^|&)hmode=PRINTREPORT(?:&|$)""", RegexOption.IGNORE_CASE).containsMatchIn(uri.rawQuery.orEmpty())) return ""
            raw
        }.getOrDefault("")
    }

    private fun verifyCr(document: Document, expected: String) {
        val text = document.text()
        val labelled = crRegex.findAll(text).map { it.groupValues[1] }.toSet()
        if (labelled.any { it != expected }) throw IdentityMismatchException("NIMS report page returned a different CR.")
        if (expected !in text && expected !in labelled) throw IdentityMismatchException("NIMS report page did not confirm the requested CR.")
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
            is JSONObject -> node.keys().forEach { collectArrays(node.opt(it), depth + 1, out) }
        }
    }

    private fun score(array: JSONArray): Int {
        if (array.length() == 0) return -1
        var score = 0
        for (i in 0 until minOf(20, array.length())) {
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
        keys.forEach { key ->
            val value = row.opt(key)
            if (value != null && value != JSONObject.NULL) {
                val text = value.toString().trim()
                if (text.isNotBlank() && !text.equals("null", true)) return text
            }
        }
        return ""
    }

    private fun guessTitle(cells: List<String>, rowText: String): String {
        val candidates = cells.filter { it.isNotBlank() && !it.contains("View Report", true) && !dateRegex.containsMatchIn(it) }
        return candidates.firstOrNull {
            Regex("""(?i)cbc|blood|renal|rft|liver|lft|culture|electrolyte|coag|crp|procalcitonin|urine|sputum|creatinine|hemoglobin|platelet""").containsMatchIn(it)
        } ?: candidates.getOrNull(minOf(2, maxOf(candidates.size - 1, 0)))
        ?: candidates.lastOrNull()
        ?: rowText.take(100)
    }

    private fun compact(value: String) = value.replace(Regex("\\s+"), " ").trim()
    private fun canonical(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "")
}
