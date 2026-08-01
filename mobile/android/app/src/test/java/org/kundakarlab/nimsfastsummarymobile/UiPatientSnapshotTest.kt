package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary

class UiPatientSnapshotTest {
    @Test
    fun mapsCanonicalPatientSnapshotFields() {
        val summary = UiSummary(
            rawJson = JSONObject().put(
                "patient",
                JSONObject()
                    .put("name", "Test Patient")
                    .put("cr_number", "CR-123")
                    .put("age", "54 years")
                    .put("sex", "Male")
                    .put("ward", "CT ICU")
            )
        )

        val snapshot = summary.patientSnapshot
        assertTrue(snapshot.isAvailable)
        assertEquals("Test Patient", snapshot.name)
        assertEquals("CR-123", snapshot.crNumber)
        assertEquals("54 years · Male", snapshot.demographicLine)
        assertEquals("CT ICU", snapshot.location)
    }

    @Test
    fun acceptsLegacyPatientDetailAliases() {
        val summary = UiSummary(
            rawJson = JSONObject().put(
                "patient_details",
                JSONObject()
                    .put("patient_name", "Alias Patient")
                    .put("uhid", "UHID-9")
                    .put("patient_age", "31")
                    .put("gender", "Female")
                    .put("unit", "Medicine")
            )
        )

        val snapshot = summary.patientSnapshot
        assertEquals("Alias Patient", snapshot.name)
        assertEquals("UHID-9", snapshot.crNumber)
        assertEquals("31 · Female", snapshot.demographicLine)
        assertEquals("Medicine", snapshot.location)
    }

    @Test
    fun absentPatientMetadataProducesSafeEmptySnapshot() {
        val snapshot = UiSummary(rawJson = JSONObject()).patientSnapshot
        assertFalse(snapshot.isAvailable)
        assertEquals("", snapshot.demographicLine)
    }
}
