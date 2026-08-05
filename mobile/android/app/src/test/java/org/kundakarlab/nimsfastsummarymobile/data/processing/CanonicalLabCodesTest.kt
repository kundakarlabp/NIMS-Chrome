package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalLabCodesTest {
    @Test fun aliasesConverge() {
        assertEquals("HB", CanonicalLabCodes.normalize("Hemoglobin"))
        assertEquals("HB", CanonicalLabCodes.normalize("Hb"))
        assertEquals("WBC", CanonicalLabCodes.normalize("Total Leucocyte Count"))
        assertEquals("PLT", CanonicalLabCodes.normalize("Platelet Count"))
        assertEquals("CREAT", CanonicalLabCodes.normalize("Serum Creatinine"))
        assertEquals("GLUCOSE", CanonicalLabCodes.normalize("RBS"))
        assertEquals("GLUCOSE", CanonicalLabCodes.normalize("Random Blood Glucose"))
        assertEquals("GM", CanonicalLabCodes.normalize("GM_INDEX"))
        assertEquals("GM", CanonicalLabCodes.normalize("Galactomannan index"))
    }

    @Test fun unknownNameHasDeterministicFallback() {
        assertEquals("CUSTOM_TEST", CanonicalLabCodes.normalize("Custom-Test"))
    }
}
