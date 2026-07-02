package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandNimsExtractorTest {
    @Test
    fun decodesEvaluateJavascriptStringResult() {
        val raw = "\"{\\\"ok\\\":true,\\\"pageKind\\\":\\\"cr_results\\\"}\""
        val result = OnDemandNimsExtractor.decodeResult(raw).getOrThrow()
        assertTrue(result.getBoolean("ok"))
        assertEquals("cr_results", result.getString("pageKind"))
    }

    @Test
    fun rejectsNullJavascriptResult() {
        assertTrue(OnDemandNimsExtractor.decodeResult("null").isFailure)
    }

    @Test
    fun desktopUserAgentUsesInstalledChromiumVersion() {
        val defaultUa = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/149.0.7777.12 Mobile Safari/537.36"
        val desktopUa = ChromeModeActivity.desktopChromeUserAgent(defaultUa)
        assertTrue(desktopUa.contains("Chrome/149.0.7777.12"))
        assertTrue(desktopUa.contains("Windows NT 10.0"))
        assertFalse(desktopUa.contains("Mobile"))
        assertFalse(desktopUa.contains("Android"))
    }
}
