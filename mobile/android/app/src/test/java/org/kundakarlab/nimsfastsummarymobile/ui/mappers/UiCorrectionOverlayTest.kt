package org.kundakarlab.nimsfastsummarymobile.ui.mappers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.recovery.ClinicianCorrection
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary

class UiCorrectionOverlayTest {
    @Test
    fun addsClearlyLabelledLaboratoryCorrectionWithoutDeletingSourceRows() {
        val corrected = UiCorrectionOverlay.apply(
            UiSummary(),
            listOf(ClinicianCorrection("report-1", "Total bilirubin", "2.4", "mg/dL"))
        )

        assertEquals(1, corrected.labTrends.size)
        assertTrue(corrected.labTrends.single().parameter.contains("Clinician entered"))
        assertEquals("2.4 mg/dL", corrected.labTrends.single().latestValue)
    }

    @Test
    fun organismCorrectionCreatesClinicianLabelledCultureObservation() {
        val corrected = UiCorrectionOverlay.apply(
            UiSummary(),
            listOf(ClinicianCorrection("culture-1", "Organism", "Enterococcus faecium"))
        )

        assertEquals("Enterococcus faecium", corrected.cultures.single().organism)
        assertEquals("growth_detected", corrected.cultures.single().status)
        assertTrue(corrected.cultures.single().comment.contains("Clinician entered"))
    }
}
