package org.kundakarlab.nimsfastsummarymobile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var output: TextView
    private lateinit var helperUrl: EditText
    private lateinit var helperKey: EditText
    private lateinit var settings: SecureSettings
    private var mapping: ReportTemplate? = null
    private var mappingValidated = false
    private var webViewUserAgent = ""
    private var appState = AppState.NEED_HELPER_SETTINGS

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SecureSettings(this)
        CookieManager.getInstance().setAcceptCookie(true)
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = NimsWebViewClient()
        }
        webViewUserAgent = webView.settings.userAgentString
        output = TextView(this).apply { textSize = 12f }
        helperUrl = EditText(this).apply {
            hint = "https://your-service.up.railway.app"
            setText(settings.helperUrl())
        }
        helperKey = EditText(this).apply {
            hint = if (settings.apiKey().isBlank()) "Helper API key" else "API key saved; enter only to replace"
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(helperUrl)
            addView(helperKey)
            addButton("Save Helper") { saveHelperSettings() }
            addButton("Test Helper") { testHelper() }
            addButton("Diagnose Page") { diagnosePage() }
            addButton("Discover Mapping") { discoverMapping() }
            addButton("Test Direct Fetch") { runMode("test_direct") }
            addButton("Bulk Fast Summary") { runMode("bulk_fast") }
            addButton("Bulk Cultures Only") { runMode("bulk_cultures_only") }
            addButton("Bulk Full Summary") { runMode("bulk_full") }
            addButton("Clear Mapping") { mapping = null; mappingValidated = false; log("Mapping cleared") }
            addButton("Copy Text") { copyOutput() }
            addButton("Clear Output") { output.text = ""; setState(AppState.NIMS_LOGIN, "Output cleared") }
            addButton("Clear Helper Settings") { settings.clear(); helperUrl.setText(""); helperKey.setText(""); log("Helper settings cleared") }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(controls)
            addView(ScrollView(this@MainActivity).apply { addView(output) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 260))
        }
        setContentView(root)
        setState(if (settings.helperUrl().isBlank() || !settings.hasApiKey()) AppState.NEED_HELPER_SETTINGS else AppState.HELPER_READY, "Open NIMS and login manually.")
        webView.loadUrl(NIMS_LOGIN_URL)
    }

    private fun LinearLayout.addButton(label: String, onClick: () -> Unit) {
        addView(Button(context).apply {
            text = label
            setOnClickListener { onClick() }
        })
    }

    private fun saveHelperSettings() {
        runCatching {
            settings.saveHelperUrl(helperUrl.text.toString())
            if (helperKey.text.toString().isBlank() && !settings.hasApiKey()) {
                throw IllegalArgumentException("Set Railway helper API key first.")
            }
            settings.saveApiKey(helperKey.text.toString())
        }.onFailure {
            setState(AppState.ERROR, it.message ?: "Helper settings invalid")
            return
        }
        helperKey.setText("")
        helperKey.hint = "API key saved; enter only to replace"
        setState(AppState.HELPER_READY, "Helper settings saved")
    }

    private fun testHelper() {
        Thread {
            runCatching {
                val health = publicHelper().health()
                val version = publicHelper().version()
                "Helper ok: version=${version.optString("version")} remote_mode=${health.optBoolean("remote_mode")} cache_enabled=${health.optBoolean("cache_enabled")} api_key_configured=${health.optBoolean("api_key_configured")}"
            }
                .onSuccess { setState(AppState.HELPER_READY, it) }
                .onFailure { setState(AppState.ERROR, "Helper connection failed: ${it.message}") }
        }.start()
    }

    private fun diagnosePage() {
        evaluateCore("JSON.stringify(NimsReportCore.diagnosePage(document))") { json ->
            log("Diagnosis: ${safeJson(json)}")
        }
    }

    private fun discoverMapping() {
        mappingValidated = false
        evaluateCore("JSON.stringify(NimsReportCore.clickFirstReportForMode('test_direct', document))") { click ->
            if (!click.optBoolean("ok")) {
                log(click.optString("error", "No View Report button found for row"))
                return@evaluateCore
            }
            log("Waiting for setPdf template")
            Handler(Looper.getMainLooper()).postDelayed({
                evaluateCore("JSON.stringify(NimsReportCore.discoverSetPdfTemplate(document))") { template ->
                    if (!template.optBoolean("discovered")) {
                        log("setPdf template not discovered")
                        return@evaluateCore
                    }
                    mapping = ReportTemplate(
                        origin = template.getString("origin"),
                        pathname = template.getString("pathname"),
                        modeParamName = template.optString("modeParamName", "hmode"),
                        modeParamValue = template.optString("modeParamValue", "PRINTREPORT"),
                        argumentParameterName = template.optString("argumentParameterName", "fileName")
                    )
                    mappingValidated = false
                    setState(AppState.MAPPING_DISCOVERED, "Mapping ready: ${template.optString("endpoint")} params=${template.optJSONArray("queryParamNames")}")
                }
            }, 1200)
        }
    }

    private fun runMode(mode: String) {
        val currentMapping = mapping
        if (currentMapping == null) {
            log("Run Discover Mapping first")
            return
        }
        if (mode != "test_direct" && !mappingValidated) {
            log("Run Test Direct Fetch successfully before Bulk Summary.")
            return
        }
        evaluateJson("JSON.stringify(NimsReportCore.rowsFromBestFrame(document))") { rowsText ->
            val rows = JSONArray(rowsText)
            evaluateJson("JSON.stringify(NimsReportCore.selectRowsForMode(${rows}, '$mode'))") { selectedText ->
                val selectedAll = JSONArray(selectedText)
                val selected = if (mode == "test_direct" && selectedAll.length() > 1) {
                    JSONArray().put(selectedAll.getJSONObject(0))
                } else {
                    selectedAll
                }
                log("Selected ${selected.length()} reports")
                Thread { fetchParseSummarize(mode, selected, currentMapping) }.start()
            }
        }
    }

    private fun fetchParseSummarize(mode: String, selected: JSONArray, template: ReportTemplate) {
        setState(AppState.FETCHING, "Fetching reports")
        val parsedReports = JSONArray()
        val rows = (0 until selected.length()).map { selected.getJSONObject(it) }
        if (mode == "test_direct") {
            val row = rows.firstOrNull()
            if (row == null) {
                runOnUiThread { log("No report selected for Test Direct Fetch") }
                return
            }
            val report = fetchAndParseOne(row, 0, 1, template)
            parsedReports.put(report)
            val valid = isParsedReportValid(report)
            mappingValidated = valid
            if (!valid) {
                mappingValidated = false
                runOnUiThread { log(report.optJSONArray("errors")?.optString(0) ?: "Test Direct Fetch did not parse a report") }
                return
            }
        } else {
            val results = ReportFetchQueue(concurrency = 3).run(rows.withIndex().toList()) { indexed ->
                fetchAndParseOne(indexed.value, indexed.index, rows.size, template)
            }
            results.forEach { result ->
                parsedReports.put(result.getOrElse { errorReport(JSONObject(), it.message ?: "direct fetch failed") })
            }
        }
        val summaryMode = when (mode) {
            "bulk_full" -> "full"
            "bulk_cultures_only" -> "cultures_only"
            else -> "fast"
        }
        runOnUiThread { log("Parsing ${parsedReports.length()}/${selected.length()}") }
        val summary = helper().summarize(JSONObject().put("mode", summaryMode).put("reports", parsedReports))
        runOnUiThread {
            renderSummary(summary)
            setState(AppState.SUMMARY_READY, "Done")
        }
    }

    private fun fetchAndParseOne(row: JSONObject, index: Int, total: Int, template: ReportTemplate): JSONObject {
        return runCatching {
            validateHelperSettings()
            runOnUiThread { log("Fetching ${index + 1}/$total") }
            val transient = transientArgFor(row)
            val url = NimsReportTemplate.directReportUrl(template, transient)
            val response = fetchWithWebViewCookies(url)
            val classification = ReportResponseClassifier.classify(response.statusCode, response.contentType, response.bytes)
            if (classification == "html_login_or_session") throw IllegalStateException("Session expired. Login again in NIMS WebView.")
            if (classification !in setOf("pdf_report", "html_report_content")) throw IllegalStateException("Report fetch returned $classification")
            runOnUiThread { log("Parsing ${index + 1}/$total") }
            val payload = JSONObject()
                .put("report_id", safeReportKey(transient, row))
                .put("report_name", row.optString("report_name"))
                .put("date_sent", row.optString("date_sent"))
                .put("source_url", SafeUrl.hostPath(url))
                .put("content_type", contentType(response.contentType))
                .put("pdf_base64", Base64.encodeToString(response.bytes, Base64.NO_WRAP))
            helper().parseReport(payload)
        }.getOrElse {
            errorReport(row, it.message ?: "direct fetch failed")
        }
    }

    private fun transientArgFor(row: JSONObject): String {
        var arg = ""
        val script = "JSON.stringify(NimsReportCore.transientPayloadForRow(${row}, document))"
        val latch = java.util.concurrent.CountDownLatch(1)
        runOnUiThread {
            evaluateCore(script) { payload ->
                arg = payload.optString("transientPrintReportArg")
                latch.countDown()
            }
        }
        latch.await()
        if (arg.isBlank()) throw IllegalStateException("Required report argument missing")
        return arg
    }

    private fun fetchWithWebViewCookies(url: String): ReportFetchResult {
        if (!NimsReportTemplate.isAllowedNimsUrl(url)) throw IllegalStateException("NIMS report URL is not allowed")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 45_000
            setRequestProperty("Cookie", CookieManager.getInstance().getCookie(url).orEmpty())
            setRequestProperty("User-Agent", webViewUserAgent)
            setRequestProperty("Accept", "application/pdf,text/html,text/plain,*/*")
        }
        val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        val bytes = stream?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_REPORT_BYTES) throw IllegalStateException("Report response exceeded 25 MB")
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        } ?: ByteArray(0)
        if (connection.responseCode >= 400) {
            throw IllegalStateException("NIMS report fetch returned ${connection.responseCode} (${contentType(connection.contentType.orEmpty())})")
        }
        return ReportFetchResult(connection.contentType.orEmpty(), connection.responseCode, SafeUrl.hostPath(connection.url.toString()), bytes)
    }

    private fun contentType(value: String): String = value.substringBefore(";").ifBlank { "application/octet-stream" }

    private fun helper(): HelperApiClient {
        validateHelperSettings()
        return HelperApiClient(settings.helperUrl()) { settings.apiKey() }
    }

    private fun publicHelper(): HelperApiClient {
        val url = HelperSettingsValidator.normalizeUrl(helperUrl.text.toString().ifBlank { settings.helperUrl() })
        return HelperApiClient(url) { settings.apiKey() }
    }

    private fun validateHelperSettings() {
        HelperSettingsValidator.normalizeUrl(settings.helperUrl())
        if (settings.apiKey().isBlank()) throw IllegalStateException("Set Railway helper API key first.")
    }

    private fun evaluateCore(expression: String, callback: (JSONObject) -> Unit) {
        evaluateJson(expression) { callback(JSONObject(it)) }
    }

    private fun evaluateJson(expression: String, callback: (String) -> Unit) {
        val core = assets.open("nimsReportCore.js").bufferedReader().use { it.readText() }
        webView.evaluateJavascript("$core\n(function(){ return $expression; })();") { value ->
            callback(decodeJsString(value))
        }
    }

    private fun decodeJsString(value: String): String {
        return JSONArray("[$value]").getString(0)
    }

    private fun safeJson(json: JSONObject): String = json.toString(2)

    private fun renderSummary(summary: JSONObject) {
        val builder = StringBuilder()
        builder.appendLine("Summary")
        builder.appendLine("Source Reports")
        appendRows(builder, summary.optJSONArray("source_reports"), listOf("date_sent", "report_name", "type", "status", "notes"))
        builder.appendLine()
        builder.appendLine("Failed Reports")
        val failed = JSONArray()
        val source = summary.optJSONArray("source_reports") ?: JSONArray()
        for (i in 0 until source.length()) {
            val row = source.getJSONObject(i)
            if (row.optString("status") == "error" || row.optString("notes").isNotBlank()) failed.put(row)
        }
        appendRows(builder, failed, listOf("date_sent", "report_name", "notes"))
        builder.appendLine()
        builder.appendLine("Lab Trends")
        appendRows(builder, summary.optJSONObject("lab_trend_table")?.optJSONArray("rows"), listOf("parameter", "trend"))
        builder.appendLine()
        builder.appendLine("Cultures")
        appendRows(builder, summary.optJSONArray("culture_table"), listOf("collection_date", "culture_no", "specimen_no", "site_specimen", "status", "result", "growth", "organism", "comment", "sensitivity_summary"))
        builder.appendLine()
        builder.appendLine("Interpretation")
        val interpretation = summary.optJSONArray("interpretation") ?: JSONArray()
        for (i in 0 until interpretation.length()) builder.appendLine("- ${interpretation.optString(i)}")
        output.text = builder.toString()
    }

    private fun appendRows(builder: StringBuilder, rows: JSONArray?, keys: List<String>) {
        if (rows == null || rows.length() == 0) {
            builder.appendLine("No data")
            return
        }
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            builder.appendLine(keys.mapNotNull { key ->
                val value = row.optString(key)
                if (value.isBlank()) null else "$key=$value"
            }.joinToString(" | "))
        }
    }

    private fun isParsedReportValid(report: JSONObject): Boolean {
        val parameters = report.optJSONArray("parameters")?.length() ?: 0
        val cultures = report.optJSONArray("culture_results")?.length() ?: 0
        val culture = report.optJSONObject("culture")
        return parameters > 0 || cultures > 0 || (culture != null && culture.length() > 0)
    }

    private fun errorReport(row: JSONObject, error: String): JSONObject {
        return JSONObject()
            .put("report_name", row.optString("report_name"))
            .put("date_sent", row.optString("date_sent"))
            .put("report_type", row.optString("report_type", "other"))
            .put("report_tags", row.optJSONArray("report_tags") ?: JSONArray().put("other"))
            .put("parameters", JSONArray())
            .put("errors", JSONArray().put(error))
    }

    private fun safeReportKey(transientArg: String, row: JSONObject): String {
        val input = listOf(
            transientArg,
            row.optString("date_sent"),
            row.optString("report_name"),
            row.optString("department")
        ).joinToString("|")
        val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "report_key:$hash"
    }

    private fun log(message: String) {
        runOnUiThread { output.text = "${output.text}\n$message" }
    }

    private fun setState(state: AppState, message: String) {
        appState = state
        log("${state.name}: $message")
    }

    private fun copyOutput() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("NIMS Fast Summary", output.text))
        log("Output copied")
    }

    companion object {
        private const val NIMS_LOGIN_URL = "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action"
        private const val MAX_REPORT_BYTES = 25 * 1024 * 1024
    }
}

data class ReportFetchResult(
    val contentType: String,
    val statusCode: Int,
    val finalUrlSafe: String,
    val bytes: ByteArray
)
