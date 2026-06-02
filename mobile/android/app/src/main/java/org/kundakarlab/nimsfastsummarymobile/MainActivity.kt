package org.kundakarlab.nimsfastsummarymobile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Base64
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
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
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var helperPanel: LinearLayout
    private lateinit var logPanel: ScrollView
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
        statusText = TextView(this).apply {
            text = "NIMS Fast Summary"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setSingleLine(true)
        }
        progressText = TextView(this).apply {
            text = "Ready"
            textSize = 12f
            setTextColor(Color.rgb(220, 235, 255))
            setSingleLine(true)
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.setSupportMultipleWindows(true)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.textZoom = 100
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            isFocusable = true
            isFocusableInTouchMode = true
            setInitialScale(85)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progressText.text = if (newProgress >= 100) "Loaded" else "Loading $newProgress%"
                }

                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                    val popup = WebView(view.context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(popupView: WebView, request: WebResourceRequest): Boolean {
                                webView.loadUrl(request.url.toString())
                                return true
                            }

                            override fun onPageFinished(popupView: WebView, url: String) {
                                if (url.isNotBlank() && url != "about:blank") {
                                    webView.loadUrl(url)
                                }
                            }
                        }
                    }
                    val transport = resultMsg.obj as WebView.WebViewTransport
                    transport.webView = popup
                    resultMsg.sendToTarget()
                    return true
                }
            }
            webViewClient = NimsWebViewClient { safeUrl ->
                statusText.text = safeUrl.ifBlank { "NIMS Fast Summary" }
            }
        }
        webViewUserAgent = webView.settings.userAgentString
        output = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(20, 28, 35))
            setPadding(14, 10, 14, 10)
        }
        helperUrl = EditText(this).apply {
            hint = "https://your-service.up.railway.app"
            setText(settings.helperUrl())
        }
        helperKey = EditText(this).apply {
            hint = if (settings.apiKey().isBlank()) "Helper API key" else "API key saved; enter only to replace"
        }

        helperPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.rgb(245, 248, 252))
            addView(helperUrl)
            addView(helperKey)
            addButtonRow(
                button("Save Helper") { saveHelperSettings() },
                button("Test Helper") { testHelper() },
                button("Clear Helper") { settings.clear(); helperUrl.setText(""); helperKey.setText(""); log("Helper settings cleared") }
            )
        }
        logPanel = ScrollView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.WHITE)
            addView(output)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 8, 14, 8)
            setBackgroundColor(Color.rgb(16, 45, 78))
            addView(statusText)
            addView(progressText)
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 6, 8, 6)
            setBackgroundColor(Color.rgb(232, 238, 245))
            addView(button("Back") { if (webView.canGoBack()) webView.goBack() })
            addView(button("Forward") { if (webView.canGoForward()) webView.goForward() })
            addView(button("Reload") { webView.reload() })
            addView(button("NIMS Login") { webView.loadUrl(NIMS_LOGIN_URL) })
            addView(button("Zoom -") { webView.zoomOut() })
            addView(button("Zoom +") { webView.zoomIn() })
            addView(button("Diagnose") { diagnosePage() })
            addView(button("Discover") { discoverMapping() })
            addView(button("Test Fetch") { runMode("test_direct") })
            addView(button("Fast") { runMode("bulk_fast") })
            addView(button("Cultures") { runMode("bulk_cultures_only") })
            addView(button("Full") { runMode("bulk_full") })
            addView(button("Helper") { togglePanel(helperPanel) })
            addView(button("Log") { togglePanel(logPanel) })
            addView(button("Copy") { copyOutput() })
            addView(button("Clear Log") { output.text = ""; setState(AppState.NIMS_LOGIN, "Output cleared") })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(250, 252, 255))
            addView(header)
            addView(webView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(actions)
            })
            addView(helperPanel)
            addView(logPanel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 220))
        }
        setContentView(root)
        setState(if (settings.helperUrl().isBlank() || !settings.hasApiKey()) AppState.NEED_HELPER_SETTINGS else AppState.HELPER_READY, "Open NIMS and login manually.")
        webView.loadUrl(NIMS_LOGIN_URL)
        webView.requestFocus()
    }

    private fun LinearLayout.addButton(label: String, onClick: () -> Unit) {
        addView(button(label, onClick))
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 12f
            minHeight = 42
            minWidth = 0
            isAllCaps = false
            setPadding(16, 4, 16, 4)
            setOnClickListener { onClick() }
        }
    }

    private fun LinearLayout.addButtonRow(vararg buttons: Button) {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach { addView(it) }
        })
    }

    private fun togglePanel(view: View) {
        view.visibility = if (view.visibility == View.VISIBLE) View.GONE else View.VISIBLE
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
                    JSONArray().apply {
                        selectedAll.optJSONObject(0)?.let { put(it) }
                    }
                } else {
                    selectedAll
                }
                log("Selected ${selected.length()} reports")
                prepareReportRequests(selected, currentMapping) { prepared ->
                    Thread { fetchParseSummarize(mode, prepared) }.start()
                }
            }
        }
    }

    private fun prepareReportRequests(selected: JSONArray, template: ReportTemplate, callback: (List<PreparedReportRequest>) -> Unit) {
        val prepared = mutableListOf<PreparedReportRequest>()
        fun step(index: Int) {
            if (index >= selected.length()) {
                callback(prepared)
                return
            }
            val row = selected.optJSONObject(index)
            if (row == null) {
                step(index + 1)
                return
            }
            evaluateCore("JSON.stringify(NimsReportCore.transientPayloadForRow(${row}, document))") { payload ->
                val transient = payload.optString("transientPrintReportArg")
                if (transient.isBlank()) {
                    log("Skipping ${row.optString("report_name", "report")}: Required report argument missing")
                } else {
                    prepared.add(PreparedReportRequest(row, transient, NimsReportTemplate.directReportUrl(template, transient)))
                }
                step(index + 1)
            }
        }
        step(0)
    }

    private fun fetchParseSummarize(mode: String, prepared: List<PreparedReportRequest>) {
        setState(AppState.FETCHING, "Fetching reports")
        val parsedReports = JSONArray()
        if (mode == "test_direct") {
            val request = prepared.firstOrNull()
            if (request == null) {
                runOnUiThread { log("No report selected for Test Direct Fetch") }
                return
            }
            val report = fetchAndParseOne(request, 0, 1)
            parsedReports.put(report)
            val valid = isParsedReportValid(report)
            mappingValidated = valid
            if (!valid) {
                mappingValidated = false
                runOnUiThread { log(report.optJSONArray("errors")?.optString(0) ?: "Test Direct Fetch did not parse a report") }
                return
            }
        } else {
            val results = ReportFetchQueue(concurrency = 3).run(prepared.withIndex().toList()) { indexed ->
                fetchAndParseOne(indexed.value, indexed.index, prepared.size)
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
        runOnUiThread { log("Parsing ${parsedReports.length()}/${prepared.size}") }
        runCatching {
            helper().summarize(JSONObject().put("mode", summaryMode).put("reports", parsedReports))
        }
            .onSuccess { summary ->
                runOnUiThread {
                    renderSummary(summary)
                    setState(AppState.SUMMARY_READY, "Done")
                }
            }
            .onFailure { error ->
                runOnUiThread { setState(AppState.ERROR, "Summary failed: ${error.message ?: "unknown error"}") }
            }
    }

    private fun fetchAndParseOne(request: PreparedReportRequest, index: Int, total: Int): JSONObject {
        val row = request.row
        return runCatching {
            validateHelperSettings()
            runOnUiThread { log("Fetching ${index + 1}/$total") }
            val transient = request.transientArg
            val url = request.directUrl
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
            val row = source.optJSONObject(i) ?: continue
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
            val row = rows.optJSONObject(i) ?: continue
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

data class PreparedReportRequest(
    val row: JSONObject,
    val transientArg: String,
    val directUrl: String
)
