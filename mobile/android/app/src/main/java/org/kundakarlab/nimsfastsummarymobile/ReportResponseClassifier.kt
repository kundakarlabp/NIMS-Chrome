package org.kundakarlab.nimsfastsummarymobile

object ReportResponseClassifier {
    fun classify(statusCode: Int, contentType: String, bytes: ByteArray): String {
        if (bytes.isEmpty()) return "empty_response"
        if (statusCode in setOf(404, 405, 500)) return "wrong_endpoint"
        val lowerType = contentType.lowercase()
        if (lowerType.contains("application/pdf") || bytes.take(4).toByteArray().contentEquals("%PDF".toByteArray())) {
            return "pdf_report"
        }
        val prefixSize = minOf(bytes.size, 128 * 1024)
        val text = bytes.decodeToString(endIndex = prefixSize).lowercase()
        if (isLoginOrExpiredHtml(text)) {
            return "html_login_or_session"
        }
        val reportLike = listOf(
            "hemoglobin", "platelet", "creatinine", "bilirubin", "culture", "report",
            "esr", "erythrocyte sedimentation", "aptt", "prothrombin", "inr",
            "genexpert", "cbnaat", "histopathology", "diagnosis", "special stain"
        ).any { text.contains(it) } &&
            Regex("\\d+(?:\\.\\d+)?").containsMatchIn(text)
        if ((lowerType.contains("text/html") || lowerType.contains("text/plain") || text.startsWith("<!doctype") || text.startsWith("<html")) && reportLike) {
            return "html_report_content"
        }
        return "unsupported_content_type"
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

        // Authenticated NIMS pages routinely contain login/logout navigation and
        // login-related JavaScript. Treat the page as a login response only when
        // it contains an actual credential challenge, never from the word
        // "login" alone.
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
