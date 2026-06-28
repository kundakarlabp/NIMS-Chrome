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
    fun observerSourceIsEmbeddedInRuntimeScript() {
        val marker = "passive_observer_marker"
        assertTrue(NimsWebViewRuntime.buildPayload(marker).contains(marker))
    }
}
