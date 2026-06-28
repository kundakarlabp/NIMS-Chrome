package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NimsWebViewRuntimeTest {
    @Test
    fun approvedOriginsAreExact() {
        assertEquals(
            setOf("https://www.nimsts.edu.in", "https://nimsts.edu.in"),
            NimsWebViewRuntime.allowedOrigins
        )
    }

    @Test
    fun payloadContainsOnlyCoreUtilitiesAndPassiveObserver() {
        val payload = NimsWebViewRuntime.buildPayload(
            core = "CORE_SENTINEL",
            utils = "UTILS_SENTINEL",
            observer = "OBSERVER_SENTINEL"
        )

        assertTrue(payload.contains("CORE_SENTINEL"))
        assertTrue(payload.contains("UTILS_SENTINEL"))
        assertTrue(payload.contains("OBSERVER_SENTINEL"))
        assertTrue(payload.contains("nimsts\\.edu\\.in"))
        assertFalse(payload.contains("JQUERY_SENTINEL"))
        assertFalse(payload.contains("SHIM_SENTINEL"))
        assertFalse(payload.contains("__nimsBundledJqueryVersion"))
        assertFalse(payload.contains("typeof w.jQuery==='undefined'"))
    }
}
