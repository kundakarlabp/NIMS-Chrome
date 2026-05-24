package org.kundakarlab.nimsfastsummarymobile

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
    fun directReportUrl(template: ReportTemplate, transientFileName: String): String {
        val mode = encode(template.modeParamValue)
        val fileName = encode(transientFileName)
        return "${template.origin.trimEnd('/')}${template.pathname}?${template.modeParamName}=$mode&${template.argumentParameterName}=$fileName"
    }

    fun isAllowedNimsUrl(url: String): Boolean {
        return try {
            val uri = URI(url)
            uri.scheme == "https" &&
                (uri.host == "nimsts.edu.in" || uri.host == "www.nimsts.edu.in")
        } catch (_: Exception) {
            false
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
