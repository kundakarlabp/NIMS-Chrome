package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

/** Regression fixtures for alternate NIMS blood-bank PDF rendering. */
class CoombsGradedPdfTest {
    @Test fun readsIndependentGradedDatAndIatWithoutPromotingControl() {
        val report = """
            Department of Immuno Hematology And Blood Transfusion
            Direct Coombs Test     4+     Control: 2+
            Indirect Coombs Test    1+
            Result:
            Comments:
            ADVICE - MONOSPECIFIC DAT WORKUP
        """.trimIndent()
        assertEquals("4+",PdfClinicalEvidence.coombs("Direct Coombs Test (GEL)",report))
        assertEquals("1+",PdfClinicalEvidence.coombs("Indirect Coombs Test (GEL)",report))
        assertEquals("2+",PdfClinicalEvidence.coombsControl("Direct Coombs Test (GEL)",report))
        assertNull(PdfClinicalEvidence.coombsControl("Indirect Coombs Test (GEL)",report))
        assertEquals("ADVICE - MONOSPECIFIC DAT WORKUP",
            PdfClinicalEvidence.coombsComment(report))
    }

    @Test fun handlesPositionedPdfTablePipesAndSplitGradeLabels() {
        val geometry = """
            PAGE 1
            Direct Coombs Test   |   4+   |   Control:   |   2+
            Indirect Coombs Test
            Result:
            1+
            Comments:   |   ADVICE - MONOSPECIFIC DAT WORKUP
        """.trimIndent()
        assertEquals("4+",PdfClinicalEvidence.coombs("Direct Coombs Test",geometry))
        assertEquals("1+",PdfClinicalEvidence.coombs("Indirect Coombs Test",geometry))
        assertEquals("2+",PdfClinicalEvidence.coombsControl("Direct Coombs Test",geometry))
        assertEquals("ADVICE - MONOSPECIFIC DAT WORKUP",
            PdfClinicalEvidence.coombsComment(geometry))
    }

    @Test fun neverConfusesNumericCodesOrControlOnlyWithPatientResult() {
        assertNull(PdfClinicalEvidence.coombs("Direct Coombs Test","com1: 4"))
        assertNull(PdfClinicalEvidence.coombs("Direct Coombs Test",
            "Direct Coombs Test\nControl: 2+"))
        assertNull(PdfClinicalEvidence.coombs("Indirect Coombs Test",
            "Direct Coombs Test 4+\nControl: 2+"))
        assertNull(PdfClinicalEvidence.coombs("Direct Coombs Test",
            "Direct Coombs Test 4+\nResult: Negative\nControl: 2+"))
    }

    @Test fun legacyQualitativeReportsRemainSupported() {
        val report = """
            Direct Coombs Test (GEL)
            Result: Traces   Control: Neg
            Indirect Coombs Test (GEL)
            Result: Negative
        """.trimIndent()
        assertEquals("Traces",PdfClinicalEvidence.coombs("Direct Coombs Test",report))
        assertEquals("Negative",PdfClinicalEvidence.coombs("Indirect Coombs Test",report))
        assertEquals("Neg",PdfClinicalEvidence.coombsControl("Direct Coombs Test",report))
    }
}
