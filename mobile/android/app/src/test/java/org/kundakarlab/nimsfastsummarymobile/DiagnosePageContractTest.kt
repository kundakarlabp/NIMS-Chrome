package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosePageContractTest {
    @Test fun usesSharedJavascriptViewReportRowsKey() {
        assertEquals(7, DiagnosePageContract.viewReportRows(JSONObject().put("viewReportRows", 7)))
    }
}
