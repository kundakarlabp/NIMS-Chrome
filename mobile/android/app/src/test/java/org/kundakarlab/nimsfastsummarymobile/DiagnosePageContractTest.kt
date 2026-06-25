package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosePageContractTest {
    @Test fun usesSharedJavascriptViewReportRowsKey() {
        assertEquals(7, DiagnosePageContract.viewReportRows(JSONObject().put("viewReportRows", 7)))
    }

    @Test fun readsFrameReachKeys() {
        val json = JSONObject().put("blockedFrames", 2).put("reachableDocuments", 3)
        assertEquals(2, DiagnosePageContract.blockedFrames(json))
        assertEquals(3, DiagnosePageContract.reachableDocuments(json))
    }
}
