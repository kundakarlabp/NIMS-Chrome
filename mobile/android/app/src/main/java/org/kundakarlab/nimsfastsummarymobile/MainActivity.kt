package org.kundakarlab.nimsfastsummarymobile

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
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

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SecureSettings(this)
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
            addButton("Clear Mapping") { mapping = null; log("Mapping cleared") }
            addButton("Clear Helper Settings") { settings.clear(); helperUrl.setText(""); helperKey.setText(""); log("Helper settings cleared") }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(webView, LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(controls)
            addView(ScrollView(this@MainActivity).apply { addView(output) }, LinearLayout.LayoutParams.MATCH_PARENT, 260)
        }
        setContentView(root)
        webView.loadUrl(NIMS_LOGIN_URL)
    }

    private fun LinearLayout.addButton(label: String, onClick: () -> Unit) {
        addView(Button(context).apply {
            text = label
            setOnClickListener { onClick() }
        })
    }

    private fun saveHelperSettings() {
        settings.saveHelperUrl(helperUrl.text.toString())
        settings.saveApiKey(helperKey.text.toString())
        helperKey.setText("")
        helperKey.hint = "API key saved; enter only to replace"
        log("Helper settings saved")
    }

    private fun testHelper() {
        Thread {
            runCatching { helper().health() }
                .onSuccess { log("Helper connection ok") }
                .onFailure { log("Helper connection failed: ${it.message}") }
        }.start()
    }

    private fun diagnosePage() {
        evaluateCore("JSON.stringify(NimsReportCore.diagnosePage(document))") { json ->
            log("Diagnosis: ${safeJson(json)}")
        }
    }

    private fun discoverMapping() {
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
                    log("Mapping ready: ${template.optString("endpoint")} params=${template.optJSONArray("queryParamNames")}")
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
        evaluateJson("JSON.stringify(NimsReportCore.rowsFromBestFrame(document))") { rowsText ->
            val rows = JSONArray(rowsText)
            evaluateJson("JSON.stringify(NimsReportCore.selectRowsForMode(${rows}, '$mode'))") { selectedText ->
                val selected = JSONArray(selectedText)
                log("Selected ${selected.length()} reports")
                Thread { fetchParseSummarize(mode, selected, currentMapping) }.start()
            }
        }
    }

    private fun fetchParseSummarize(mode: String, selected: JSONArray, template: ReportTemplate) {
        val parsedReports = JSONArray()
        for (index in 0 until selected.length()) {
            val row = selected.getJSONObject(index)
            runOnUiThread { log("Fetching ${index + 1}/${selected.length()}") }
            val report = runCatching {
                val transient = transientArgFor(row)
                val url = NimsReportTemplate.directReportUrl(template, transient)
                val bytes = fetchWithWebViewCookies(url)
                val payload = JSONObject()
                    .put("report_id", safeReportKey(transient, row))
                    .put("report_name", row.optString("report_name"))
                    .put("date_sent", row.optString("date_sent"))
                    .put("source_url", SafeUrl.hostPath(url))
                    .put("content_type", contentType(bytes.first))
                    .put("pdf_base64", Base64.encodeToString(bytes.second, Base64.NO_WRAP))
                helper().parseReport(payload)
            }.getOrElse {
                JSONObject()
                    .put("report_name", row.optString("report_name"))
                    .put("date_sent", row.optString("date_sent"))
                    .put("report_type", row.optString("report_type", "other"))
                    .put("report_tags", row.optJSONArray("report_tags") ?: JSONArray().put("other"))
                    .put("parameters", JSONArray())
                    .put("errors", JSONArray().put(it.message ?: "direct fetch failed"))
            }
            parsedReports.put(report)
        }
        val summaryMode = when (mode) {
            "bulk_full" -> "full"
            "bulk_cultures_only" -> "cultures_only"
            else -> "fast"
        }
        val summary = helper().summarize(JSONObject().put("mode", summaryMode).put("reports", parsedReports))
        runOnUiThread { renderSummary(summary) }
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

    private fun fetchWithWebViewCookies(url: String): Pair<String, ByteArray> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 45_000
            setRequestProperty("Cookie", CookieManager.getInstance().getCookie(url).orEmpty())
            setRequestProperty("User-Agent", webView.settings.userAgentString)
            setRequestProperty("Accept", "application/pdf,text/html,text/plain,*/*")
        }
        val bytes = connection.inputStream.use { it.readBytes() }
        return Pair(connection.contentType.orEmpty(), bytes)
    }

    private fun contentType(value: String): String = value.substringBefore(";").ifBlank { "application/octet-stream" }

    private fun helper(): HelperApiClient = HelperApiClient(settings.helperUrl()) { settings.apiKey() }

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
        builder.appendLine(summary.toString(2))
        output.text = builder.toString()
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

    companion object {
        private const val NIMS_LOGIN_URL = "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action"
    }
}
