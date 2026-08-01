package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.ui.formatters.ClinicalSummaryFormatter
import org.kundakarlab.nimsfastsummarymobile.ui.models.Abnormality
import org.kundakarlab.nimsfastsummarymobile.ui.models.ClinicalPanel
import org.kundakarlab.nimsfastsummarymobile.ui.models.ClinicalReviewFilter
import org.kundakarlab.nimsfastsummarymobile.ui.models.ClinicalReviewProjector
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiCultureRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiLabTrendRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSourceReport
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary

class ClinicalReviewProjectorTest {
    @Test
    fun abnormalAndPanelFiltersKeepOnlyClinicallyRelevantRows() {
        val summary = sampleSummary()
        val view = ClinicalReviewProjector.project(
            summary,
            ClinicalReviewFilter(panel = ClinicalPanel.HEMOGRAM, abnormalOnly = true)
        )

        assertEquals(listOf("Hemoglobin"), view.labs.map { it.parameter })
        assertFalse(view.labs.any { it.parameter == "MCV" })
    }

    @Test
    fun dateWindowTrimsOldHistoryAndEvents() {
        val view = ClinicalReviewProjector.project(sampleSummary(), ClinicalReviewFilter(days = 14))
        val hemoglobin = view.labs.first { it.parameter == "Hemoglobin" }

        assertEquals(listOf("01-08-2026"), hemoglobin.history.map { it.first })
        assertFalse(view.timeline.any { it.date == "01-06-2026" })
        assertTrue(view.timeline.any { it.date == "01-08-2026" })
    }

    @Test
    fun numericPairsProduceReadableChangeStatements() {
        val statement = ClinicalReviewProjector.changeStatement(
            lab("CRP", "60", "01-08-2026", "30", "25-07-2026", Abnormality.HIGH)
        )

        assertEquals("CRP increased from 30 to 60 (100%)", statement)
    }

    @Test
    fun clinicalHeaderSurfacesPatientWithoutChangingRawPersistence() {
        val summary = sampleSummary()

        assertTrue(summary.dateRange.contains("Test Patient"))
        assertTrue(summary.dateRange.contains("CR/UHID CR-123"))
        assertEquals("01-06-2026 to 01-08-2026", summary.reportDateRange)
    }

    @Test
    fun clinicianExportIncludesIdentityActionableFindingsAndReviewFailures() {
        val text = ClinicalSummaryFormatter.cleanText(sampleSummary())

        assertTrue(text.contains("Patient: Test Patient · CR/UHID CR-123"))
        assertTrue(text.contains("Immediate review:"))
        assertTrue(text.contains("Blood | Escherichia coli"))
        assertTrue(text.contains("Longitudinal changes:"))
        assertTrue(text.contains("Reports needing source review: 1"))
        assertTrue(text.contains("Auto-parsed summary"))
    }

    private fun sampleSummary(): UiSummary = UiSummary(
        sourceReports = listOf(
            UiSourceReport("r1", "01-08-2026", "CBC", "lab", "fully_parsed", "", false),
            UiSourceReport("r2", "01-06-2026", "Old CBC", "lab", "fully_parsed", "", false),
            UiSourceReport("r3", "30-07-2026", "Unsupported scan", "other", "unsupported", "No text layer", true)
        ),
        labTrends = listOf(
            UiLabTrendRow(
                parameter = "Hemoglobin",
                latestValue = "8.0",
                latestDate = "01-08-2026",
                previousValue = "9.0",
                previousDate = "01-06-2026",
                trendText = "decreasing",
                abnormality = Abnormality.LOW,
                history = listOf("01-08-2026" to "8.0", "01-06-2026" to "9.0")
            ),
            lab("Platelet", "180", "01-08-2026", "170", "25-07-2026", Abnormality.NORMAL),
            lab("MCV", "82", "01-08-2026", "81", "25-07-2026", Abnormality.NORMAL),
            lab("CRP", "60", "01-08-2026", "30", "25-07-2026", Abnormality.HIGH)
        ),
        cultures = listOf(
            UiCultureRow(
                sourceKey = "r4",
                collectionDate = "31-07-2026",
                cultureNo = "C1",
                specimen = "Blood",
                site = "Blood",
                organism = "Escherichia coli",
                growth = "growth detected",
                status = "growth_detected",
                sensitivitySummary = "S: meropenem; R: ceftriaxone",
                comment = "",
                reportStage = "final"
            )
        ),
        rawJson = JSONObject().put(
            "patient",
            JSONObject()
                .put("name", "Test Patient")
                .put("cr_number", "CR-123")
                .put("age", "52 years")
                .put("sex", "Male")
                .put("ward", "CT ICU")
        )
    )

    private fun lab(
        parameter: String,
        latest: String,
        latestDate: String,
        previous: String,
        previousDate: String,
        abnormality: Abnormality
    ) = UiLabTrendRow(
        parameter = parameter,
        latestValue = latest,
        latestDate = latestDate,
        previousValue = previous,
        previousDate = previousDate,
        trendText = "trend",
        abnormality = abnormality,
        history = listOf(latestDate to latest, previousDate to previous)
    )
}
