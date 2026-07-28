package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRowSelectorTest {
    private fun row(name: String, vararg tags: String): JSONObject = JSONObject()
        .put("report_name", name)
        .put("report_type", tags.firstOrNull() ?: "other")
        .put("report_tags", JSONArray(tags.toList()))

    @Test
    fun culturesOnlyReturnsOnlyCultureRows() {
        val rows = JSONArray()
            .put(row("CBC", "cbc"))
            .put(row("Blood culture", "culture"))
            .put(row("CRP", "inflammatory"))

        val selected = ReportRowSelector.select(rows, NimsAnalysisMode.CULTURES_ONLY)

        assertEquals(1, selected.size)
        assertEquals("Blood culture", selected.first().getString("report_name"))
    }

    @Test
    fun fastModeCapsRepeatedCbcAndChemistryGroups() {
        val rows = JSONArray()
        repeat(5) { rows.put(row("CBC $it", "cbc")) }
        repeat(5) { rows.put(row("RFT $it", "rft")) }
        rows.put(row("Culture", "culture"))
        rows.put(row("CRP", "inflammatory"))

        val selected = ReportRowSelector.select(rows, NimsAnalysisMode.FAST)

        assertEquals(12, selected.size)
        assertEquals(5, selected.count { it.getJSONArray("report_tags").toString().contains("cbc") })
        assertEquals(5, selected.count { it.getJSONArray("report_tags").toString().contains("rft") })
        assertTrue(selected.any { it.getString("report_name") == "Culture" })
        assertTrue(selected.any { it.getString("report_name") == "CRP" })
    }

    @Test
    fun fastModeNeverDropsCulturesBecauseOfGlobalCap() {
        val rows = JSONArray()
        repeat(30) { rows.put(row("Culture $it", "culture")) }
        repeat(10) { rows.put(row("CBC $it", "cbc")) }

        val selected = ReportRowSelector.select(rows, NimsAnalysisMode.FAST)

        assertEquals(30, selected.count { it.getJSONArray("report_tags").toString().contains("culture") })
        assertEquals(5, selected.count { it.getJSONArray("report_tags").toString().contains("cbc") })
    }

    @Test
    fun fastModeIncludesAllMolecularAndCoagulationReports() {
        val rows = JSONArray()
            .put(row("Gene Xpert", "molecular"))
            .put(row("Activated Partial Thromboplastin Time", "coagulation"))
            .put(row("Histopathology review", "pathology"))
            .put(row("CBC", "cbc"))

        val selected = ReportRowSelector.select(rows, NimsAnalysisMode.FAST)

        assertTrue(selected.any { it.getString("report_name") == "Gene Xpert" })
        assertTrue(selected.any { it.getString("report_name").startsWith("Activated Partial") })
        assertTrue(selected.any { it.getString("report_name").startsWith("Histopathology") })
    }

    @Test
    fun fastModeFallsBackToOneUnknownReport() {
        val rows = JSONArray().put(row("Unknown report", "other"))

        val selected = ReportRowSelector.select(rows, NimsAnalysisMode.FAST)

        assertEquals(1, selected.size)
    }
}
