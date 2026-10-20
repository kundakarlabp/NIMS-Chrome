package org.kundakarlab.nimsresults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClinicalReportRendererTest {
    private fun row(test: String, parameter: String, value: String, numeric: Double? = null,
                    date: String = "2026-05-05") = LabResult(
        id = test + parameter + date, test = test, parameter = parameter,
        date = date, valueText = value, numericValue = numeric
    )

    @Test fun eachClinicalResultTypeUsesAnAppropriateLayout() {
        assertEquals(ClinicalLayout.SERIES,
            ClinicalReportRenderer.layout("CBC+DIFF", listOf(row("CBC+DIFF", "Hemoglobin", "10.3",10.3))))
        assertEquals(ClinicalLayout.SERIES,
            ClinicalReportRenderer.layout("ELECTROLYTES(Na K&Cl)", emptyList()))
        assertEquals(ClinicalLayout.QUALITATIVE,
            ClinicalReportRenderer.layout("CRP AGGLUTINATION",
                listOf(row("CRP AGGLUTINATION", "Result", "< 6 mg/1000ml - Negative"))))
        assertEquals(ClinicalLayout.QUALITATIVE,
            ClinicalReportRenderer.layout("GRAMS STAINING", emptyList()))
        assertEquals(ClinicalLayout.QUALITATIVE,
            ClinicalReportRenderer.layout("HCV ELFA / ELISA", emptyList()))
        assertEquals(ClinicalLayout.NARRATIVE,
            ClinicalReportRenderer.layout("F18 FDG WHOLE BODY PET CT (WITH CONTRAST)", emptyList()))
        assertEquals(ClinicalLayout.SOURCE_CODED,
            ClinicalReportRenderer.layout("Biofire Joint Infection Panel", emptyList()))
        assertEquals(ClinicalLayout.SOURCE_CODED,
            ClinicalReportRenderer.layout("Blood Grouping & Antibody Screening", emptyList()))
    }

    @Test fun hidesUnmappedSourceCodesButKeepsConfirmedSourceText() {
        assertTrue(ClinicalReportRenderer.isUninterpretedCode(
            row("COMPLETE URINE EXAMINATION", "/LPF(Investigation)", "-1", -1.0)))
        assertTrue(ClinicalReportRenderer.isUninterpretedCode(
            row("COMPLETE URINE EXAMINATION", "Negative(Result)", "1", 1.0)))
        assertTrue(ClinicalReportRenderer.isUninterpretedCode(
            row("COMPLETE URINE EXAMINATION", "/HPF(Result)", "1-2")))
        assertTrue(ClinicalReportRenderer.isUninterpretedCode(
            row("Biofire Joint Infection Panel", "com4", "3", 3.0)))
        assertFalse(ClinicalReportRenderer.isUninterpretedCode(
            row("Dengue IgM ELFA", "Result", "Negative - IV: 0.23 (IV <1.00 - Negative)")))
        assertFalse(ClinicalReportRenderer.isUninterpretedCode(
            row("CBC", "Hemoglobin", "13.8", 13.8)))
        assertTrue(ClinicalReportRenderer.isPlaceholder(
            row("CBC", "Platelet count", "--")))
        assertFalse(ClinicalReportRenderer.isPlaceholder(
            row("CRP", "Result", "<6 mg/1000ml - Negative")))
    }

    @Test fun prefersCompleteNarrativeAndPreventsDuplicatedResultTextarea() {
        val full = "F-18 FDG PET-CT Clinical History: " +
            "Prior surgery. Technique: study performed. Findings: ring lesion. Impression: correlate clinically."
        val rows = listOf(
            row("PET CT", "Result", full),
            row("PET CT", "textarea", full),
            row("PET CT", "Test Done By", "-1"))
        val preferred = ClinicalReportRenderer.preferredRows(rows)
        assertEquals(1, preferred.size)
        assertEquals(full, preferred.single().valueText)
    }

    @Test fun embeddedRadiologyHeadingsProduceDistinctSourceSections() {
        val all = ClinicalReportRenderer.sourceSections(
            "Clinical History: follow-up. Technique: PET CT performed. " +
            "Findings: left occipital uptake. Impression: no other focus."
        )
        assertEquals(listOf("Clinical History", "Technique", "Findings", "Impression"),
            all.map { it.heading })
        assertTrue(all[2].content.contains("occipital"))
        val free = ClinicalReportRenderer.sourceSections("Unstructured histopathology text")
        assertEquals(1, free.size)
        assertEquals("Source result", free.single().heading)
    }

    @Test fun priorCultureMentionInImagingDoesNotRouteReportToMicrobiology() {
        assertFalse(ClinicalReportRenderer.isMicrobiologyInvestigation("PET CT WHOLE BODY"))
        assertTrue(ClinicalReportRenderer.isMicrobiologyInvestigation("AEROBIC CULTURE"))
        assertTrue(ClinicalReportRenderer.isMicrobiologyInvestigation("GeneXpert"))
        assertFalse(ClinicalReportRenderer.isMicrobiologyInvestigation("CRP AGGLUTINATION"))
    }
}
