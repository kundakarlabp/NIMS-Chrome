package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.junit.Assert.*
import org.junit.Test

class CanonicalLabCodesTest {
    @Test fun aliasesConverge() {
        assertEquals("HB", CanonicalLabCodes.normalize("Hemoglobin"))
        assertEquals("HB", CanonicalLabCodes.normalize("Hb"))
        assertEquals("WBC", CanonicalLabCodes.normalize("Total Leucocyte Count"))
        assertEquals("PLT", CanonicalLabCodes.normalize("Platelet Count"))
        assertEquals("CREAT", CanonicalLabCodes.normalize("Serum Creatinine"))
    }

    @Test fun unknownNameHasDeterministicFallback() {
        assertEquals("CUSTOM_TEST", CanonicalLabCodes.normalize("Custom-Test"))
    }
}
