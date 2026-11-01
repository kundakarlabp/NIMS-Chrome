package org.kundakarlab.nimsresults

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CompactMicroCoombsV148Test {
    private fun row(test: String = "AEROBIC CULTURE",
                    value: String = "NO GROWTH",
                    lab: String = "E18848",
                    sample: String = "26BCT22946",
                    specimen: String = "Pleural fluid",
                    collection: String = "2026-10-29",
                    reported: String = "2026-10-30") = LabResult(
        id = "r1", test = test, parameter = "Result",
        date = "2026-10-30", valueText = value,
        labNumber = lab, sampleNumber = sample,
        specimenType = specimen,
        collectionDateTime = collection, reportedDate = reported
    )

    @Test fun cultureHeaderHasAtMostTwoLinesAndNoDuplicateReportedDay() {
        val lines = ClinicalResultsLayout.compactMicrobiologyHeader(
            listOf(row()), emptyList(), null, LocalDate.parse("2026-10-30"))
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("Lab E18848"))
        assertTrue(lines[0].contains("Sample 26BCT22946"))
        assertTrue(lines[0].contains("Specimen Pleural fluid"))
        assertTrue(lines[1].contains("Collected"))
        assertFalse(lines.joinToString(" ").contains("Reported"))
        assertFalse(lines.joinToString(" ").contains("Status"))
    }

    @Test fun preserveDifferentReportedDateAndBloodCultureSampleNumber() {
        val data = row(test = "BLOOD CULTURE", reported = "2026-10-31")
        val lines = ClinicalResultsLayout.compactMicrobiologyHeader(
            listOf(data), emptyList(), null, LocalDate.parse("2026-10-30"),
            bloodCulture = true)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("Sample 26BCT22946"))
        assertTrue(lines[1].contains("Reported"))
    }

    @Test fun numericSpecimenCodeCannotBecomeClinicalSample() {
        val lines = ClinicalResultsLayout.compactMicrobiologyHeader(
            listOf(row(specimen="3")),emptyList(),null,LocalDate.parse("2026-10-30"))
        assertFalse(lines.joinToString(" ").contains("Specimen 3"))
    }

    @Test fun directAndIndirectAreParsedIndependentlyFromSharedPdf() {
        val pdf = """
            CR No: 123456789012345
            Direct Coombs Test (GEL)
            Result: Positive
            Indirect Coombs Test (GEL)
            Result: Negative
        """.trimIndent()
        assertEquals("Positive",PdfClinicalEvidence.coombs("Direct Coombs Test (GEL)",pdf))
        assertEquals("Negative",PdfClinicalEvidence.coombs("Indirect Coombs Test (GEL)",pdf))
    }

    @Test fun coombsResultCanFollowHeadingOrResultLabelOnSeparateLine() {
        val pdf = """
            Indirect Coombs Test (GEL)
            Result
            Weak Positive
        """.trimIndent()
        assertEquals("Weak positive", PdfClinicalEvidence.coombs("Indirect Coombs Test (GEL)",pdf))
        assertEquals("Negative",PdfClinicalEvidence.coombs(
            "Direct Coombs Test (GEL)","Direct Coombs Test (GEL): Negative"))
    }

    @Test fun rejectAnonymousNumericCodeAndOtherModeResult() {
        assertNull(PdfClinicalEvidence.coombs("Direct Coombs Test (GEL)", "com1: 2"))
        assertNull(PdfClinicalEvidence.coombs("Direct Coombs Test (GEL)",
            "Indirect Coombs Test (GEL)\nResult: Negative"))
        assertNull(PdfClinicalEvidence.coombs("Indirect Coombs Test (GEL)",
            "Direct Coombs Test (GEL)\nResult: Positive"))
    }
}