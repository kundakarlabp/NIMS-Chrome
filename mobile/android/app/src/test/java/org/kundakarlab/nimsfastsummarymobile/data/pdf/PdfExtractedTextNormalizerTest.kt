package org.kundakarlab.nimsfastsummarymobile.data.pdf

import org.junit.Assert.*
import org.junit.Test

class PdfExtractedTextNormalizerTest {
    @Test fun preservesClinicalOperatorsUnitsAndSirRows() {
        val text = "CRP\r\n< 0.5   mg/L\nMeropenem     R\nAmikacin\u00A0S\nPlatelet Count   150,000 /cumm"
        val normalized = PdfExtractedTextNormalizer.normalize(text)
        assertTrue(normalized.contains("< 0.5 mg/L"))
        assertTrue(normalized.contains("Meropenem R"))
        assertTrue(normalized.contains("Amikacin S"))
        assertTrue(normalized.contains("150,000 /cumm"))
    }
}
