package org.kundakarlab.nimsresults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClinicalPresentationTest {
    private fun result(
        test: String = "Blood culture",
        date: String = "2026-05-05",
        accession: String = "",
        sentDate: String = ""
    ) = LabResult(
        id = "synthetic", test = test, parameter = "Result", date = date,
        valueText = "CULTURE SHOWS GROWTH OF TEST ORGANISM",
        labNumber = accession, sentDate = sentDate
    )

    @Test fun distinctAccessionWinsEvenWithMultipleSameDayCultureReports() {
        val reports = listOf(
            ReportRef("a","Blood culture","2026-05-05","Microbiology","https://nims.example/a",labNumber="ABC100"),
            ReportRef("b","Blood culture","2026-05-05","Microbiology","https://nims.example/b",labNumber="ABC101")
        )
        assertEquals("b", ClinicalPresentation.reportIdentity(listOf(result(accession="ABC101")), reports)?.id)
        assertNull(ClinicalPresentation.reportIdentity(listOf(result()), reports))
        assertNull(ClinicalPresentation.reportIdentity(listOf(result(accession="WRONG")), reports))
    }

    @Test fun matchingByDateRequiresExplicitCollectionDateAndUniqueTestTitle() {
        val row = result(sentDate="05-May-2026")
        val reports = listOf(
            ReportRef("a","Blood culture","05-May-2026","Microbiology","https://nims.example/a",
                sentDate="05-May-2026"),
            ReportRef("b","Blood culture","05-May-2026","Microbiology","https://nims.example/b",
                sentDate="05-May-2026")
        )
        assertNull(ClinicalPresentation.reportIdentity(listOf(row), reports))
        assertEquals("a", ClinicalPresentation.reportIdentity(listOf(row), reports.take(1))?.id)
        assertNull(ClinicalPresentation.reportIdentity(listOf(result()), reports.take(1)))
    }

    @Test fun sourcePanelControlsAreNotClinicalInterpretations() {
        val coded = LabResult("1","Biofire Joint Infection Panel","Combo10","2026-05-05","3",3.0)
        val named = LabResult("2","Biofire Joint Infection Panel","Staphylococcus aureus","2026-05-05","Not detected")
        assertTrue(ClinicalPresentation.isOpaquePanelCode(coded))
        assertFalse(ClinicalPresentation.isOpaquePanelCode(named))
        assertEquals("Unmapped laboratory code: 3", ClinicalPresentation.displayValue(coded))
        val technical = LabResult("3","Widal","Sample Processed:","2026-05-05","-1",-1.0)
        assertTrue(ClinicalPresentation.isTechnical(technical))
        assertEquals("Source code -1 (not a test result)", ClinicalPresentation.displayValue(technical))
    }

    @Test fun radiologyParserDoesNotInventImpressionWhenAbsent() {
        val parsed = RadiologyNarrative.parse(
            "TECHNIQUE: MRI with contrast\nFINDINGS:\nScattered test signal changes\nIMPRESSION:\nNo acute abnormality"
        )
        assertTrue(parsed.technique.contains("MRI"))
        assertTrue(parsed.findings.contains("Scattered"))
        assertEquals("No acute abnormality", parsed.impression)
        val freeText = RadiologyNarrative.parse("A plain narrative without labeled conclusions.")
        assertEquals("", freeText.impression)
        assertTrue(freeText.other.contains("plain narrative"))
    }
}
