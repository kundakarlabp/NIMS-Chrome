package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.model.AntibioticResult
import org.kundakarlab.nimsfastsummarymobile.domain.model.GrowthStatus
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParseConfidence
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedCultureValue
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedReport

class CultureEpisodeReconcilerTest {
    @Test
    fun joinsPreliminaryIdentificationAndFinalSusceptibilityByLabNumber() {
        val preliminary = report(
            id = "prelim",
            stage = "preliminary",
            organism = null,
            susceptibility = emptyList()
        )
        val identification = report(
            id = "id",
            stage = "48-hour preliminary",
            organism = "Enterococcus faecium",
            susceptibility = emptyList()
        )
        val final = report(
            id = "final",
            stage = "final",
            organism = "Enterococcus faecium",
            susceptibility = listOf(
                AntibioticResult("Vancomycin", "R", ParseConfidence.HIGH),
                AntibioticResult("Linezolid", "Susceptible", ParseConfidence.HIGH)
            )
        )

        val result = CultureEpisodeReconciler.reconcile(listOf(preliminary, identification, final))
        val cultures = result.flatMap { it.cultures }

        assertEquals(1, cultures.size)
        assertEquals("Enterococcus faecium", cultures.single().organism)
        assertEquals("final", cultures.single().reportStage)
        assertEquals(2, cultures.single().susceptibility.size)
        assertTrue(cultures.single().susceptibility.any { it.antibiotic == "Vancomycin" && it.interpretation == "Resistant" })
        assertTrue(cultures.single().susceptibility.any { it.antibiotic == "Linezolid" && it.interpretation == "Susceptible" })
    }

    @Test
    fun doesNotCollapseDifferentBottleNumbers() {
        val first = report("one", "final", "Klebsiella pneumoniae", emptyList(), bottle = 1)
        val second = report("two", "final", "Acinetobacter baumannii", emptyList(), bottle = 2)

        val cultures = CultureEpisodeReconciler.reconcile(listOf(first, second)).flatMap { it.cultures }

        assertEquals(2, cultures.size)
    }

    private fun report(
        id: String,
        stage: String,
        organism: String?,
        susceptibility: List<AntibioticResult>,
        bottle: Int = 1
    ): ParsedReport = ParsedReport(
        reportId = id,
        reportName = "Fan Blood Culture Final",
        dateSent = "20-Jul-2025 18:15",
        reportType = "culture",
        cultures = listOf(
            ParsedCultureValue(
                specimen = "Blood",
                site = "Blood",
                collectionDate = "20-Jul-2025 18:15",
                organism = organism,
                growthStatus = GrowthStatus.GROWTH_DETECTED,
                susceptibility = susceptibility,
                explicitResistanceMarkers = emptySet(),
                comments = emptyList(),
                confidence = ParseConfidence.HIGH,
                labStudyNumber = "B21840",
                reportingDate = "29-Jul-2025 16:36",
                reportStage = stage,
                setNumber = 1,
                bottleNumber = bottle,
                isolateNumber = 1,
                organismRaw = organism
            )
        ),
        processorName = "test",
        rawText = "culture report"
    )
}
