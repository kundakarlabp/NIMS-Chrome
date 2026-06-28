package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FrameReportNormalizerTest {
    @Test
    fun rejectsRowsFromLoggedInHomeShell() {
        val input = JSONObject()
            .put("type", "nims_report_frame")
            .put("href", "www.nimsts.edu.in/AHIMSG5/hislogin/transactions/jsp/st_desk_homeMenuTab_page.jsp")
            .put("rows", JSONArray().put(JSONObject().put("report_name", "Not a report")))

        assertNull(FrameReportNormalizer.normalize(input, "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action"))
    }

    @Test
    fun extractsRuntimeArgumentAddsTemplateAndRemovesRawDomFields() {
        val row = JSONObject()
            .put("report_name", "CBC")
            .put("onclick", "printReport('temporary-file-token')")
            .put("source_url", "https://www.nimsts.edu.in/private?x=1")
            .put("raw_row_text", "synthetic row")
        val input = JSONObject()
            .put("type", "nims_report_frame")
            .put("href", "www.nimsts.edu.in/HISInvestigationG5/new_investigation/invResultReportPrintingCRNoWise.cnt")
            .put("rows", JSONArray().put(row))

        val result = FrameReportNormalizer.normalize(
            input,
            "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action"
        )

        assertNotNull(result)
        val normalizedRow = result!!.getJSONArray("rows").getJSONObject(0)
        assertEquals("temporary-file-token", normalizedRow.getString("transientPrintReportArg"))
        assertFalse(normalizedRow.has("onclick"))
        assertFalse(normalizedRow.has("source_url"))
        assertFalse(normalizedRow.has("raw_row_text"))
        assertEquals(
            "/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt",
            result.getJSONObject("template").getString("pathname")
        )
    }

    @Test
    fun preservesEmptyClearAnnouncement() {
        val input = JSONObject()
            .put("type", "nims_report_frame")
            .put("href", "www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action")
            .put("rows", JSONArray())

        val result = FrameReportNormalizer.normalize(input, "https://www.nimsts.edu.in/")
        assertNotNull(result)
        assertEquals(0, result!!.getJSONArray("rows").length())
    }
}
