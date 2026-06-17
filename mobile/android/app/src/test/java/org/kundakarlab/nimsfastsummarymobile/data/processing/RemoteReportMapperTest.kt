package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.model.ReportInput

class RemoteReportMapperTest {
    @Test fun mapsValidRowsAndWarnings() {
        val json = JSONObject().put("report_id", "r").put("parameters", JSONArray().put(JSONObject().put("name", "Creatinine").put("value", 1.2).put("unit", "mg/dL")).put("bad"))
            .put("culture_results", JSONArray().put(JSONObject().put("specimen", "Blood").put("organism", "E coli")).put("bad"))
            .put("errors", JSONArray().put("helper warning"))
        val (report, warnings) = RemoteReportMapper.toParsedReport(json, input(), "Railway")
        assertEquals(1, report.labs.size); assertEquals(1, report.cultures.size); assertTrue(warnings.any { it.contains("helper warning") }); assertTrue(warnings.any { it.contains("Skipped malformed") })
    }
    @Test fun summaryMapperKeepsHelperJson() {
        val json = JSONObject().put("interpretation", JSONArray().put("A"))
        val summary = RemoteSummaryMapper.toProcessingSummary(json, 2)
        assertEquals(json, summary.helperJson); assertEquals(2, summary.reportsProcessed)
    }
    private fun input() = ReportInput("r", "Report", "2026-01-01", "lab", "text/plain", ByteArray(0))
}
