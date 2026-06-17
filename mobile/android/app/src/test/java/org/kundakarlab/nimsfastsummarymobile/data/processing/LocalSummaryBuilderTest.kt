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
        val low = ParsedLabValue("CREAT", "Creatinine", "Creatinine", 9.9, null, "mg/dL", null, null, Abnormality.UNKNOWN, "2026-01-01", ParseConfidence.LOW)
        val text = LocalSummaryBuilder().build(listOf(ParsedReport("r", "r", "2026-01-01", "lab", labs = listOf(low), processorName = "local")), SummaryMode.FULL).text
        assertFalse(text.contains("9.9"))
    }
    private fun report(date: String, creat: Double, growth: GrowthStatus) = ParsedReport("r$date", "Report", date, "lab", labs = listOf(ParsedLabValue("CREAT", "Creatinine", "Creatinine", creat, null, "mg/dL", null, null, Abnormality.UNKNOWN, date, ParseConfidence.HIGH)), cultures = listOf(ParsedCultureValue("Blood", null, date, if (growth == GrowthStatus.GROWTH_DETECTED) "Escherichia coli" else null, growth, emptyList(), emptySet(), emptyList(), ParseConfidence.HIGH)), processorName = "local")
}
