package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.junit.Assert.*
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.model.*
import org.kundakarlab.nimsfastsummarymobile.domain.processing.ProcessingResult
import kotlin.coroutines.*

class LocalTextReportProcessorTest {
    @Test fun parsesUppercaseLowercaseAndComparatorLabs() {
        val text = "HEMOGLOBIN : 10.2 g/dL\nhemoglobin 10.1 g/dL\nHb: 10.2 gm%\nPlatelet Count 150,000 /cumm\nCRP <0.5 mg/L\nPCT >100 ng/mL"
        val result = success(text)
        assertTrue(result.labs.any { it.canonicalCode == "HB" && it.confidence == ParseConfidence.HIGH })
        assertTrue(result.labs.any { it.canonicalCode == "PLT" && it.numericValue == 150000.0 })
        assertTrue(result.labs.toString(), result.labs.any { it.canonicalCode == "CRP" && it.comparator == NumericComparator.LESS_THAN })
        assertTrue(result.labs.any { it.canonicalCode == "PCT" && it.comparator == NumericComparator.GREATER_THAN })
    }
    @Test fun dateBeforeValueIsLowAndExcluded() {
        val result = runSuspend { LocalTextReportProcessor().parseReport(input("01-01-2026 Creatinine 1.2 mg/dL")) }
        assertTrue((result as ProcessingResult.Success).value.labs.any { it.canonicalCode == "CREAT" && it.confidence == ParseConfidence.LOW })
    }
    @Test fun detectsLoginCaptchaAndPdf() {
        assertTrue(runSuspend { LocalTextReportProcessor().parseReport(input("<html>login password</html>", "text/html")) } is ProcessingResult.Failure)
        assertTrue(runSuspend { LocalTextReportProcessor().parseReport(input("captcha required", "text/html")) } is ProcessingResult.Failure)
        assertTrue(runSuspend { LocalTextReportProcessor().parseReport(input("%PDF", "application/pdf")) } is ProcessingResult.Unsupported)
    }
    @Test fun cultureMarkersUseWordBoundaries() {
        assertFalse(CultureTextParser.parse("Creatinine 1.2 mg/dL increased growth", "2026-01-01").flatMap { it.explicitResistanceMarkers }.contains("CRE"))
        assertTrue(CultureTextParser.parse("Blood Culture\nOrganism: Klebsiella pneumoniae\nCRE isolated", "2026-01-01").flatMap { it.explicitResistanceMarkers }.contains("CRE"))
        assertTrue(CultureTextParser.parse("Carbapenem-resistant Klebsiella pneumoniae", "2026-01-01").flatMap { it.explicitResistanceMarkers }.contains("Carbapenem resistant"))
        assertTrue(CultureTextParser.parse("Blood Culture\nOrganism: Acinetobacter baumannii\nCRAB MRSA VRE isolated", "2026-01-01").flatMap { it.explicitResistanceMarkers }.containsAll(listOf("CRAB", "MRSA", "VRE")))
    }
    @Test fun cultureResistanceNegationsAreNotMarkers() {
        assertFalse(CultureTextParser.parse("Blood Culture\nOrganism: Escherichia coli\nno ESBL detected", "2026-01-01").flatMap { it.explicitResistanceMarkers }.contains("ESBL"))
        assertFalse(CultureTextParser.parse("Blood Culture\nOrganism: Klebsiella pneumoniae\nCRE negative", "2026-01-01").flatMap { it.explicitResistanceMarkers }.contains("CRE"))
        assertFalse(CultureTextParser.parse("Blood Culture\nOrganism: Staphylococcus aureus\nMRSA not detected", "2026-01-01").flatMap { it.explicitResistanceMarkers }.contains("MRSA"))
        assertFalse(CultureTextParser.parse("Blood Culture\nOrganism: Escherichia coli\nnot ESBL", "2026-01-01").flatMap { it.explicitResistanceMarkers }.contains("ESBL"))
        assertFalse(CultureTextParser.parse("Blood Culture\nOrganism: Escherichia coli\nESBL negative", "2026-01-01").flatMap { it.explicitResistanceMarkers }.contains("ESBL"))
    }

    @Test fun cultureResistanceMarkerAloneIsUnknownLowConfidence() {
        val result = CultureTextParser.parse("ESBL mentioned", "2026-01-01")
        assertEquals(1, result.size)
        assertEquals(GrowthStatus.UNKNOWN, result.first().growthStatus)
        assertEquals(ParseConfidence.LOW, result.first().confidence)
    }

    @Test fun cultureBlocksKeepPositiveAndNoGrowthSeparate() {
        val result = CultureTextParser.parse("Blood Culture\nOrganism: Escherichia coli\nMeropenem Resistant\n\nUrine Culture\nSpecimen: Urine\nNo growth", "2026-01-01")
        assertTrue(result.any { it.growthStatus == GrowthStatus.GROWTH_DETECTED && it.organism?.contains("coli", true) == true })
        assertTrue(result.any { it.growthStatus == GrowthStatus.NO_GROWTH && it.specimen?.contains("Urine", true) == true })
    }

    @Test fun parsesCommonPanelsAndSusceptibilityAbbreviations() {
        val report = success("RBC 4.5 million/cumm\nPCV 36 %\nMCV 82 fL\neGFR 95 mL/min\nGGT 44 U/L\nTotal Protein 6.8 g/dL\naPTT 32 sec\nBlood Culture\nOrganism: Escherichia coli\nCeftriaxone R\nMeropenem S")
        assertTrue(report.labs.any { it.canonicalCode == "RBC" })
        assertTrue(report.labs.any { it.canonicalCode == "EGFR" })
        assertTrue(report.labs.any { it.canonicalCode == "GGT" })
        assertTrue(report.labs.any { it.canonicalCode == "APTT" })
        assertTrue(report.cultures.flatMap { it.susceptibility }.any { it.antibiotic.contains("Meropenem", true) && it.interpretation == "Susceptible" })
    }
    private fun success(text: String): ParsedReport = (runSuspend { LocalTextReportProcessor().parseReport(input(text)) } as ProcessingResult.Success).value
    private fun input(text: String, type: String = "text/plain") = ReportInput("r1", "Test", "2026-01-01", "lab", type, text.toByteArray())
    @Test fun astParserRejectsWardNamesAndNonAntibioticPhrases() {
        val block = "Blood Culture\nOrganism: Klebsiella pneumoniae\nNeurology Unit Intermediate\nSurgical Ward Resistant\nICU Sensitive"
        val results = CultureTextParser.parse(block, "01-Jan-2026")
        val antibiotics = results.flatMap { it.susceptibility }.map { it.antibiotic }
        // Ward names must never appear in the antibiotic list
        assertFalse("Ward name appeared as antibiotic", antibiotics.any { it.contains("Neurology", true) })
        assertFalse("Ward name appeared as antibiotic", antibiotics.any { it.contains("Surgical", true) })
        assertFalse("Ward name appeared as antibiotic", antibiotics.any { it.contains("ICU", true) })
    }

    @Test fun astParserAcceptsAntibioticsUnderCorrectSectionHeadings() {
        val block = """
            Blood Culture
            Organism: Klebsiella pneumoniae
            SENSITIVITY REPORT
            MEROPENEM
            PIPERACILLIN/TAZOBACTAM
            INTERMEDIATE REPORT
            CEFUROXIME
            RESISTANCE REPORT
            CEFTRIAXONE
        """.trimIndent()
        val results = CultureTextParser.parse(block, "01-Jan-2026")
        val susceptibility = results.flatMap { it.susceptibility }
        assertTrue("Expected Meropenem Susceptible", susceptibility.any { it.antibiotic.contains("Meropenem", true) && it.interpretation == "Susceptible" })
        assertTrue("Expected Cefuroxime Intermediate", susceptibility.any { it.antibiotic.contains("Cefuroxime", true) && it.interpretation == "Intermediate" })
        assertTrue("Expected Ceftriaxone Resistant", susceptibility.any { it.antibiotic.contains("Ceftriaxone", true) && it.interpretation == "Resistant" })
    }

    @Test fun astParserHandlesInlineFormatInsideSection() {
        val block = """
            Urine Culture
            Organism: Escherichia coli
            SENSITIVITY REPORT
            Meropenem Susceptible
            Ceftriaxone Resistant
            Ciprofloxacin Intermediate
        """.trimIndent()
        val results = CultureTextParser.parse(block, "01-Jan-2026")
        val susceptibility = results.flatMap { it.susceptibility }
        assertTrue("Expected Meropenem Susceptible", susceptibility.any { it.antibiotic.contains("Meropenem", true) && it.interpretation == "Susceptible" })
        assertTrue("Expected Ceftriaxone Resistant", susceptibility.any { it.antibiotic.contains("Ceftriaxone", true) && it.interpretation == "Resistant" })
    }

    @Test fun looksLikeReportAcceptsCoagulationReport() {
        // REGRESSION: APTT/PT reports were returning Unsupported because "thromboplastin"
        // and "prothrombin" were not in the looksLikeReport keyword list.
        val aptText = "Activated Partial Thromboplastin Time (APTT) 32 sec\nProthrombin Time 12 sec\nINR 1.1"
        val result = success(aptText)
        assertTrue("APTT should be parsed", result.labs.any { it.canonicalCode == "APTT" })
        assertTrue("INR should be parsed", result.labs.any { it.canonicalCode == "INR" })
    }

}

private fun <T> runSuspend(block: suspend () -> T): T { var value: Result<T>? = null; block.startCoroutine(object : Continuation<T> { override val context = EmptyCoroutineContext; override fun resumeWith(result: Result<T>) { value = result } }); return value!!.getOrThrow() }

    // REGRESSION: "Neurology Unit Intermediate" was being returned as an AST result
    // because the old broad regex matched any English words before S/I/R keywords.
    // The new section-state parser requires an explicit sensitivity heading before
    // accepting any tokens, and then only accepts known antibiotic names.
