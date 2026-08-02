package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.model.GrowthStatus
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParseConfidence
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedCultureValue

class NimsClinicalParsingEnhancerTest {
    @Test
    fun recoversBurkholderiaPseudomalleiFromNarrativeReport() {
        val text = """
            CULTURE REPORT
            CULTURE SHOWS GROWTH OF BURKHOLDERIA PSEUDOMALLEI- ETIOLOGICAL AGENT OF MELIOIDOSIS.
            HIGH PROBABILITY OF TRUE BACTEREMIA.
            TREATMENT:
            Initial intensive phase: Ceftazidime or Meropenem or Imipenem.
            Eradication phase: Sulphamethoxazole - Trimethoprim.
        """.trimIndent()
        val source = culture(organism = null, comments = listOf("Antibiogram text was present but no supported antibiotic rows were recognized; verify the source report."))

        val result = NimsClinicalParsingEnhancer.enrichCultures(listOf(source), text).single()

        assertEquals("Burkholderia pseudomallei", result.organism)
        assertEquals(ParseConfidence.HIGH, result.confidence)
        assertFalse(result.comments.any { it.contains("Antibiogram text was present") })
        assertTrue(result.comments.any { it.contains("Treatment guidance present") })
    }

    @Test
    fun recoversRbsAcrossCommonNimsAliases() {
        val result = NimsClinicalParsingEnhancer.recoverPriorityLabs(
            "Department of Biochemistry\nRandom Blood Sugar     186 mg/dL",
            "02-Aug-2026"
        ).single { it.canonicalCode == "RBS" }

        assertEquals("Random blood glucose", result.displayName)
        assertEquals(186.0, result.numericValue!!, 0.001)
        assertEquals("mg/dL", result.unit)
    }

    @Test
    fun recoversExactSerumBilirubinTotalLayout() {
        val text = """
            LIVER FUNCTION TESTS:
            Serum AST (SGOT) 49 U/L
            Serum Bilirubin (Total) 0.6 mg/dL
            Serum Bilirubin Conjugated 0.2 mg/dL
        """.trimIndent()

        val result = NimsClinicalParsingEnhancer.recoverPriorityLabs(text, "13-Aug-2025")
            .single { it.canonicalCode == "TBIL" }

        assertEquals(0.6, result.numericValue!!, 0.001)
        assertEquals("Total Bilirubin", result.displayName)
    }

    @Test
    fun recoversTotalBilirubinWhenValueIsOnNextLine() {
        val result = NimsClinicalParsingEnhancer.recoverPriorityLabs(
            "Liver Function Test\nBilirubin Total\n2.4 mg/dL\nBilirubin Direct 0.8 mg/dL",
            "02-Aug-2026"
        ).single { it.canonicalCode == "TBIL" }

        assertEquals(2.4, result.numericValue!!, 0.001)
    }

    @Test
    fun betaDGlucanWithoutCultureEvidenceCannotCreateCulture() {
        val text = """
            Beta D glucan STAT Test
            Sample: Serum
            Result 112 pg/mL
            Interpretation: Positive
            Cryptococcus neoformans may not reliably elevate this assay.
        """.trimIndent()

        assertTrue(NimsClinicalParsingEnhancer.isBiomarkerOnlyReport(text))
        assertTrue(NimsClinicalParsingEnhancer.enrichCultures(listOf(culture("Cryptococcus neoformans")), text).isEmpty())
        val lab = NimsClinicalParsingEnhancer.recoverPriorityLabs(text, "22-Jul-2025").single { it.canonicalCode == "BDG" }
        assertEquals(112.0, lab.numericValue!!, 0.001)
    }

    @Test
    fun galactomannanCannotBecomeAspergillusCulture() {
        val text = """
            ASPERGILLUS GALACTOMANNAN AG ELISA
            Specimen Serum
            Galactomannan Index 0.4 Comment Negative
        """.trimIndent()

        assertTrue(NimsClinicalParsingEnhancer.enrichCultures(listOf(culture("Aspergillus species")), text).isEmpty())
        val lab = NimsClinicalParsingEnhancer.recoverPriorityLabs(text, "22-Jul-2025").single { it.canonicalCode == "GM_INDEX" }
        assertEquals(0.4, lab.numericValue!!, 0.001)
        assertEquals("", lab.unit)
    }

    @Test
    fun drugNamesInTreatmentTextAreNotCalledSusceptibility() {
        val text = "CULTURE SHOWS GROWTH OF BURKHOLDERIA PSEUDOMALLEI\nTREATMENT:\nCeftazidime or Meropenem or Imipenem may be used."
        val result = NimsClinicalParsingEnhancer.enrichCultures(listOf(culture("Burkholderia pseudomallei")), text).single()

        assertTrue(result.susceptibility.isEmpty())
        assertTrue(result.comments.any { it.contains("no structured susceptibility table") })
    }

    private fun culture(
        organism: String?,
        comments: List<String> = emptyList()
    ) = ParsedCultureValue(
        specimen = "Blood",
        site = null,
        collectionDate = "22-Apr-2026 12:13",
        organism = organism,
        growthStatus = GrowthStatus.GROWTH_DETECTED,
        susceptibility = emptyList(),
        explicitResistanceMarkers = emptySet(),
        comments = comments,
        confidence = ParseConfidence.MEDIUM
    )
}
