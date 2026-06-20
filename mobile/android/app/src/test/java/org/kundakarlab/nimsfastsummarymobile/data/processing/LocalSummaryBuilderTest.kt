package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.junit.Assert.*
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.model.*

class LocalSummaryBuilderTest {
    @Test fun modesAreDifferentAndIncludeDisclaimer() {
        val reports = listOf(report("31-05-2026", 1.0, GrowthStatus.NO_GROWTH), report("02-06-2026", 1.4, GrowthStatus.GROWTH_DETECTED))
        val fast = LocalSummaryBuilder().build(reports, SummaryMode.FAST).text
        val cultures = LocalSummaryBuilder().build(reports, SummaryMode.CULTURES_ONLY).text
        val full = LocalSummaryBuilder().build(reports, SummaryMode.FULL).text
        assertTrue(fast.contains("Verify with source NIMS reports")); assertTrue(cultures.contains("Verify with source NIMS reports")); assertTrue(full.contains("Verify with source NIMS reports"))
        assertTrue(fast.contains("Creatinine increased from 1.0 mg/dL to 1.4 mg/dL"))
        assertFalse(cultures.contains("Creatinine increased"))
        assertTrue(full.contains("Reports processed"))
    }

    @Test fun lowConfidenceLabsExcluded() {
        val low = ParsedLabValue("CREAT", "Creatinine", "Creatinine", 9.9, null, "mg/dL", null, null, null, Abnormality.UNKNOWN, "2026-01-01", ParseConfidence.LOW)
        val text = LocalSummaryBuilder().build(listOf(ParsedReport("r", "r", "2026-01-01", "lab", labs = listOf(low), processorName = "local")), SummaryMode.FULL).text
        assertFalse(text.contains("9.9"))
    }

    @Test fun unknownDateDoesNotBecomeLatestOrTrend() {
        val dated = report("02-06-2026", 1.4, GrowthStatus.NO_GROWTH)
        val undated = report("not a date", 9.9, GrowthStatus.NO_GROWTH)
        val text = LocalSummaryBuilder().build(listOf(dated, undated), SummaryMode.FULL).text
        assertTrue(text.contains("Latest Creatinine: 1.4 mg/dL"))
        assertFalse(text.contains("9.9 mg/dL on not a date"))
        assertTrue(text.contains("undated Creatinine"))
    }

    @Test fun allUnknownDatesUseNeutralRecordedWording() {
        val text = LocalSummaryBuilder().build(listOf(report("unknown", 10.2, GrowthStatus.NO_GROWTH)), SummaryMode.FULL).text
        assertTrue(text.contains("Recorded Creatinine value: 10.2 mg/dL; report date unavailable."))
        assertFalse(text.contains("Latest Creatinine"))
    }

    @Test fun dateRangeUsesValidDatesOnly() {
        val text = LocalSummaryBuilder().build(listOf(report("bad", 1.1, GrowthStatus.NO_GROWTH), report("31-05-2026", 1.0, GrowthStatus.NO_GROWTH), report("02-06-2026", 1.4, GrowthStatus.NO_GROWTH)), SummaryMode.FULL).text
        assertTrue(text.contains("Date range: 31-05-2026 to 02-06-2026"))
        assertFalse(text.contains("bad to"))
    }

    @Test fun localAndRemoteHemoglobinCodesGroupTogether() {
        val local = ParsedReport("l", "Local", "31-05-2026", "lab", labs = listOf(ParsedLabValue("HB", "Hemoglobin", "Hb", 10.0, null, "g/dL", null, null, null, Abnormality.UNKNOWN, "31-05-2026", ParseConfidence.HIGH)), processorName = "local")
        val remote = ParsedReport("r", "Remote", "02-06-2026", "lab", labs = listOf(ParsedLabValue("Hemoglobin", "Hemoglobin", "Hemoglobin", 11.0, null, "g/dL", null, null, null, Abnormality.UNKNOWN, "02-06-2026", ParseConfidence.HIGH)), processorName = "Railway")
        val text = LocalSummaryBuilder().build(listOf(local, remote), SummaryMode.FULL).text
        assertTrue(text.contains("Hemoglobin increased from 10.0 g/dL to 11.0 g/dL"))
    }

    @Test fun unsupportedPdfAppearsAsSourceReportWithAction() {
        val reason = "PDF local parsing is not yet supported. Open the source report manually."
        val unsupported = ParsedReport("pdf", "PDF Report", "02-06-2026", "pdf", warnings = listOf(reason), processorName = "none")
        val json = LocalSummaryBuilder().build(listOf(unsupported), SummaryMode.FULL).helperJson!!
        val sourceReport = json.getJSONArray("source_reports").getJSONObject(0)
        assertEquals("PDF Report", sourceReport.getString("report_name"))
        assertEquals("02-06-2026", sourceReport.getString("date_sent"))
        assertEquals("unsupported", sourceReport.getString("status"))
        assertEquals(reason, sourceReport.getString("notes"))
        assertEquals("Open source report in NIMS", sourceReport.getString("action"))
    }

    @Test fun trendJsonUsesLatestToOldestAlignedColumnsAndBlanks() {
        val reports = listOf(report("31-05-2026", 1.0, GrowthStatus.NO_GROWTH), ParsedReport("mid", "Report", "01-06-2026", "lab", labs = emptyList(), processorName = "local"), report("02-06-2026", 1.4, GrowthStatus.NO_GROWTH))
        val table = LocalSummaryBuilder().build(reports.shuffled(), SummaryMode.FULL).helperJson!!.getJSONObject("lab_trend_table")
        assertEquals(listOf("02-06-2026", "01-06-2026", "31-05-2026"), strings(table.getJSONArray("columns")))
        val row = table.getJSONArray("rows").getJSONObject(0)
        assertEquals(listOf("1.4 mg/dL", "", "1.0 mg/dL"), strings(row.getJSONArray("values")))
        assertEquals(table.getJSONArray("columns").length(), row.getJSONArray("values").length())
    }

    @Test fun trendJsonExcludesLowConfidenceValuesButKeepsGenericWarningOnly() {
        val high = report("02-06-2026", 1.4, GrowthStatus.NO_GROWTH)
        val low = ParsedReport("low", "Low", "01-06-2026", "lab", labs = listOf(ParsedLabValue("CREAT", "Creatinine", "Creatinine", 9.9, null, "mg/dL", null, null, null, Abnormality.UNKNOWN, "01-06-2026", ParseConfidence.LOW)), processorName = "local")
        val json = LocalSummaryBuilder().build(listOf(high, low), SummaryMode.FULL).helperJson!!
        assertFalse(json.toString().contains("9.9"))
        assertTrue(json.getJSONArray("warnings").getString(0).contains("low-confidence", true))
        val sourceReports = json.getJSONArray("source_reports")
        val lowSourceReport = (0 until sourceReports.length())
            .map { sourceReports.getJSONObject(it) }
            .first { it.getString("report_name") == "Low" }
        assertTrue(lowSourceReport.getString("notes").contains("Low-confidence"))
    }

    @Test fun localAndRemoteAliasesProduceOneCanonicalTrendRow() {
        val local = ParsedReport("l", "Local", "31-05-2026", "lab", labs = listOf(ParsedLabValue("HB", "Hemoglobin", "Hb", 10.0, null, "g/dL", null, null, null, Abnormality.UNKNOWN, "31-05-2026", ParseConfidence.HIGH)), processorName = "local")
        val remote = ParsedReport("r", "Remote", "02-06-2026", "lab", labs = listOf(ParsedLabValue("Hemoglobin", "Hemoglobin", "Hemoglobin", 11.0, null, "g/dL", null, null, null, Abnormality.UNKNOWN, "02-06-2026", ParseConfidence.HIGH)), processorName = "Railway")
        val rows = LocalSummaryBuilder().build(listOf(remote, local), SummaryMode.FULL).helperJson!!.getJSONObject("lab_trend_table").getJSONArray("rows")
        assertEquals(1, rows.length())
        assertEquals(listOf("11.0 g/dL", "10.0 g/dL"), strings(rows.getJSONObject(0).getJSONArray("values")))
    }

    @Test fun duplicateSameDateResultsPreferHighestConfidenceValue() {
        val medium = ParsedLabValue("CREAT", "Creatinine", "Creatinine", 1.1, null, null, null, null, null, Abnormality.UNKNOWN, "02-06-2026", ParseConfidence.MEDIUM)
        val high = ParsedLabValue("CREAT", "Creatinine", "Creatinine", 1.2, null, "mg/dL", null, null, null, Abnormality.UNKNOWN, "02-06-2026", ParseConfidence.HIGH)
        val report = ParsedReport("r", "Report", "02-06-2026", "lab", labs = listOf(medium, high), processorName = "local")
        val values = LocalSummaryBuilder().build(listOf(report), SummaryMode.FULL).helperJson!!.getJSONObject("lab_trend_table").getJSONArray("rows").getJSONObject(0).getJSONArray("values")
        assertEquals(listOf("1.2 mg/dL"), strings(values))
    }

    private fun strings(array: org.json.JSONArray): List<String> = (0 until array.length()).map { array.getString(it) }

    private fun report(date: String, creat: Double, growth: GrowthStatus) = ParsedReport("r$date", "Report", date, "lab", labs = listOf(ParsedLabValue("CREAT", "Creatinine", "Creatinine", creat, null, "mg/dL", null, null, null, Abnormality.UNKNOWN, date, ParseConfidence.HIGH)), cultures = listOf(ParsedCultureValue("Blood", null, date, if (growth == GrowthStatus.GROWTH_DETECTED) "Escherichia coli" else null, growth, emptyList(), emptySet(), emptyList(), ParseConfidence.HIGH)), processorName = "local")
}
