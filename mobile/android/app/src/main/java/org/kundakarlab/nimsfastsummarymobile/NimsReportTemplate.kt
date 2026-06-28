package org.kundakarlab.nimsfastsummarymobile

import org.kundakarlab.nimsfastsummarymobile.security.NimsUrlPolicy
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ReportTemplate(
    val origin: String,
    val pathname: String,
    val modeParamName: String = "hmode",
    val modeParamValue: String = "PRINTREPORT",
    val argumentParameterName: String = "fileName"
)

object NimsReportTemplate {
    const val VERIFIED_REPORT_PATH = "/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt"

    private val transientPdfName = Regex("^[A-Za-z0-9_-]+\\.pdf$", RegexOption.IGNORE_CASE)

    fun directReportUrl(template: ReportTemplate, transientFileName: String): String {
        require(template.pathname == VERIFIED_REPORT_PATH) { "Unexpected NIMS report endpoint" }
        require(template.modeParamName == "hmode" && template.modeParamValue == "PRINTREPORT") {
            "Unexpected NIMS report mode contract"
        }
        require(template.argumentParameterName == "fileName") { "Unexpected NIMS report argument contract" }
        require(isSafeTransientFileName(transientFileName)) { "Invalid transient NIMS report token" }

        val origin = runCatching { URI(template.origin.trimEnd('/')) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid NIMS report origin")
        require(origin.scheme.equals("https", ignoreCase = true) && origin.rawUserInfo == null && origin.port in listOf(-1, 443)) {
            "Invalid NIMS report origin"
        }

        val mode = encode(template.modeParamValue)
        val fileName = encode(transientFileName)
        val url = "${template.origin.trimEnd('/')}${template.pathname}?${template.modeParamName}=$mode&${template.argumentParameterName}=$fileName"
        require(NimsUrlPolicy.isAllowedUrl(url)) { "NIMS report URL is not allowed" }
        return url
    }

    fun isAllowedNimsUrl(url: String): Boolean = NimsUrlPolicy.isAllowedUrl(url)

    internal fun isSafeTransientFileName(value: String): Boolean {
        val token = value.trim()
        if (token.length !in 5..256) return false
        if (token.contains("..") || token.any { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) return false
        return transientPdfName.matches(token)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
