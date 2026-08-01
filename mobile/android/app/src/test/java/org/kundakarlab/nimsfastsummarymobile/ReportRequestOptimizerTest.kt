package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRequestOptimizerTest {
    @Test fun prioritizesCultureThenKeyLabsAndDeduplicatesFinalStage() {
        val preliminary = request("a", "Blood Culture preliminary", "01-Aug-2026", "LAB-1", "preliminary", culture = true)
        val final = request("b", "Blood Culture final", "01-Aug-2026", "LAB-1", "final", culture = true)
        val renal = request("c", "Renal function test", "01-Aug-2026", "LAB-2", "final")
        val cbc = request("d", "Complete hemogram", "01-Aug-2026", "LAB-3", "final")

        val optimized = ReportRequestOptimizer.optimize(listOf(renal, preliminary, cbc, final))

        assertEquals(3, optimized.size)
        assertEquals("b", optimized.first().reportId)
        assertEquals("d", optimized[1].reportId)
        assertTrue(ReportRequestOptimizer.isCulture(optimized.first()))
    }

    private fun request(
        id: String,
        name: String,
        date: String,
        labNo: String,
        stage: String,
        culture: Boolean = false
    ): PreparedReportRequest {
        val row = JSONObject()
            .put("report_name", name)
            .put("date_sent", date)
            .put("lab_study_number", labNo)
            .put("report_stage", stage)
            .put("report_tags", JSONArray().put(if (culture) "culture" else "lab"))
        return PreparedReportRequest(row, "$id.pdf", "https://www.nimsts.edu.in/$id.pdf", id)
    }
}
