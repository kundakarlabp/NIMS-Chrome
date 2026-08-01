package org.kundakarlab.nimsfastsummarymobile

object ReportResponseClassifier {
    fun classify(statusCode: Int, contentType: String, bytes: ByteArray): String {
        // Authentication status is authoritative even when the response body is
        // empty, generic, or mislabeled by an intermediary. Returning the same
        // classification as login HTML lets MainActivity use its existing
        // session-recovery path instead of showing a generic fetch error.
        if (statusCode == 401 || statusCode == 403) return "html_login_or_session"
        if (bytes.isEmpty()) return "empty_response"
        if (statusCode in setOf(404, 405, 500)) return "wrong_endpoint"
        val lowerType = contentType.lowercase()
        if (hasPdfSignature(bytes)) return "pdf_report"

        val prefixSize = minOf(bytes.size, 128 * 1024)
        val text = bytes.decodeToString(endIndex = prefixSize).lowercase()
        if (isLoginOrExpiredHtml(text)) return "html_login_or_session"
        if (isReportListHtml(text)) return "html_report_list"

        val reportLike = listOf(
            "hemoglobin", "haemoglobin", "platelet", "creatinine", "bilirubin", "culture",
            "esr", "erythrocyte sedimentation", "aptt", "prothrombin", "inr",
            "genexpert", "cbnaat", "histopathology", "diagnosis", "special stain"
        ).any(text::contains) && Regex("\\d+(?:\\.\\d+)?").containsMatchIn(text)
        val htmlOrText = lowerType.contains("text/html") || lowerType.contains("text/plain") ||
            text.startsWith("<!doctype") || text.startsWith("<html")
        if (htmlOrText && reportLike) return "html_report_content"

        // Do not trust a declared PDF content type without a PDF signature. NIMS
        // can return HTML/login/list pages under a stale PDF URL, which otherwise
        // produces a blank or black viewer.
        if (lowerType.contains("application/pdf")) return "invalid_pdf_response"
        return "unsupported_content_type"
    }

    internal fun hasPdfSignature(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return false
        var index = 0
        while (index < minOf(bytes.size, 16) && bytes[index].toInt().toChar().isWhitespace()) index++
        return index + 5 <= bytes.size &&
            bytes.copyOfRange(index, index + 5).contentEquals("%PDF-".toByteArray())
    }

    internal fun isReportListHtml(html: String): Boolean {
        val text = html.lowercase()
        val hasResultTable = listOf("report_list", "printreport(", "cr no wise result", "patcrno").any(text::contains)
        val hasMultipleReportLinks = Regex("printreport\\s*\\(", RegexOption.IGNORE_CASE)
            .findAll(html)
            .take(2)
            .count() >= 2
        return hasResultTable && (hasMultipleReportLinks || text.contains("investigation result"))
    }

    internal fun isLoginOrExpiredHtml(html: String): Boolean {
        val text = html.lowercase()
        if (listOf(
                "session expired",
                "session has expired",
                "your session is expired",
                "invalid session",
                "please login again",
                "please log in again"
            ).any(text::contains)
        ) return true

        val hasPasswordInput = Regex(
            "<input[^>]+(?:type\\s*=\\s*['\"]?password|name\\s*=\\s*['\"]?(?:password|passwd|userpassword))",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(html)
        val hasCredentialForm = Regex(
            "<form[^>]+(?:loginlogin\\.action|hissso/login)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(html) && Regex(
            "<input[^>]+(?:name|id)\\s*=\\s*['\"]?(?:username|userid|user_name|loginid|captcha|otp)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(html)
        return hasPasswordInput || hasCredentialForm
    }
}
