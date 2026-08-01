package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.ui.components.PatientSnapshotPresentation
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiPatientSnapshot

class PatientSnapshotPresentationTest {
    @Test
    fun completeSnapshotProducesCompactClinicalLabels() {
        val presentation = PatientSnapshotPresentation.from(
            UiPatientSnapshot(
                name = "Test Patient",
                crNumber = "CR-123",
                age = "54 years",
                sex = "Male",
                location = "CT ICU"
            )
        )

        assertEquals("Test Patient", presentation.title)
        assertEquals("CR / UHID: CR-123", presentation.identifier)
        assertEquals("54 years · Male", presentation.demographics)
        assertEquals("Location: CT ICU", presentation.location)
        assertTrue(presentation.hasSecondaryDetails)
    }

    @Test
    fun partialSnapshotOmitsUnavailableLabels() {
        val presentation = PatientSnapshotPresentation.from(
            UiPatientSnapshot(name = "Known Patient", sex = "Female")
        )

        assertEquals("Known Patient", presentation.title)
        assertEquals("", presentation.identifier)
        assertEquals("Female", presentation.demographics)
        assertEquals("", presentation.location)
        assertTrue(presentation.hasSecondaryDetails)
    }

    @Test
    fun emptySnapshotUsesExplicitUnavailableState() {
        val presentation = PatientSnapshotPresentation.from(UiPatientSnapshot())

        assertEquals("Patient details unavailable", presentation.title)
        assertFalse(presentation.hasSecondaryDetails)
    }
}
