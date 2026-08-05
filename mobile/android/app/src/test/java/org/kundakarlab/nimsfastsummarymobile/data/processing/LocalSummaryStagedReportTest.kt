package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.junit.Assert.assertEquals
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedReport
import org.kundakarlab.nimsfastsummarymobile.domain.model.SummaryMode

class LocalSummaryStagedReportTest {
    @Test
    fun fetchedStagedReportWithoutRetainedCultureRowIsStillAvailable() {
        val report = ParsedReport(
            reportId = "stage",
            reportName = "Fan Blood Culture Preliminary",
            dateSent = "20-Jul-2025",
            reportType = "culture",
            cultures = emptyList(),
            processorName = "On-device",
            rawText = "PRELIMINARY CULTURE REPORT"
        )

        val source = LocalSummaryBuilder().build(listOf(report), SummaryMode.FAST).helperJson!!
            .getJSONArray("source_reports").getJSONObject(0)

        assertEquals("parsed", source.getString("status"))
    }
}
