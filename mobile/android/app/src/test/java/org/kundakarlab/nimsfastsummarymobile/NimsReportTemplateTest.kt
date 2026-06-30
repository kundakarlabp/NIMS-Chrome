package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NimsReportTemplateTest {

    private val validTemplate = ReportTemplate(
        origin = "https://www.nimsts.edu.in",
        pathname = NimsReportTemplate.VERIFIED_REPORT_PATH
    )

    // ────────────────────────────────────────────────────────────────────────
    // REGRESSION: the P0 crash
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun directReportUrlAcceptsOpaqueArgumentWithoutPdfSuffix() {
        // This is the exact case that produced the IllegalArgumentException
        // crash on the main thread (14 confirmed instances, Vivo crash-rescue
        // logs, 29 June – 1 July 2026).  The old validator required the token
        // to end in ".pdf"; the live NIMS page treats printReport(name) as
        // fully opaque and makes no such guarantee.
        val url = NimsReportTemplate.directReportUrl(validTemplate, "SAFE_FIXTURE_ARG")
        assertTrue(url.contains("fileName=SAFE_FIXTURE_ARG"))
    }

    @Test
    fun directReportUrlOrNullAcceptsOpaqueArgumentWithoutPdfSuffix() {
        val url = NimsReportTemplate.directReportUrlOrNull(validTemplate, "SAFE_FIXTURE_ARG")
        assertNotNull(url)
        assertTrue(url!!.contains("fileName=SAFE_FIXTURE_ARG"))
    }

    @Test
    fun directReportUrlOrNullNeverThrowsOnAnyInput() {
        // The crash was an IllegalArgumentException from require() on the main
        // thread. directReportUrlOrNull must return null, not throw, for any
        // input — including inputs that a future developer might introduce.
        val cases = listOf(
            "SAFE_FIXTURE_ARG", "../secret", "/absolute", "https://evil.com/x",
            "back\\slash", "\u0000null", "a".repeat(600), "", "normal.pdf"
        )
        for (input in cases) {
            runCatching { NimsReportTemplate.directReportUrlOrNull(validTemplate, input) }
                .onFailure { fail("directReportUrlOrNull threw for input \"$input\": $it") }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // .pdf-shaped tokens must still work (common live case)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun directReportUrlAcceptsPdfShapedArgument() {
        val token = "123456_789012_20260628153000.pdf"
        val url = NimsReportTemplate.directReportUrl(validTemplate, token)
        assertTrue(url.contains("fileName=$token"))
        assertFalse(url.contains("/HISClinical/"))
    }

    @Test
    fun directReportUrlBuildsCorrectGetUrl() {
        val token = "123456_789012_20260628153000.pdf"
        val url = NimsReportTemplate.directReportUrl(validTemplate, token)
        assertEquals(
            "https://www.nimsts.edu.in${NimsReportTemplate.VERIFIED_REPORT_PATH}" +
                "?hmode=PRINTREPORT&fileName=$token",
            url
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // Unsafe values must still be rejected
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun isSafeTransientReportArgRejectsUnsafeValues() {
        assertFalse(NimsReportTemplate.isSafeTransientReportArg("../secret"))
        assertFalse(NimsReportTemplate.isSafeTransientReportArg("/absolute/path"))
        assertFalse(NimsReportTemplate.isSafeTransientReportArg("https://example.com/file"))
        assertFalse(NimsReportTemplate.isSafeTransientReportArg("back\\slash"))
        assertFalse(NimsReportTemplate.isSafeTransientReportArg("\u0000nul"))
        assertFalse(NimsReportTemplate.isSafeTransientReportArg("a".repeat(600)))
        assertFalse(NimsReportTemplate.isSafeTransientReportArg(""))
    }

    @Test
    fun directReportUrlOrNullRejectsUnsafeTokens() {
        assertNull(NimsReportTemplate.directReportUrlOrNull(validTemplate, "../secret"))
        assertNull(NimsReportTemplate.directReportUrlOrNull(validTemplate, "https://example.com/x.pdf"))
        assertNull(NimsReportTemplate.directReportUrlOrNull(validTemplate, "/abs/path.pdf"))
        assertNull(NimsReportTemplate.directReportUrlOrNull(validTemplate, "back\\slash.pdf"))
    }

    @Test
    fun directReportUrlOrNullRejectsWrongEndpoint() {
        val wrong = validTemplate.copy(pathname = "/HISClinical/investigationDesk/viewInvestigation.cnt")
        assertNull(NimsReportTemplate.directReportUrlOrNull(wrong, "token.pdf"))
    }

    @Test
    fun directReportUrlOrNullRejectsWrongModeContract() {
        val wrong = validTemplate.copy(modeParamValue = "SOMETHING_ELSE")
        assertNull(NimsReportTemplate.directReportUrlOrNull(wrong, "token.pdf"))
    }

    @Test
    fun directReportUrlOrNullRejectsNonHttpsOrigin() {
        val wrong = validTemplate.copy(origin = "http://www.nimsts.edu.in")
        assertNull(NimsReportTemplate.directReportUrlOrNull(wrong, "token.pdf"))
    }

    // ────────────────────────────────────────────────────────────────────────
    // isSafeTransientReportArg positive cases
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun isSafeTransientReportArgAcceptsSafeForms() {
        assertTrue(NimsReportTemplate.isSafeTransientReportArg("SAFE_FIXTURE_ARG"))
        assertTrue(NimsReportTemplate.isSafeTransientReportArg("123456_789012_20260628153000.pdf"))
        assertTrue(NimsReportTemplate.isSafeTransientReportArg("abc-def_123"))
        assertTrue(NimsReportTemplate.isSafeTransientReportArg("token"))
    }

    // ────────────────────────────────────────────────────────────────────────
    // SafeUrl, NimsUrlPolicy, HelperSettings, ReportResponseClassifier
    // (unchanged from before — kept here for continuity)
    // ────────────────────────────────────────────────────────────────────────

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

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
