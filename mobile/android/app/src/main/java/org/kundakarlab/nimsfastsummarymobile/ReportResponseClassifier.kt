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
        if (text.contains("password") || text.contains("captcha") || text.contains("otp") || text.contains("session expired") || text.contains("login")) {
            return "html_login_or_session"
        }
        val reportLike = listOf("hemoglobin", "platelet", "creatinine", "bilirubin", "culture", "report").any { text.contains(it) } &&
            Regex("\\d+(?:\\.\\d+)?").containsMatchIn(text)
        if ((lowerType.contains("text/html") || lowerType.contains("text/plain") || text.startsWith("<!doctype") || text.startsWith("<html")) && reportLike) {
            return "html_report_content"
        }
        return "unsupported_content_type"
    }
}
