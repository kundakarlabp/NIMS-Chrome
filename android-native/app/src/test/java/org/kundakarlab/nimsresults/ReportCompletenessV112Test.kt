package org.kundakarlab.nimsresults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportCompletenessV112Test {
    private fun lab(test: String, date: String = "2025-07-08", accession: String = "") =
        LabResult(id = "source-row", test = test, parameter = "STAINING", date = date,
            valueText = "No organisms seen", labNumber = accession)

    private fun ref(id: Int, test: String, date: String = "08-Jul-2025",
                    accession: String = "") = ReportRef(
        id = "report-$id", title = test, date = date, department = "Microbiology",
        url = "https://www.nimsts.edu.in/synthetic-report-$id.pdf", labNumber = accession
    )

    @Test fun microbiologyIncludesStainsPanelsAndMicrobiologyDiagnostics() {
        listOf("GRAMS STAINING", "Gram stain", "Aerobic Culture",
            "AFB smear", "GeneXpert MTB", "BioFire Meningitis panel",
            "KOH preparation", "Calcofluor stain").forEach {
            assertTrue("Missing microbiology report: $it",
                ClinicalReportRenderer.isMicrobiologyInvestigation(it))
        }
        assertFalse(ClinicalReportRenderer.isMicrobiologyInvestigation(
            "CT chest reporting previous culture from clinical history"))
        assertFalse(ClinicalReportRenderer.isMicrobiologyInvestigation("CBC"))
    }

    @Test fun unrelatedSameDayPdfIsNotAResultSourceCandidate() {
        val inventory = (1..40).map { ref(it, "Other blood chemistry") }
        val choice = ReportLinkResolver.candidates(listOf(lab("GRAMS STAINING")), inventory)
        assertNull(choice.verified)
        assertTrue(choice.candidates.isEmpty())
    }

    @Test fun sameInvestigationSourceCandidatesAreBoundedAndNotAutoVerified() {
        val inventory = (1..40).map { ref(it, "Gram stain") }
        val choice = ReportLinkResolver.candidates(listOf(lab("GRAMS STAINING")), inventory)
        assertNull(choice.verified)
        assertEquals(8, choice.candidates.size)
    }

    @Test fun differentPatientPdfHeaderCannotBeRescuedByMatchingAccession() {
        ReportTitleCache.clear()
        val uri = "https://www.nimsts.edu.in/synthetic-wrong-patient.pdf"
        val report = ReportRef("wrong", "Aerobic Culture", "08-Jul-2025",
            "Microbiology", uri, labNumber = "25M02019")
        assertTrue(ReportTitleCache.learn(uri,
            "CR No: 111111111111111\nLab/Study No.: 25M02019\nAerobic Culture",
            "111111111111111"))
        val choice = ReportLinkResolver.candidates(
            listOf(lab("Aerobic Culture", accession = "25M02019")),
            listOf(report), "222222222222222")
        assertNull(choice.verified)
        ReportTitleCache.clear()
    }

    @Test fun explicitRhAndAntibodyScreenResultCanBeReadWithoutControlMapping() {
        val source = """
            Grouping and Antibody Screening
            Anti A  Anti B  Anti D  A1 Cells  B Cells
            Neg     4+      4+      4+       Neg
            ABO Blood Group: B
            Rh(D): POSITIVE
            Antibody Screening: Non Reactive
        """.trimIndent()
        val answer = PdfClinicalSummaries.bloodGrouping(source)
        assertEquals("B Positive", answer?.group)
        assertEquals("Negative", answer?.antibodyScreen)
    }

    @Test fun reactionsAndUnmappedCodesCannotCreateAClinicalBloodGroup() {
        val source = """
            Blood Grouping & Antibody Screening
            Anti-A 4+
            Anti-B 0
            Rh(D) 4+
            A1 Cells Negative
            Combo1 1
            Result: 1
        """.trimIndent()
        assertNull(PdfClinicalSummaries.bloodGrouping(source))
        assertTrue(ClinicalReportRenderer.isUninterpretedCode(
            LabResult("coded","Blood Grouping","Result","2025-07-08","1")))
    }

    @Test fun contradictoryFinalConclusionsDoNotSelectFirstOne() {
        assertNull(PdfClinicalSummaries.bloodGrouping("""
            Blood grouping and antibody screening
            RESULT: O POSITIVE
            RESULT: A NEGATIVE
        """.trimIndent()))
    }

    @Test fun explicitCombinedConclusionRemainsSupported() {
        val parsed = PdfClinicalSummaries.bloodGrouping("""
            Department of Immuno Haematology
            Grouping and Antibody Screening
            RESULT: O POSITIVE
            2. Antibody Screening Negative
        """.trimIndent())
        assertEquals("O Positive", parsed?.group)
        assertEquals("Negative", parsed?.antibodyScreen)
    }

    @Test fun hsCrpAndEsrTrendAliasesAreQuantitativeOnly() {
        val row = LabResult("hscrp", "hsCRP", "High Sensitivity CRP", "2025-07-08", "2.3",
            numericValue = 2.3, unit = "mg/L")
        assertTrue(ClinicalResultRules.isTrendEligible(row))
        assertFalse(ClinicalResultRules.isTrendEligible(row.copy(valueText = "<2.3", numericValue = null)))
    }
}
