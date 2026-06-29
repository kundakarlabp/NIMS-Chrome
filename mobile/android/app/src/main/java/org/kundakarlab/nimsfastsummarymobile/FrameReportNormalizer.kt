package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Converts the all-frames WebView announcement into the minimal runtime shape
 * consumed by MainActivity. Raw DOM strings are discarded immediately after
 * the one-argument printReport value is extracted in memory.
 */
object FrameReportNormalizer {
    private const val DIRECT_REPORT_PATH =
        "/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt"

    private val allowedHosts = setOf("nimsts.edu.in", "www.nimsts.edu.in")
    private val contractPaths = listOf(
        "viewcrnowisereportprocess.cnt",
        "invresultreportprintingcrnowise.cnt"
    )

    fun normalize(input: JSONObject, currentWebViewUrl: String, onIgnored: (String) -> Unit = {}): JSONObject? {
        val normalized = input
        val rows = normalized.optJSONArray("rows") ?: JSONArray()
        if (rows.length() == 0) return normalized

        val framePath = normalized.optString("href").lowercase()
        if (contractPaths.none(framePath::contains)) {
            onIgnored("Ignored report-like rows outside the CR-wise result frame")
            return null
        }

        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.optString("transientPrintReportArg").isBlank()) {
                extractSinglePrintReportArg(row.optString("onclick"))
                    .takeIf(String::isNotBlank)
                    ?.let { row.put("transientPrintReportArg", it) }
            }
            row.remove("onclick")
            row.remove("href")
            row.remove("source_url")
            row.remove("raw_row_text")
        }

        if (normalized.optJSONObject("template") == null) {
            safeOrigin(currentWebViewUrl)?.let { origin ->
                normalized.put(
                    "template",
                    JSONObject()
                        .put("origin", origin)
                        .put("pathname", DIRECT_REPORT_PATH)
                        .put("modeParamName", "hmode")
                        .put("modeParamValue", "PRINTREPORT")
                        .put("argumentParameterName", "fileName")
                )
            }
        }
        return normalized
    }

    // BUG FIX (same defect as nimsAndroidFrameBridge.js's old firstPrintReportButton):
    // matchEntire with ^...$ anchors required the WHOLE onclick attribute to be
    // exactly printReport('x') or printReport('x');. Real NIMS markup can wrap
    // the call (observed live: "return printReport('x.pdf');"), which a fully
    // anchored pattern rejects outright. This is normally a fallback (the JS
    // side now populates transientPrintReportArg directly in the common case),
    // but it must not silently fail the same way if it's ever the only source.
    // find() + explicit function-name/arg-shape checks instead of matchEntire.
    internal fun extractSinglePrintReportArg(onclick: String): String {
        val callPattern = Regex(
            pattern = """(?is)printReport\s*\(\s*(['\"])(.*?)\1\s*\)"""
        )
        val quoted = callPattern.find(onclick)?.groupValues?.getOrNull(2)
        if (!quoted.isNullOrBlank()) {
            return quoted
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }

        val unquotedPattern = Regex(
            pattern = """(?is)printReport\s*\(\s*([^,()'\"]+)\s*\)"""
        )
        val unquoted = unquotedPattern.find(onclick)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        return unquoted.takeIf { it.isNotBlank() && !it.equals("null", true) && !it.equals("undefined", true) }.orEmpty()
    }

    private fun safeOrigin(value: String): String? = runCatching {
        val uri = URI(value)
        val host = uri.host?.lowercase().orEmpty()
        if (uri.scheme != "https" || host !in allowedHosts) null else "https://$host"
    }.getOrNull()
}
