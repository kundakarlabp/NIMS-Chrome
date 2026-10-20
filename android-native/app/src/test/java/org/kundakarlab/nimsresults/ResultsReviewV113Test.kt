package org.kundakarlab.nimsresults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultsReviewV113Test {
    private fun field(test: String, parameter: String, value: String,
                      date: String = "2025-07-12") =
        LabResult("synthetic", test, parameter, date, value)

    @Test fun urineRoutineUsesDateComparisonDespiteQualitativeAndRangeValues() {
        val test = "COMPLETE URINE EXAMINATION (CHEMISTRY + MICROSCOPY)"
        val rows = listOf(field(test, "Pus Cells(Result)", "10-12"),
            field(test, "Red Cells(Result)", "14-16"),
            field(test, "Bacteria(Result)", "Nil"),
            field(test, "Protein(Result)", "3"))
        assertTrue(ClinicalReportRenderer.isUrineRoutine(test))
        assertEquals(ClinicalLayout.SERIES, ClinicalReportRenderer.layout(test, rows))
        assertFalse(ClinicalReportRenderer.isPlaceholder(rows.first()))
    }

    @Test fun tbFluorescenceAndAuramineAreMicrobiologyNotLabs() {
        assertTrue(ClinicalReportRenderer.isMicrobiologyInvestigation(
            "TB FLUORESCENCE STAINING (AR)"))
        assertTrue(ClinicalReportRenderer.isMicrobiologyInvestigation(
            "Auramine Rhodamine Fluorescent Stain"))
        assertFalse(ClinicalReportRenderer.isMicrobiologyInvestigation(
            "Histopathology with prior TB culture noted"))
    }

    @Test fun intermediateAntibiogramHeadingIsIndependent() {
        val parsed = CultureNarrative.parse(
            "CULTURE REPORT GROWTH OF ACINETOBACTER BAUMANNII " +
            "SENSITIVITY REPORT MINOCYCLINE " +
            "INTERMEDIATE REPORT COLISTIN " +
            "RESISTANCE REPORT MEROPENEM")
        assertEquals("MINOCYCLINE", parsed.susceptible)
        assertEquals("COLISTIN", parsed.intermediate)
        assertEquals("MEROPENEM", parsed.resistant)
    }

    @Test fun diagnosticsAreSafeAndUsefulWithoutValueOrPatientDisclosure() {
        val secretCr = "123456789012345"
        val source = listOf(field("AEROBIC CULTURE", "Result",
            "Patient named Example PERSON culture Acinetobacter baumannii"))
        val info = ReportDiagnostics.describe("AEROBIC CULTURE", source,
            emptyList(), secretCr)
        assertTrue(info.contains("pdf_mapping_status=no_verified_identity"))
        assertTrue(info.contains("source_rows=1"))
        assertFalse(info.contains(secretCr))
        assertFalse(info.contains("Example PERSON"))
        assertFalse(info.contains("Acinetobacter baumannii"))
    }
    @Test fun malformedGreaterThanSymbolIsRestoredWithoutChangingOtherText() {
        val cr = "123456789012345"
        val raw = """{"status":1,"crNo":"123456789012345","groups":[{"testName":"C REACTIVE PROTEIN","rows":[{"parameterName":"Result","valuesByDate":{"27-Jun-2025":"( gt;=6 mg/1000ml )"}}]}]}"""
        val bundle = BulkParser.parse(raw, cr, PatientInfo(), emptyList())
        assertEquals("( >=6 mg/1000ml )", bundle.results.single().valueText)
    }

}
