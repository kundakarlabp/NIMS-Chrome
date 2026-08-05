package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NimsSusceptibilityTableParserTest {
    @Test
    fun parsesExplicitEnterococcusRows() {
        val text = """
            SUSCEPTIBILITY REPORT
            Ampicillin Resistant
            Vancomycin R MIC >= 32 ug/ml
            Linezolid Susceptible
            High level Gentamicin Resistant
        """.trimIndent()

        val results = NimsSusceptibilityTableParser.parse(text)

        assertTrue(results.any { it.antibiotic == "Ampicillin" && it.interpretation == "Resistant" })
        assertTrue(results.any { it.antibiotic == "Vancomycin" && it.interpretation == "Resistant" && it.micValue == 32.0 })
        assertTrue(results.any { it.antibiotic == "Linezolid" && it.interpretation == "Susceptible" })
        assertTrue(results.any { it.antibiotic == "Gentamicin" && it.interpretation == "Resistant" })
    }

    @Test
    fun parsesLabelledKlebsiellaSections() {
        val text = """
            SENSITIVITY REPORT:
            Trimethoprim/Sulfamethoxazole
            RESISTANCE REPORT:
            Amikacin Gentamicin Ciprofloxacin Ceftriaxone Cefepime Meropenem Colistin
        """.trimIndent()

        val results = NimsSusceptibilityTableParser.parse(text)

        assertTrue(results.any { it.antibiotic == "Trimethoprim/Sulfamethoxazole" && it.interpretation == "Susceptible" })
        assertTrue(results.any { it.antibiotic == "Meropenem" && it.interpretation == "Resistant" })
        assertTrue(results.any { it.antibiotic == "Colistin" && it.interpretation == "Resistant" })
    }

    @Test
    fun treatmentRecommendationsAreNotSusceptibility() {
        val text = """
            CULTURE SHOWS GROWTH OF BURKHOLDERIA PSEUDOMALLEI
            TREATMENT:
            Ceftazidime or Meropenem or Imipenem may be used.
        """.trimIndent()

        assertEquals(emptyList<Any>(), NimsSusceptibilityTableParser.parse(text))
    }

    @Test
    fun suspiciousAllIntermediatePanelIsRejected() {
        val text = """
            SUSCEPTIBILITY REPORT
            Amikacin Intermediate
            Gentamicin Intermediate
            Ciprofloxacin Intermediate
            Ceftriaxone Intermediate
            Cefepime Intermediate
            Meropenem Intermediate
        """.trimIndent()

        assertTrue(NimsSusceptibilityTableParser.parse(text).isEmpty())
    }
}
