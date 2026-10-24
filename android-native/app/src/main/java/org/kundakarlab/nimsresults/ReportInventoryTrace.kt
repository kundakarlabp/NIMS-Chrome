package org.kundakarlab.nimsresults

import java.util.concurrent.ConcurrentHashMap

/** Redacted source-index diagnostics. Store only enumerated states and counts;
 * never URLs, HTML, lab values, cookies, CR identifiers or patient names. */
object ReportInventoryTrace {
    private val counters = ConcurrentHashMap<String, Int>()
    private val allowed = setOf(
        "rest_attempt", "rest_nonempty", "rest_empty", "rest_auth_required",
        "rest_http_error", "rest_parse_error", "html_attempt",
        "html_form_found", "html_form_missing", "html_table_rows",
        "html_no_report_rows", "html_verified_rows", "html_auth_required",
        "html_http_error", "html_identity_mismatch", "html_parse_error",
        "html_response_without_cr", "all_sources_unavailable",
        "webview_attempt", "webview_unavailable", "webview_verified_rows",
        "webview_empty", "webview_identity_mismatch", "webview_parse_error"
    )
    fun event(code: String, count: Int = 1) {
        if (code in allowed) counters.merge(code, count.coerceIn(1, 10000), Int::plus)
    }
    fun snapshot(): String =
        counters.toSortedMap().entries.joinToString(",") { it.key + "=" + it.value }
    fun reset() { counters.clear() }
}
