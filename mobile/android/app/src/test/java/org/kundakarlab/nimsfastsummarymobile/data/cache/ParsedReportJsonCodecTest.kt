package org.kundakarlab.nimsfastsummarymobile.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.model.*

class ParsedReportJsonCodecTest {
    @Test fun roundTripsLabsAndCultureOrganism() {
        val report = ParsedReport(
            reportId = "r1",
            reportName = "CBC and culture",
            dateSent = "01-Aug-2026",
            reportType = "culture",
            labs = listOf(ParsedLabValue("WBC", "WBC/TLC", "TLC", 12400.0, null, "/cumm", null, null, null, Abnormality.HIGH, "01-Aug-2026", ParseConfidence.HIGH)),
            cultures = listOf(ParsedCultureValue("Blood", null, "01-Aug-2026", "Klebsiella pneumoniae", GrowthStatus.GROWTH_DETECTED, emptyList(), emptySet(), emptyList(), ParseConfidence.HIGH)),
            processorName = "test"
        )
        val decoded = ParsedReportJsonCodec.decode(ParsedReportJsonCodec.encode(report))
        assertEquals(12400.0, decoded.labs.first().numericValue!!, 0.01)
        assertEquals("Klebsiella pneumoniae", decoded.cultures.first().organism)
        assertTrue(decoded.cultures.first().growthStatus == GrowthStatus.GROWTH_DETECTED)
    }
}
