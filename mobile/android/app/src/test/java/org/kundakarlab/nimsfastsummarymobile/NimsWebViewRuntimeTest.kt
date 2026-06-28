package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.assertEquals
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
    fun payloadUsesConditionalInvestigationJqueryBootstrap() {
        val payload = NimsWebViewRuntime.buildPayload(
            jquery = "JQUERY_SENTINEL",
            shim = "SHIM_SENTINEL",
            core = "CORE_SENTINEL",
            utils = "UTILS_SENTINEL",
            bridge = "BRIDGE_SENTINEL"
        )

        assertTrue(payload.contains("HISInvestigationG5"))
        assertTrue(payload.contains("typeof w.jQuery==='undefined'"))
        assertTrue(payload.contains("JQUERY_SENTINEL"))
        assertTrue(payload.contains("SHIM_SENTINEL"))
        assertTrue(payload.contains("CORE_SENTINEL"))
        assertTrue(payload.contains("UTILS_SENTINEL"))
        assertTrue(payload.contains("BRIDGE_SENTINEL"))
    }
}
