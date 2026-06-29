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

    // REGRESSION: live NIMS markup wraps the call (e.g. "return printReport('x.pdf');"),
    // which the old fully-anchored matchEntire(^...$) pattern rejected outright,
    // matching the exact defect found and fixed in nimsAndroidFrameBridge.js.
    @Test
    fun extractsArgumentWhenOnclickIsWrappedInAReturnStatement() {
        assertEquals(
            "260611R1114_E9736.pdf",
            FrameReportNormalizer.extractSinglePrintReportArg("return printReport('260611R1114_E9736.pdf');")
        )
    }

    @Test
    fun extractsArgumentWhenOnclickHasATrailingStatement() {
        assertEquals(
            "260611R1114_E9736.pdf",
            FrameReportNormalizer.extractSinglePrintReportArg("printReport('260611R1114_E9736.pdf'); return false;")
        )
    }

    @Test
    fun stillExtractsThePlainUnwrappedFormAfterTheFix() {
        assertEquals(
            "260611R1114_E9736.pdf",
            FrameReportNormalizer.extractSinglePrintReportArg("printReport('260611R1114_E9736.pdf')")
        )
    }

    @Test
    fun returnsEmptyForAnUnrelatedFunctionCall() {
        assertEquals("", FrameReportNormalizer.extractSinglePrintReportArg("submitForm('NEW');"))
    }

    @Test
    fun wrappedFormFlowsThroughFullNormalizeEndToEnd() {
        val row = JSONObject()
            .put("report_name", "Cultures")
            .put("onclick", "return printReport('TOKEN_42.pdf');")
        val input = JSONObject()
            .put("type", "nims_report_frame")
            .put("href", "www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt")
            .put("rows", JSONArray().put(row))

        val result = FrameReportNormalizer.normalize(input, "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action")

        assertNotNull(result)
        assertEquals(
            "TOKEN_42.pdf",
            result!!.getJSONArray("rows").getJSONObject(0).getString("transientPrintReportArg")
        )
    }
}
