package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.model.GrowthStatus

class NimsPdfFallbackParserTest {
    @Test fun recoversFlattenedCbcAndInflammatoryValues() {
        val text = """
            CBC WITH DIFFERENTIAL
            Haemoglobin
            9.8 g/dL
            Total Leucocyte Count
            12,450 /cumm
            Platelet Count
            1.72 lakh/cumm
            C-Reactive Protein Quantitative
            86 mg/L
            ESR Westergren
            74 mm in 1st hr
        """.trimIndent()

        val labs = NimsPdfFallbackParser.parseLabs(text, "18-Mar-2026")
        assertEquals(9.8, labs.first { it.canonicalCode == "HB" }.numericValue!!, 0.001)
        assertEquals(12450.0, labs.first { it.canonicalCode == "WBC" }.numericValue!!, 0.001)
        assertEquals(172000.0, labs.first { it.canonicalCode == "PLT" }.numericValue!!, 0.001)
        assertEquals(86.0, labs.first { it.canonicalCode == "CRP" }.numericValue!!, 0.001)
        assertEquals(74.0, labs.first { it.canonicalCode == "ESR" }.numericValue!!, 0.001)
    }

    @Test fun recoversUrineQualitativeAndMicroscopyRows() {
        val text = """
            COMPLETE URINE EXAMINATION
            Colour Pale Yellow
            Appearance Clear
            Protein Trace
            Nitrite Negative
            Leukocyte Esterase Positive
            Pus Cells 18 /hpf
            RBC 4 /hpf
            Epithelial Cells Few
        """.trimIndent()

        val labs = NimsPdfFallbackParser.parseLabs(text, "17-Mar-2026")
        assertTrue(labs.any { it.canonicalCode == "URINE_PROTEIN" && it.textValue.equals("Trace", true) })
        assertTrue(labs.any { it.canonicalCode == "URINE_NITRITE" && it.textValue.equals("Negative", true) })
        assertEquals(18.0, labs.first { it.canonicalCode == "URINE_WBC" }.numericValue!!, 0.001)
        assertEquals(4.0, labs.first { it.canonicalCode == "URINE_RBC" }.numericValue!!, 0.001)
    }

    @Test fun enrichesGenericPositiveCultureWithOrganismAndAst() {
        val text = """
            FAN BLOOD CULTURE FINAL REPORT
            CULTURE REPORT
            Growth of Klebsiella pneumoniae isolated
            SUSCEPTIBILITY REPORT
            Meropenem R
            Amikacin S
        """.trimIndent()

        val cultures = NimsPdfFallbackParser.enrichCultures(emptyList(), text, "25-Apr-2026")
        assertEquals(1, cultures.size)
        assertEquals(GrowthStatus.GROWTH_DETECTED, cultures.first().growthStatus)
        assertTrue(cultures.first().organism.orEmpty().contains("Klebsiella", true))
        assertTrue(cultures.first().susceptibility.any { it.antibiotic == "Meropenem" && it.interpretation == "Resistant" })
        assertTrue(cultures.first().susceptibility.any { it.antibiotic == "Amikacin" && it.interpretation == "Susceptible" })
    }
}
