package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NimsPortalBridgeTest {
    @Test
    fun probesNestedFramesAndAuthenticationSignals() {
        assertTrue(NimsPortalBridge.probeScript.contains("depth>7"))
        assertTrue(NimsPortalBridge.probeScript.contains("loginVisible"))
        assertTrue(NimsPortalBridge.probeScript.contains("crReady"))
        assertTrue(NimsPortalBridge.probeScript.contains("sessionExpired"))
    }

    @Test
    fun crSubmissionUsesInjectedBridgeAndRemovesNonDigits() {
        val script = NimsPortalBridge.submitCrScript("3310-121-00872674")
        assertTrue(script.contains("331012100872674"))
        assertTrue(script.contains("__nimsSubmitCrNumber"))
        assertTrue(script.contains("__nims_cr_proxy_input"))
        assertFalse(script.contains("3310-121"))
    }

    @Test
    fun resultProbeFingerprintsRowsAndChecksExpectedCr() {
        val script = NimsPortalBridge.resultListProbeScript("3310-121-00872674")
        assertTrue(script.contains("selectRowsForModeFromDoc"))
        assertTrue(script.contains("signature"))
        assertTrue(script.contains("crMatch"))
        assertTrue(script.contains("331012100872674"))
        assertFalse(script.contains("3310-121"))
    }

    @Test
    fun mappingScriptsTraverseNestedDocuments() {
        assertTrue(NimsPortalBridge.prepareMappingScript.contains("clickFirstReportForMode"))
        assertTrue(NimsPortalBridge.discoverMappingScript.contains("discoverSetPdfTemplate"))
        assertTrue(NimsPortalBridge.selectRowsScript.contains("JSON.stringify(rows)"))
    }
}
