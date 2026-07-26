package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.model.GrowthStatus

class NimsCultureTextParserTest {
    @Test
    fun parsesBloodBottleFinalReportAndMic() {
        val text = """
            Lab/Study No. : B21921
            Coll./Study Date : 02-Jun-2026 17:26
            Reporting Date : 15-Jun-2026 16:14
            Fan Blood Culture - Second Bottle of first Set (Final Report)
            Sample Processed : Blood
            CULTURE REPORT
            CULTURE SHOWS GROWTH OF METHICILLIN RESISTANT STAPHYLOCOCCUS AUREUS (MRSA)
            SENSITIVITY REPORT
            VANCOMYCIN TEICOPLANIN LINEZOLID LEVOFLOXACIN ( MIC 0.25 mcg/ml)
            RESISTANCE REPORT
            PENICILLIN OXACILLIN CIPROFLOXACIN
            COLLECTION: PERIPHERAL
        """.trimIndent()

        val result = NimsCultureMetadataEnricher.enrich(NimsCultureTextParser.parse(text), text).single()
        assertEquals("B21921", result.labStudyNumber)
        assertEquals(2, result.bottleNumber)
        assertEquals(1, result.setNumber)
        assertEquals("final", result.reportStage)
        assertEquals("Staphylococcus aureus", result.organism)
        assertTrue("MRSA" in result.explicitResistanceMarkers)
        assertEquals("PERIPHERAL", result.site)
        val levofloxacin = result.susceptibility.first { it.antibiotic == "Levofloxacin" }
        assertEquals(0.25, levofloxacin.micValue!!, 0.001)
        assertEquals("Susceptible", levofloxacin.interpretation)
        assertTrue(result.susceptibility.filterNot { it.antibiotic == "Levofloxacin" }.all { it.micValue == null })
    }

    @Test
    fun micDoesNotCrossAnAntibioticOrLineBoundary() {
        val text = """
            Sample Processed: Blood
            CULTURE REPORT
            CULTURE SHOWS GROWTH OF KLEBSIELLA PNEUMONIAE
            SENSITIVITY REPORT
            AMIKACIN
            MEROPENEM MIC <= 1 mg/L
        """.trimIndent()

        val result = NimsCultureTextParser.parse(text).single().susceptibility

        assertEquals(null, result.first { it.antibiotic == "Amikacin" }.micValue)
        assertEquals(1.0, result.first { it.antibiotic == "Meropenem" }.micValue!!, 0.001)
    }

    @Test
    fun retainsPreliminaryAndFinalForSameBottle() {
        val text = """
            Lab/Study No. : B100
            Coll./Study Date : 01-Jun-2026 10:00
            Fan Blood Culture - First Bottle of first Set (48 Hours Preliminary Report)
            Sample Processed : Blood
            CULTURE REPORT
            No growth at 48 hours
            Fan Blood Culture - First Bottle of first Set (Final Report)
            Sample Processed : Blood
            CULTURE REPORT
            CULTURE SHOWS GROWTH OF KLEBSIELLA PNEUMONIAE
            SENSITIVITY REPORT
            MEROPENEM AMIKACIN
            RESISTANCE REPORT
            CEFTRIAXONE CIPROFLOXACIN
        """.trimIndent()

        val result = NimsCultureMetadataEnricher.enrich(NimsCultureTextParser.parse(text), text)
        assertEquals(2, result.size)
        assertTrue(result.any { it.reportStage == "48-hour preliminary" && it.growthStatus == GrowthStatus.NO_GROWTH })
        assertTrue(result.any { it.reportStage == "final" && it.organism == "Klebsiella pneumoniae" })
    }

    @Test
    fun parsesTwoIsolatesSeparately() {
        val text = """
            Lab/Study No. : E500
            Coll./Study Date : 03-Jun-2026 12:00
            Sample Processed : WOUND SWAB
            ISOLATE 1
            CULTURE REPORT
            CULTURE SHOWS GROWTH OF ACINETOBACTER BAUMANNII
            INTERMEDIATE REPORT
            COLISTIN
            RESISTANCE REPORT
            MEROPENEM AMIKACIN CIPROFLOXACIN
            ISOLATE 2
            CULTURE REPORT
            CULTURE SHOWS GROWTH OF STAPHYLOCOCCUS AUREUS
            SENSITIVITY REPORT
            VANCOMYCIN LINEZOLID
            RESISTANCE REPORT
            PENICILLIN OXACILLIN
        """.trimIndent()

        val result = NimsCultureTextParser.parse(text)
        assertEquals(2, result.size)
        assertTrue(result.any { it.isolateNumber == 1 && it.organism == "Acinetobacter baumannii" })
        assertTrue(result.any { it.isolateNumber == 2 && it.organism == "Staphylococcus aureus" })
    }

    @Test
    fun keepsGramStainOnlyPreliminaryObservation() {
        val text = """
            Lab/Study No. : E9125
            Coll./Study Date : 01-Jun-2026 15:22
            Reporting Date : 02-Jun-2026 08:58
            Sample Processed: WOUND SWAB
            STAINING
            GRAMS SMEAR SHOWS GRAM NEGATIVE BACILLI IN OCCASIONAL OIL FIELD ALONG WITH <5 PUS CELLS / HPF
        """.trimIndent()

        val result = NimsCultureTextParser.parse(text).single()
        assertEquals(GrowthStatus.UNKNOWN, result.growthStatus)
        assertTrue(result.gramStain!!.contains("GRAM NEGATIVE BACILLI", true))
        assertEquals("WOUND SWAB", result.specimen)
    }

    @Test
    fun nilSensitivityIsAnExplicitEmptySection() {
        val text = """
            Sample Processed: PUS
            CULTURE REPORT
            CULTURE SHOWS GROWTH OF ACINETOBACTER BAUMANNII
            SENSITIVITY REPORT
            NIL
            INTERMEDIATE REPORT
            COLISTIN
            RESISTANCE REPORT
            PIPERACILLIN/TAZOBACTAM CEFTAZIDIME CEFOPERAZONE+SULBACTAM CEFEPIME IMIPENEM MEROPENEM
        """.trimIndent()
        val result = NimsCultureTextParser.parse(text).single()
        assertTrue(result.susceptibility.none { it.interpretation == "Susceptible" })
        assertTrue(result.susceptibility.any { it.antibiotic == "Colistin" && it.interpretation == "Intermediate" })
        assertTrue(result.susceptibility.any { it.antibiotic == "Meropenem" && it.interpretation == "Resistant" })
    }
}
