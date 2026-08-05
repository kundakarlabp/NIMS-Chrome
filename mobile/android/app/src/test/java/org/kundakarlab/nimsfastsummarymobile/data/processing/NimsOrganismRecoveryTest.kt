package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.model.GrowthStatus
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParseConfidence
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedCultureValue

class NimsOrganismRecoveryTest {
    @Test
    fun recoversEnterococcusSpeciesFromExplicitGrowthStatement() {
        val result = NimsOrganismRecovery.enrich(
            listOf(growth()),
            "CULTURE SHOWS GROWTH OF ENTEROCOCCUS SPECIES. SUSCEPTIBILITY REPORT follows."
        ).single()

        assertEquals("Enterococcus species", result.organism)
    }

    @Test
    fun recoversCommonGramNegativeOrganism() {
        val result = NimsOrganismRecovery.enrich(
            listOf(growth()),
            "Organism isolated: Enterobacter cloacae complex"
        ).single()

        assertEquals("Enterobacter cloacae complex", result.organism)
    }

    @Test
    fun fungalBiomarkerReferenceTextDoesNotCreateOrganism() {
        val result = NimsOrganismRecovery.enrich(
            listOf(growth()),
            "Beta D glucan STAT Test. Cryptococcus neoformans may not reliably elevate this assay. Result positive."
        ).single()

        assertNull(result.organism)
    }

    private fun growth() = ParsedCultureValue(
        specimen = "Blood",
        site = "Blood",
        collectionDate = "20-Jul-2025",
        organism = null,
        growthStatus = GrowthStatus.GROWTH_DETECTED,
        susceptibility = emptyList(),
        explicitResistanceMarkers = emptySet(),
        comments = listOf("Organism not extracted; review the source report."),
        confidence = ParseConfidence.LOW
    )
}
