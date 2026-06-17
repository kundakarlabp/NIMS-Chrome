package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.model.*

class RemoteReportMapperTest {
    @Test fun mapsLabSchemaAndCanonicalCodes() {
        val json = JSONObject().put("report_id", "r").put("parameters", JSONArray()
            .put(JSONObject().put("name", "Hemoglobin").put("canonical_name", "Hemoglobin").put("value", "<0.5").put("unit", "g/dL").put("reference_range", "12-16").put("abnormal_flag", "low").put("date_sent", "01-06-2026"))
            .put(JSONObject().put("name", "Creatinine").put("value", 1.2).put("unit", "mg/dL"))
            .put("bad")
            .put(JSONObject().put("value", "9")))
        val (report, warnings) = RemoteReportMapper.toParsedReport(json, input(), "Railway")
        val hb = report.labs.first { it.canonicalCode == "HB" }
        assertEquals(NumericComparator.LESS_THAN, hb.comparator)
        assertEquals(0.5, hb.numericValue!!, 0.001)
        assertEquals("12-16", hb.referenceRangeText)
        assertEquals(Abnormality.LOW, hb.abnormality)
        assertEquals("01-06-2026", hb.resultDate)
        assertTrue(report.labs.any { it.canonicalCode == "CREAT" && it.numericValue == 1.2 })
        assertTrue(warnings.any { it.contains("Skipped malformed remote parameter") })
        assertTrue(warnings.any { it.contains("without a name") })
    }

    @Test fun mapsCultureResultsAndSingularCultureWithDedupe() {
        val culture = JSONObject().put("specimen", "Blood").put("organism", "E coli").put("result_status", "positive").put("collection_date", "01-06-2026")
        val json = JSONObject()
            .put("culture_results", JSONArray().put(culture).put("bad"))
            .put("culture", JSONObject(culture.toString()))
        val (report, warnings) = RemoteReportMapper.toParsedReport(json, input(), "Railway")
        assertEquals(1, report.cultures.size)
        assertEquals(GrowthStatus.GROWTH_DETECTED, report.cultures.first().growthStatus)
        assertTrue(warnings.any { it.contains("Skipped malformed remote culture") })
    }

    @Test fun mapsAntibiogramAndSensitivitySummary() {
        val culture = JSONObject()
            .put("specimen", "Blood")
            .put("organisms", JSONArray().put("Klebsiella pneumoniae").put("E coli"))
            .put("result", "positive CRE isolated")
            .put("susceptible_antibiotics", JSONArray().put("Amikacin"))
            .put("resistant_antibiotics", JSONArray().put("Meropenem"))
            .put("intermediate_antibiotics", JSONArray().put("Ciprofloxacin"))
            .put("sensitivity_summary", JSONObject().put("sensitive", JSONArray().put("Amikacin")).put("resistant", JSONArray().put("Piperacillin")))
        val (report, warnings) = RemoteReportMapper.toParsedReport(JSONObject().put("culture", culture), input(), "Railway")
        val parsed = report.cultures.single()
        assertTrue(parsed.organism!!.contains(";"))
        assertTrue(warnings.any { it.contains("Multiple organisms") })
        assertTrue(parsed.susceptibility.any { it.antibiotic == "Amikacin" && it.interpretation == "Susceptible" })
        assertEquals(1, parsed.susceptibility.count { it.antibiotic == "Amikacin" })
        assertTrue(parsed.susceptibility.any { it.interpretation == "Resistant" })
        assertTrue(parsed.susceptibility.any { it.interpretation == "Intermediate" })
        assertTrue(parsed.explicitResistanceMarkers.contains("CRE"))
    }

    @Test fun noGrowthPendingAndCreatinineMarkerSafety() {
        val json = JSONObject().put("culture_results", JSONArray()
            .put(JSONObject().put("specimen", "Urine").put("result_status", "no_growth"))
            .put(JSONObject().put("specimen", "Blood").put("result_status", "pending"))
            .put(JSONObject().put("specimen", "Comment").put("comment", "Creatinine high")))
        val (report, _) = RemoteReportMapper.toParsedReport(json, input(), "Railway")
        assertTrue(report.cultures.any { it.growthStatus == GrowthStatus.NO_GROWTH })
        assertTrue(report.cultures.any { it.growthStatus == GrowthStatus.PENDING })
        assertFalse(report.cultures.flatMap { it.explicitResistanceMarkers }.contains("CRE"))
    }

    @Test fun missingCultureFieldsReturnsEmpty() {
        val (report, _) = RemoteReportMapper.toParsedReport(JSONObject(), input(), "Railway")
        assertTrue(report.cultures.isEmpty())
    }

    @Test fun summaryMapperValidatesUsefulContent() {
        val json = JSONObject().put("interpretation", JSONArray().put("A")).put("warnings", JSONArray().put("W"))
        val summary = RemoteSummaryMapper.toProcessingSummary(json, 2)
        assertEquals(json, summary.helperJson)
        assertEquals(2, summary.reportsProcessed)
        assertTrue(summary.warnings.contains("W"))
        assertThrows(IllegalArgumentException::class.java) { RemoteSummaryMapper.toProcessingSummary(JSONObject(), 1) }
    }

    private fun input() = ReportInput("r", "Report", "2026-01-01", "lab", "text/plain", ByteArray(0))
}
