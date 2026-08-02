package org.kundakarlab.nimsfastsummarymobile.domain.recovery

import java.util.concurrent.ConcurrentHashMap

enum class ReportIssueKind {
    TRANSIENT_NETWORK,
    SESSION_EXPIRED,
    PARSE_INCOMPLETE,
    UNSUPPORTED,
    DUPLICATE,
    UNKNOWN
}

data class ReportIssue(
    val reportId: String,
    val reportName: String,
    val dateSent: String,
    val kind: ReportIssueKind,
    val userMessage: String,
    val retryable: Boolean,
    val attempts: Int = 0
)

data class ClinicianCorrection(
    val reportId: String,
    val field: String,
    val value: String,
    val unit: String = "",
    val resultDate: String = "",
    val enteredAtEpochMs: Long = System.currentTimeMillis()
)

/**
 * In-memory issue and correction registry. It intentionally persists neither
 * source text nor credentials and is cleared when the patient/session is reset.
 */
class ReportIssueController {
    private val issues = ConcurrentHashMap<String, ReportIssue>()
    private val corrections = ConcurrentHashMap<String, MutableList<ClinicianCorrection>>()

    fun recordFailure(
        reportId: String,
        reportName: String,
        dateSent: String,
        technicalMessage: String
    ): ReportIssue {
        val previous = issues[reportId]
        val issue = classify(reportId, reportName, dateSent, technicalMessage)
            .copy(attempts = (previous?.attempts ?: 0) + 1)
        issues[reportId] = issue
        return issue
    }

    fun resolve(reportId: String) {
        issues.remove(reportId)
    }

    fun retryableIssues(): List<ReportIssue> = issues.values
        .filter { it.retryable }
        .sortedWith(compareBy<ReportIssue> { it.kind }.thenBy { it.dateSent }.thenBy { it.reportName })

    fun allIssues(): List<ReportIssue> = issues.values.sortedBy { it.reportName }

    fun addCorrection(correction: ClinicianCorrection) {
        corrections.computeIfAbsent(correction.reportId) { mutableListOf() }.add(correction)
        issues.remove(correction.reportId)
    }

    fun correctionsFor(reportId: String): List<ClinicianCorrection> =
        corrections[reportId]?.toList().orEmpty()

    fun undoLastCorrection(reportId: String): ClinicianCorrection? {
        val values = corrections[reportId] ?: return null
        if (values.isEmpty()) return null
        val removed = values.removeAt(values.lastIndex)
        if (values.isEmpty()) corrections.remove(reportId)
        return removed
    }

    fun clear() {
        issues.clear()
        corrections.clear()
    }

    companion object {
        fun classify(
            reportId: String,
            reportName: String,
            dateSent: String,
            technicalMessage: String
        ): ReportIssue {
            val lower = technicalMessage.lowercase()
            val kind = when {
                listOf("failed to connect", "connection abort", "connection reset", "timeout", "stream was reset").any(lower::contains) -> ReportIssueKind.TRANSIENT_NETWORK
                "session" in lower && ("expired" in lower || "login" in lower) -> ReportIssueKind.SESSION_EXPIRED
                "no high-confidence" in lower || "parse incomplete" in lower || "not confidently parsed" in lower -> ReportIssueKind.PARSE_INCOMPLETE
                "unsupported" in lower || "image without extractable text" in lower || "ocr is not enabled" in lower -> ReportIssueKind.UNSUPPORTED
                "duplicate" in lower -> ReportIssueKind.DUPLICATE
                else -> ReportIssueKind.UNKNOWN
            }
            val message = when (kind) {
                ReportIssueKind.TRANSIENT_NETWORK -> "Connection interrupted. Retry this report."
                ReportIssueKind.SESSION_EXPIRED -> "Session expired. Login again, then retry."
                ReportIssueKind.PARSE_INCOMPLETE -> "Result needs review."
                ReportIssueKind.UNSUPPORTED -> "Open the source PDF to review this report."
                ReportIssueKind.DUPLICATE -> "Duplicate source row ignored."
                ReportIssueKind.UNKNOWN -> "This report could not be processed."
            }
            return ReportIssue(
                reportId = reportId,
                reportName = reportName,
                dateSent = dateSent,
                kind = kind,
                userMessage = message,
                retryable = kind in setOf(ReportIssueKind.TRANSIENT_NETWORK, ReportIssueKind.SESSION_EXPIRED, ReportIssueKind.PARSE_INCOMPLETE)
            )
        }
    }
}
