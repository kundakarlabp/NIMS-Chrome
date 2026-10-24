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
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Dashboard parity: try the authenticated REST report-list before loading
     * the HIS browser. It is report inventory, not a lab-values API.
     * The 3.5 s limit matches the desktop connector's optional metadata probe.
     * Never return unverified or wrong-CR report data.
     */
    fun loadRestIndex(crNo: String): Inventory {
        require(Regex("""\d{15}""").matches(crNo))
        val fastClient = client.newBuilder()
            .callTimeout(3500, TimeUnit.MILLISECONDS)
            .build()
        ReportInventoryTrace.event("rest_attempt")
        val reports = loadRest(crNo, fastClient)
        if (reports.isEmpty()) {
            ReportInventoryTrace.event("rest_empty")
            error("NIMS REST returned no verified report rows.")
        }
        ReportInventoryTrace.event("rest_nonempty", reports.size)
        return Inventory(reports, "nims_rest_report_list")
    }

    fun load(crNo: String, verifiedRest: List<ReportRef> = emptyList()): Inventory {
        require(Regex("""\d{15}""").matches(crNo)) { "Enter the 15-digit NIMS CR number." }

        val rest = if (verifiedRest.isNotEmpty()) verifiedRest else runCatching {
            ReportInventoryTrace.event("rest_attempt")
            loadRest(crNo)
        }.getOrElse { error ->
            if (error is IdentityMismatchException) throw error
            ReportInventoryTrace.event(
                if (error is AuthenticationRequiredException) "rest_auth_required"
                else "rest_parse_error"
            )
            emptyList()
        }

        // REST is first, but a response containing only linked rows does not
        // prove report-history completeness. Supplement it with NIMS HTML
        // inventory in the background without holding up structured labs.
        val html = runCatching { loadHtml(crNo) }.getOrElse { error ->
            if (error is IdentityMismatchException) {
                ReportInventoryTrace.event("html_identity_mismatch")
                throw error
            }
            ReportInventoryTrace.event(
                if (error is AuthenticationRequiredException) "html_auth_required"
                else "html_parse_error"
            )
            if (error is AuthenticationRequiredException && rest.isEmpty()) throw error
            emptyList()
        }

        val merged = merge(rest, html)
        if (merged.isEmpty()) ReportInventoryTrace.event("all_sources_unavailable")
        return Inventory(
            reports = merged,
            source = when {
                html.isNotEmpty() -> "authenticated_report_inventory"
                rest.isNotEmpty() -> "report_list_rest"
                else -> "report_inventory_unavailable"
            }
        )
    }

    private fun loadRest(crNo: String, apiClient: OkHttpClient = client): List<ReportRef> {
        val url = NimsUrls.REPORT_LIST_API.toHttpUrl().newBuilder()
            .addQueryParameter("crNo", crNo)
            .addQueryParameter("hosCode", NimsUrls.HOSPITAL_CODE)
            .build()

        apiClient.newCall(
            request(url.toString())
                .header("Accept", "application/json,text/plain,*/*")
                .get()
                .build()
        ).execute().use { response ->
            syncCookies(response.request.url.toString(), response.headers("Set-Cookie"))
            val text = response.body?.string().orEmpty()
            if (isAuthPage(response.code, response.request.url.toString(), text)) throw AuthenticationRequiredException()
            if (!response.isSuccessful) {
                ReportInventoryTrace.event("rest_http_error")
                error("NIMS report-list returned HTTP ${response.code}.")
            }
            val payload: Any = if (text.trim().startsWith("[")) JSONArray(text) else JSONObject(text)
            return ReportPayloadParser.parseRest(payload, crNo)
        }
    }

    private fun loadHtml(crNo: String): List<ReportRef> {
        ReportInventoryTrace.event("html_attempt")
        val landing = client.newCall(
            request(NimsUrls.CR_REPORTS)
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .get()
                .build()
        ).execute().use { response ->
            syncCookies(response.request.url.toString(), response.headers("Set-Cookie"))
            val body = response.body?.string().orEmpty()
            if (isAuthPage(response.code, response.request.url.toString(), body)) throw AuthenticationRequiredException()
            if (!response.isSuccessful) {
                ReportInventoryTrace.event("html_http_error")
                error("NIMS CR report page returned HTTP ${response.code}.")
            }
            body
        }

        val doc = Jsoup.parse(landing, NimsUrls.CR_REPORTS)
        val form = doc.selectFirst("form[name=viewExternalInvFB], form#viewExternalInvFB, form[action*=viewcrnowisereportprocess]")
        ReportInventoryTrace.event(if (form == null) "html_form_missing" else "html_form_found")
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
            if (!response.isSuccessful) {
                ReportInventoryTrace.event("html_http_error")
                error("NIMS CR report request returned HTTP ${response.code}.")
            }
            val reports = ReportPayloadParser.parseHtml(html, response.request.url.toString(), crNo)
            if (reports.isNotEmpty()) ReportInventoryTrace.event("html_verified_rows", reports.size)
            else ReportInventoryTrace.event("html_no_report_rows")
            reports
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
            // Distinct same-day CBC/culture PDFs must not collapse into one
            // merely because they share a test title and date.
            val key = if (report.url.isNotBlank()) {
                "pdf|" + report.url
            } else {
                "meta|" + canonical(report.title) + "|" +
                    report.date.trim().lowercase() + "|" + report.id
            }
            val previous = map[key]
            if (previous == null) map[key] = report
            else map[key] = previous.copy(
                id = previous.id.ifBlank { report.id },
                title = previous.title.ifBlank { report.title },
                date = previous.date.ifBlank { report.date },
                department = previous.department.ifBlank { report.department },
                url = previous.url.ifBlank { report.url },
                sentDate = previous.sentDate.ifBlank { report.sentDate },
                reportedDate = previous.reportedDate.ifBlank { report.reportedDate },
                labNumber = previous.labNumber.ifBlank { report.labNumber },
                orderDate = previous.orderDate.ifBlank { report.orderDate },
                sampleNumber = previous.sampleNumber.ifBlank { report.sampleNumber }
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
    private val dateKeys = listOf("dateSent","date_sent","sampleDate","sample_date","requisitionDate","requisition_date","reportDate","report_date","date")
    private val sentKeys = listOf("collectionDate","collection_date","sampleCollectionDate","sampleDate","sample_date","dateSent","date_sent")
    private val orderKeys = listOf("requisitionDate","requisition_date","orderDate","order_date","orderedDate","ordered_date")
    private val reportedKeys = listOf("reportingDate","reporting_date","reportedDate","reported_date","reportDate","report_date","validationDate","validation_date")
    private val sampleKeys = listOf("sampleNo","sample_no","sampleNumber","sample_number","specimenNo","specimen_no")
    private val labKeys = listOf("labStudyNo","labStudyNumber","labNo","lab_no","labNumber","lab_number","accessionNo","accession_no")
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
            val sentDate = first(row, sentKeys)
            val reportedDate = first(row, reportedKeys)
            val orderedDate = first(row, orderKeys)
            // Keep the source-listed index date in its original meaning.
            val date = first(row, dateKeys)
            val labNumber = first(row, labKeys)
            val sampleNumber = first(row, sampleKeys)
            val department = first(row, departmentKeys)
            val direct = safeReportUrl(first(row, urlKeys))
            val rawUrl = first(row, urlKeys)
            val token = safeToken(first(row, tokenKeys)).ifBlank { if (direct.isBlank()) safeToken(rawUrl) else "" }
            val url = direct.ifBlank { reportUrlForToken(token) }
            val id = first(row, idKeys).ifBlank { token }.ifBlank { Integer.toHexString("$title|$date|$index".hashCode()) }
            if (title.isBlank() && url.isBlank()) null else ReportRef(
                id, title, date, department, url, sentDate, reportedDate, labNumber, orderedDate, sampleNumber
            )
        }
    }

    fun parseHtml(html: String, baseUrl: String, expectedCr: String): List<ReportRef> {
        val document = Jsoup.parse(html, baseUrl)
        verifyCr(document, expectedCr)
        val reports = mutableListOf<ReportRef>()
        val sourceRows = document.select("tr")
        if (sourceRows.isNotEmpty()) ReportInventoryTrace.event("html_table_rows", sourceRows.size)
        sourceRows.forEachIndexed { index, row ->
            val rowText = compact(row.text())
            if (!rowText.contains("View Report", true)) return@forEachIndexed
            // Keep empty cells: dropping an empty lab/collection field shifts
            // NIMS's fixed table columns and corrupts the source relationship.
            val cells = row.select("td").map { compact(it.text()) }
                .ifEmpty { row.select("th,td").map { compact(it.text()) } }
            val onclick = row.select("[onclick]").map { it.attr("onclick") }
                .firstOrNull { it.contains("printReport", true) }.orEmpty()
            val token = printReportRegex.find(onclick)?.groupValues?.getOrNull(1).orEmpty()
            // Prefer a direct, explicitly validated NIMS report URL in the
            // source row; scripts may store it in href, onclick or a data attr.
            val directStrings = row.select("a[href], [data-url], [onclick]")
                .flatMap { el -> listOf(el.absUrl("href"), el.attr("href"),
                    el.attr("data-url"), el.attr("onclick")) }
            val embedded = Regex(
                """https?://[^\s'"]+|/HISInvestigationG5/new_investigation/[^\s'"]+""",
                RegexOption.IGNORE_CASE
            )
            val url = directStrings.firstNotNullOfOrNull { raw ->
                safeReportUrl(raw).takeIf(String::isNotBlank)
                    ?: embedded.findAll(raw).mapNotNull { match ->
                        val text = match.value.replace("&amp;", "&")
                        val absolute = if (text.startsWith("/")) NimsUrls.WWW_ORIGIN + text
                            else text
                        safeReportUrl(absolute).takeIf(String::isNotBlank)
                    }.firstOrNull()
            }.orEmpty().ifBlank { reportUrlForToken(token) }
            // Preserve source-index metadata even when NIMS uses an
            // unrecognized JavaScript View Report handler. Do not manufacture
            // a PDF URL from a guessed token.
            // NIMS "CR No Wise Result Report Printing New" table:
            // LabName | Test Name | Sample No/Lab No | Collection Date |
            // Report NO. | Report(View Report).
            // This source row is already the PDF-to-study association.
            val knownLayout = cells.size >= 6 &&
                cells.last().contains("View Report", true)
            val pair = if (knownLayout) cells[2] else ""
            val components = pair.split('/').map(String::trim)
                .filter(String::isNotBlank)
            val sample = if (components.size >= 2) components.first() else ""
            val lab = if (components.size >= 2) components.last()
                else if (knownLayout && OpaqueReportTitles.isCode(pair)) pair else ""
            val collected = if (knownLayout)
                dateRegex.find(cells[3])?.value.orEmpty() else ""
            val date = collected.ifBlank {
                cells.firstNotNullOfOrNull { c ->
                    dateRegex.find(c)?.value
                }.orEmpty()
            }
            val dept = if (knownLayout) cells[0] else cells.firstOrNull {
                Regex("""(?i)pathology|microbiology|biochemistry|hematology|radiology|bacteriology|chemistry""")
                    .containsMatchIn(it)
            }.orEmpty()
            val title = if (knownLayout) cells[1] else guessTitle(cells, rowText)
            val guessedLab = if (lab.isBlank() && !knownLayout)
                cells.firstOrNull { OpaqueReportTitles.isCode(it) }.orEmpty() else lab
            val id = token.ifBlank {
                Integer.toHexString("$title|$date|$index|$url".hashCode())
            }
            if (url.isBlank()) ReportInventoryTrace.event("html_rows_without_pdf")
            reports += ReportRef(id = id, title = title, date = date,
                department = dept, url = url, sentDate = collected,
                labNumber = guessedLab, sampleNumber = sample)
        }
        // Keep separate report-stage URLs even when lab accession is shared.
        return reports.distinctBy { if (it.url.isNotBlank()) "pdf|" + it.url
            else "index|" + it.id }
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
        // Prefer a genuine investigation name, not the accession prefix.
        // If the source exposes only an identifier, keep it as metadata,
        // never invent a culture or serology label.
        val candidates = cells.filter {
            it.isNotBlank() && !it.contains("View Report", true) &&
                !dateRegex.containsMatchIn(it)
        }
        val clinical = Regex(
            """(?i)\b(cbc|peripheral\s*smear|esr|dengue|serolog|malaria|blood\s*culture|aerobic\s*culture|fungal|k[o0]h|crp|c[- ]?reactive|electrolytes|creatinine|urea|liver\s*function|voriconazole|galactomannan|procalcitonin|urine\s*culture|afb|hiv|hbsag|hepatitis|pathology|cytology)\b"""
        )
        return candidates.firstOrNull { clinical.containsMatchIn(it) && !OpaqueReportTitles.isCode(it) }
            ?: candidates.firstOrNull { it.length >= 9 && it.count(Char::isLetter) >= 6 &&
                !OpaqueReportTitles.isCode(it) &&
                !Regex("""(?i)^(clinical|microbiology|biochemistry|laboratory|pathology)\s*(?:lab)?$""").matches(it)
            }
            ?: candidates.firstOrNull { OpaqueReportTitles.isCode(it) }
            ?: "Investigation title unavailable"
    }

    private fun compact(value: String) = value.replace(Regex("\\s+"), " ").trim()
    private fun canonical(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "")
}


/** Only name reports after their own PDF text is locally extracted. */
object OpaqueReportTitles {
    fun isCode(value: String): Boolean {
        val text = value.trim()
        return text.length in 4..64 && !text.contains(' ') &&
            text.any(Char::isDigit) && text.any(Char::isLetter) &&
            Regex("""(?i)^[a-z0-9/_-]+$""").matches(text)
    }
    fun titleFromExtractedText(text: String): String? {
        val patterns = listOf(
            """(?i)\bperipheral\s+smear\s+examination\b""" to "Peripheral smear examination",
            """(?i)\bvoriconazole\s+report\b|\bserum\s+voriconazole\s+levels\b""" to "Voriconazole therapeutic drug monitoring",
            """(?i)\bdengue\s+virus\s+ns1\b""" to "Dengue serology (NS1 / antibodies)",
            """(?i)\bstrip\s+test\s+for\s+malaria\b|\bsmear\s+for\s+mp\b""" to "Malaria diagnostics",
            """(?i)\b(?:afb|acid\s*fast)\s*smear\b|\btb\s*fluorescence\s*stain(?:ing)?\b""" to "AFB smear",
            """(?i)\b(?:aerobic|blood|fungal|urine)\s+culture\b""" to "Microbiology culture report",
            """(?i)\bgram(?:s|['’]s)?\s*(?:stain(?:ing)?|smear)\b""" to "Gram stain",
            """(?i)\bc[- ]?reactive\s+protein\b""" to "C-reactive protein",
            """(?i)\b(?:complete\s+blood\s+count|cbc\s*[\(+])\b""" to "Complete blood count",
            """(?i)\bgrouping\s+and\s+(?:antibody\s+)?screening\b|\bblood\s+grouping\s+and\s+antibody\s+screening\b""" to "Blood grouping and antibody screening",
            """(?i)\b(?:biofire|filmarray)\s+(?:joint\s+infection|meningitis|respiratory|gastrointestinal)\s+panel\b""" to "BioFire molecular panel",
            """(?i)\bf\s*-?\s*18\s+fdg\b.*?\bpet\s*-?\s*ct\b""" to "F-18 FDG PET-CT",
            """(?im)^\s*esr\s*$""" to "Erythrocyte sedimentation rate"
        )
        return patterns.firstOrNull { Regex(it.first).containsMatchIn(text) }?.second
    }
}

/** In-memory only, intentionally not persisted as PHI-bearing metadata. */
data class PdfDocumentHeader(
    val crNo: String = "",
    val labNumber: String = "",
    val collectionDate: String = "",
    val reportedDate: String = "",
    val orderDate: String = ""
)

object PdfDocumentHeaders {
    fun parse(text: String): PdfDocumentHeader {
        // Only labelled fields carry clinical provenance; never label a generic
        // index/result date as collection or validation time.
        fun field(pattern: String): String = Regex(pattern, RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val date = """((?:\d{1,2}[-/][A-Za-z]{3}[-/]\d{2,4}|\d{1,2}[-/]\d{1,2}[-/]\d{2,4}|\d{4}-\d{1,2}-\d{1,2})(?:\s+\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM)?)?)"""
        val time = """(\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM)?)"""
        fun combine(d: String, t: String): String {
            if (d.isBlank()) return t
            if (t.isBlank() || Regex("""\d{1,2}:\d{2}""").containsMatchIn(d)) return d
            return "$d $t"
        }
        val collected = field(
            """\b(?:Coll\s*\.?\s*/\s*Study|Specimen\s*Collect(?:ion|ed)?|Sample\s*Collect(?:ion|ed)?|Collection|Date\s*Sent|Sent\s*Date)\s*(?:Date)?\s*[:#\-]\s*""" + date
        )
        val collectedTime = field(
            """\b(?:Collection|Sample\s*Collection|Specimen\s*Collection|Coll\s*/\s*Study|Collected)\s*Time\s*[:#\-]\s*""" + time
        )
        val reported = field(
            """\b(?:Reporting|Reported|Report\s*Issued|Validation|Report)\s*Date\s*[:#\-]\s*""" + date
        )
        val reportedTime = field(
            """\b(?:Reporting|Reported|Report\s*Issued|Validation)\s*Time\s*[:#\-]\s*""" + time
        )
        return PdfDocumentHeader(
            crNo = field("""\b(?:Patient\s*)?CR\s*(?:No|Number)?\.?\s*[:#\-]?\s*(\d{15})\b"""),
            labNumber = field("""\b(?:Lab\s*/\s*Study|Lab\s*Number|Lab\s*No|Accession(?:\s*Number|\s*No)?|Study\s*No)\.?\s*(?:No\.?)?\s*[:#\-]\s*([A-Za-z0-9/_-]+)"""),
            collectionDate = combine(collected, collectedTime),
            reportedDate = combine(reported, reportedTime),
            orderDate = field("""\b(?:Requisition|Ordered|Order)\s*Date\s*[:#\-]\s*""" + date)
        )
    }
}

object ReportTitleCache {
    private val recognized = ConcurrentHashMap<String, String>()
    private val extractedByUrl = ConcurrentHashMap<String, String>()
    private val headers = ConcurrentHashMap<String, PdfDocumentHeader>()
    private val sectionsByUrl = ConcurrentHashMap<String, List<PdfSourcePart>>()
    private val sha256ByUrl = ConcurrentHashMap<String, String>()

    fun learn(url: String, extracted: String, expectedCr: String = ""): Boolean =
        learnPages(url, listOf(extracted), expectedCr)

    /** Index all pages, not just page 1-2. Explicit CR proof is required to
     * associate PDF contents with the selected patient's investigations. */
    fun learnPages(url: String, pages: List<String>, expectedCr: String = "", sha256: String = ""): Boolean {
        val crs = PdfSourceEvidence.patientCrs(pages)
        if (expectedCr.isNotBlank() && crs != setOf(expectedCr)) return false
        val full = pages.joinToString("\n\n") { it.take(40000) }
        OpaqueReportTitles.titleFromExtractedText(full)?.let { recognized[url] = it }
        extractedByUrl[url] = full.take(160000)
        if (expectedCr.isNotBlank() && crs == setOf(expectedCr)) {
            if (sha256.isNotBlank()) sha256ByUrl[url] = sha256
            val sections = PdfSourceEvidence.parts(pages, expectedCr)
            sectionsByUrl[url] = sections
            headers[url] = sections.firstOrNull { it.header.labNumber.isNotBlank() }?.header
                ?: PdfDocumentHeaders.parse(full).copy(crNo = expectedCr)
        }
        return true
    }
    fun get(url: String): String? = recognized[url]
    fun extracted(url: String): String? = extractedByUrl[url]
    fun header(url: String): PdfDocumentHeader? = headers[url]
    fun sections(url: String): List<PdfSourcePart> = sectionsByUrl[url].orEmpty()
    fun digest(url: String): String = sha256ByUrl[url].orEmpty()
    fun accessions(url: String): Set<String> = sections(url)
        .map { it.header.labNumber.trim() }.filter { it.isNotBlank() }.toSet()
    fun clear() {
        recognized.clear()
        extractedByUrl.clear()
        headers.clear()
        sectionsByUrl.clear()
        sha256ByUrl.clear()
    }
}
