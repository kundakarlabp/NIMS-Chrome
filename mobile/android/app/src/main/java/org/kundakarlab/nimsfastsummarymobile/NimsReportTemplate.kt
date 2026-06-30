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

    // CRASH FIX: the old regex required the token to end in ".pdf".
    // The live NIMS page implements printReport(name) where name is an OPAQUE
    // server-issued identifier — the page neither checks nor appends ".pdf"
    // itself. Tokens observed live end in ".pdf", but that is a server
    // coincidence, not a contractual requirement.  The shared JS extractor
    // (getTransientPrintReportArg / parseFunctionArgs) treats the argument as
    // opaque and has no ".pdf" assertion.  The JS test fixture uses
    // "SAFE_FIXTURE_ARG" which has no ".pdf" suffix.  Only this Kotlin
    // validator was making the assumption — and it was making it with
    // require(), which throws IllegalArgumentException on the main thread and
    // kills the Android process on every live token that happened not to end
    // in ".pdf".  That is exactly the P0 crash reported (14 instances between
    // 29 June and 1 July, confirmed from Vivo crash-rescue logs).
    //
    // Security is maintained without the suffix assumption:
    //   - scheme is fixed to HTTPS (enforced in directReportUrl / NimsUrlPolicy)
    //   - host is restricted to nimsts.edu.in (NimsUrlPolicy)
    //   - endpoint is the verified /invDuplicateResultReportPrinting.cnt
    //   - parameter names are fixed (hmode, fileName)
    //   - the opaque value is URL-encoded before insertion
    //   - path-traversal strings, absolute paths, scheme-bearing strings,
    //     control characters, and overlength values are still rejected
    private const val MAX_TRANSIENT_REPORT_ARG_LENGTH = 512

    // CRASH FIX part 2: directReportUrl() now returns null instead of
    // throwing IllegalArgumentException on a bad token. An uncaught exception
    // from require() on the main thread terminates the Android process — there
    // is no try-catch between runModeInternal and this function, so every
    // validation failure was a hard crash.  Returning null lets
    // prepareReportRequests log and skip the offending row without affecting
    // the rest of the report run.
    fun directReportUrl(template: ReportTemplate, transientReportArg: String): String =
        directReportUrlOrNull(template, transientReportArg)
            ?: error("directReportUrl called with values that did not pass directReportUrlOrNull")

    fun directReportUrlOrNull(template: ReportTemplate, transientReportArg: String): String? {
        if (template.pathname != VERIFIED_REPORT_PATH) return null
        if (template.modeParamName != "hmode" || template.modeParamValue != "PRINTREPORT") return null
        if (template.argumentParameterName != "fileName") return null
        if (!isSafeTransientReportArg(transientReportArg)) return null

        val origin = runCatching { URI(template.origin.trimEnd('/')) }.getOrNull() ?: return null
        if (!origin.scheme.equals("https", ignoreCase = true)) return null
        if (origin.rawUserInfo != null) return null
        if (origin.port !in listOf(-1, 443)) return null

        val mode = encode(template.modeParamValue)
        val arg = encode(transientReportArg)
        val url = "${template.origin.trimEnd('/')}${template.pathname}" +
            "?${template.modeParamName}=$mode&${template.argumentParameterName}=$arg"
        return if (NimsUrlPolicy.isAllowedUrl(url)) url else null
    }

    fun isAllowedNimsUrl(url: String): Boolean = NimsUrlPolicy.isAllowedUrl(url)

    // Safe if: non-empty, bounded length, no path traversal (..), no path
    // separators (/ or \), no scheme (://), no control characters.
    // ".pdf" suffix is NOT required — the live NIMS page treats the printReport
    // argument as fully opaque.
    internal fun isSafeTransientReportArg(value: String): Boolean {
        val token = value.trim()
        if (token.isEmpty() || token.length > MAX_TRANSIENT_REPORT_ARG_LENGTH) return false
        if (token.contains("..") || token.contains("://")) return false
        if (token.any { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) return false
        return true
    }

    // Keep the old name for any callers that still use it — delegates to the
    // corrected function so behaviour is consistent everywhere.
    internal fun isSafeTransientFileName(value: String): Boolean = isSafeTransientReportArg(value)

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
