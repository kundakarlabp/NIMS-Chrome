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
        val source = ParsedCultureValue(
            specimen = "Blood",
            site = null,
            collectionDate = "22-Apr-2026 12:13",
            organism = null,
            growthStatus = GrowthStatus.GROWTH_DETECTED,
            susceptibility = emptyList(),
            explicitResistanceMarkers = emptySet(),
            comments = listOf("Antibiogram text was present but no supported antibiotic rows were recognized; verify the source report."),
            confidence = ParseConfidence.MEDIUM
        )

        val result = NimsClinicalParsingEnhancer.enrichCultures(listOf(source), text).single()

        assertEquals("Burkholderia pseudomallei", result.organism)
        assertEquals(ParseConfidence.HIGH, result.confidence)
        assertFalse(result.comments.any { it.contains("Antibiogram text was present") })
        assertTrue(result.comments.any { it.contains("Treatment guidance was present") })
    }

    @Test
    fun recoversRbsAcrossCommonNimsAliases() {
        val text = """
            Department of Biochemistry
            Random Blood Sugar     186 mg/dL
        """.trimIndent()

        val result = NimsClinicalParsingEnhancer.recoverPriorityLabs(text, "02-Aug-2026")
            .single { it.canonicalCode == "RBS" }

        assertEquals("Random blood glucose", result.displayName)
        assertEquals(186.0, result.numericValue!!, 0.001)
        assertEquals("mg/dL", result.unit)
    }

    @Test
    fun recoversTotalBilirubinWhenValueIsOnNextLine() {
        val text = """
            Liver Function Test
            Bilirubin Total
            2.4 mg/dL
            Bilirubin Direct 0.8 mg/dL
        """.trimIndent()

        val result = NimsClinicalParsingEnhancer.recoverPriorityLabs(text, "02-Aug-2026")
            .single { it.canonicalCode == "TBIL" }

        assertEquals(2.4, result.numericValue!!, 0.001)
        assertEquals("Total Bilirubin", result.displayName)
    }

    @Test
    fun drugNamesInTreatmentTextAreNotCalledSusceptibility() {
        val text = """
            CULTURE SHOWS GROWTH OF BURKHOLDERIA PSEUDOMALLEI
            TREATMENT:
            Ceftazidime or Meropenem or Imipenem may be used.
        """.trimIndent()
        val source = ParsedCultureValue(
            specimen = "Blood",
            site = null,
            collectionDate = null,
            organism = "Burkholderia pseudomallei",
            growthStatus = GrowthStatus.GROWTH_DETECTED,
            susceptibility = emptyList(),
            explicitResistanceMarkers = emptySet(),
            comments = emptyList(),
            confidence = ParseConfidence.HIGH
        )

        val result = NimsClinicalParsingEnhancer.enrichCultures(listOf(source), text).single()

        assertTrue(result.susceptibility.isEmpty())
        assertTrue(result.comments.any { it.contains("no structured susceptibility table") })
    }
}
