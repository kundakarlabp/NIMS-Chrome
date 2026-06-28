package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NimsReportTemplateTest {
    @Test
    fun directReportUrlUsesVerifiedGetContract() {
        val template = ReportTemplate(
            origin = "https://www.nimsts.edu.in",
            pathname = NimsReportTemplate.VERIFIED_REPORT_PATH
        )
        val token = "123456_789012_20260628153000.pdf"
        val url = NimsReportTemplate.directReportUrl(template, token)
        assertEquals(
            "https://www.nimsts.edu.in${NimsReportTemplate.VERIFIED_REPORT_PATH}?hmode=PRINTREPORT&fileName=$token",
            url
        )
        assertFalse(url.contains("/HISClinical/"))
    }

    @Test
    fun directReportUrlRejectsUnverifiedEndpointAndUnsafeTokens() {
        val valid = ReportTemplate(
            origin = "https://www.nimsts.edu.in",
            pathname = NimsReportTemplate.VERIFIED_REPORT_PATH
        )
        assertTrue(runCatching { NimsReportTemplate.directReportUrl(valid, "../secret.pdf") }.isFailure)
        assertTrue(runCatching { NimsReportTemplate.directReportUrl(valid, "https://example.com/x.pdf") }.isFailure)
        assertTrue(runCatching { NimsReportTemplate.directReportUrl(valid, "not-a-pdf.txt") }.isFailure)

        val wrongPath = valid.copy(pathname = "/HISClinical/investigationDesk/viewInvestigation.cnt")
        assertTrue(runCatching { NimsReportTemplate.directReportUrl(wrongPath, "123_456.pdf") }.isFailure)
    }

    @Test
    fun safeUrlStripsQuery() {
        assertEquals(
            "www.nimsts.edu.in/HISInvestigationG5/path",
            SafeUrl.stripQuery("https://www.nimsts.edu.in/HISInvestigationG5/path?fileName=SECRET")
        )
    }

    @Test
    fun nimsNavigationIsRestrictedToNimsHosts() {
        assertTrue(NimsReportTemplate.isAllowedNimsUrl("https://www.nimsts.edu.in/AHIMSG5/home"))
        assertTrue(NimsReportTemplate.isAllowedNimsUrl("https://nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt"))
        assertFalse(NimsReportTemplate.isAllowedNimsUrl("https://www.nimsts.edu.in/other/path"))
        assertFalse(NimsReportTemplate.isAllowedNimsUrl("https://example.com/"))
    }

    @Test
    fun helperUrlNormalizationRequiresHttpsOutsideDebugLocal() {
        assertEquals("https://example.up.railway.app", HelperSettingsValidator.normalizeUrl(" https://example.up.railway.app/ ", debugBuild = false))
        assertTrue(runCatching { HelperSettingsValidator.normalizeUrl("http://10.0.2.2:8765/", debugBuild = true) }.isFailure)
        assertTrue(runCatching { HelperSettingsValidator.normalizeUrl("http://example.com", debugBuild = false) }.isFailure)
    }

    @Test
    fun reportResponseClassificationDetectsSafeCases() {
        assertEquals("pdf_report", ReportResponseClassifier.classify(200, "application/pdf", "%PDF-1.4".toByteArray()))
        assertEquals("empty_response", ReportResponseClassifier.classify(200, "application/pdf", ByteArray(0)))
        assertEquals("html_login_or_session", ReportResponseClassifier.classify(200, "text/html", "<input type=password> captcha".toByteArray()))
        assertEquals("html_report_content", ReportResponseClassifier.classify(200, "text/html", "<table><td>Hemoglobin</td><td>8.9</td></table>".toByteArray()))
        assertEquals("wrong_endpoint", ReportResponseClassifier.classify(404, "text/html", "not found".toByteArray()))
    }

    @Test
    fun reportResponseClassificationOnlyDecodesNonPdfPrefix() {
        val loginInPrefix = ByteArray(140 * 1024) { 'a'.code.toByte() }
        "password captcha".toByteArray().copyInto(loginInPrefix, destinationOffset = 1024)
        assertEquals("html_login_or_session", ReportResponseClassifier.classify(200, "text/plain", loginInPrefix))

        val loginAfterPrefix = ByteArray(140 * 1024) { 'a'.code.toByte() }
        "password captcha".toByteArray().copyInto(loginAfterPrefix, destinationOffset = 129 * 1024)
        assertEquals("unsupported_content_type", ReportResponseClassifier.classify(200, "text/plain", loginAfterPrefix))
    }

    @Test
    fun androidAssetSourceIncludesSharedWebCore() {
        val userDir = checkNotNull(System.getProperty("user.dir")) {
            "Expected user.dir system property for repository-root fixture lookup"
        }
        val repoRoot = generateSequence(File(userDir).absoluteFile) { current ->
            current.parentFile
        }.first { File(it, "shared/nims-web/nimsReportCore.js").isFile }
        assertTrue(File(repoRoot, "shared/nims-web/nimsReportCore.js").isFile)
        val buildGradle = File(repoRoot, "mobile/android/app/build.gradle.kts").readText()
        assertTrue(buildGradle.contains("\"../../../shared/nims-web\""))
    }
}
