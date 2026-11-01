package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class ParasitePdfResultsTest {
    private val report = """
        PAGE 2
        PERIPHERAL SMEAR FOR MALARIA
        Investigation       Result
        Smear for MP:       negative
        Parasite Index:
        PERIPHERAL SMEAR FOR MICROFILARIA
        Investigation       Result
        Smear for Microfilaria       Negative
        STRIP TEST FOR MALARIA (PF)
        Investigation       Result
        Strip Test for MP (PF):    Negative
        STRIP TEST FOR MALARIA (PF PV)
        Investigation       Result
        Strip Test for Malaria (PF)    Negative
        Strip Test for Malaria (PV)    Negative
    """.trimIndent()

    @Test fun extractsDistinctNamedResultsFromCombinedPathologyPage() {
        assertEquals("Negative",PdfParasiteSmears.extract(
            "PERIPHERAL SMEAR FOR MALARIA",report))
        assertEquals("Negative",PdfParasiteSmears.extract(
            "Peripheral Smear For Microfilaria",report))
        assertEquals("Negative",PdfParasiteSmears.extract(
            "Strip Test For Mp (Pf)",report))
        assertEquals("Negative",PdfParasiteSmears.extract(
            "Strip Test for Malaria (PF)",report))
        assertEquals("Negative",PdfParasiteSmears.extract(
            "Strip Test for Malaria (PV)",report))
    }

    @Test fun neverDecodesOpaqueNumericDropdownSelections() {
        assertNull(PdfParasiteSmears.extract("Peripheral Smear For Microfilaria",
            "com1: 2"))
        assertTrue(ClinicalReportRenderer.isUninterpretedCode(
            LabResult(id="smear",date="2026-10-28",
                test="Peripheral Smear For Microfilaria",
                parameter="Peripheral Smear For Microfilaria",
                valueText="2")))
        assertTrue(ClinicalReportRenderer.isUninterpretedCode(
            LabResult(id="strip",date="2026-10-28",
                test="Strip Test for Malaria (PV)",
                parameter="Result",valueText="1")))
    }

    @Test fun differentTargetResultMustNotBeBorrowed() {
        assertNull(PdfParasiteSmears.extract(
            "Strip Test for Malaria (PV)","Strip Test for Malaria (PF) Negative"))
        assertNull(PdfParasiteSmears.extract(
            "Peripheral Smear For Microfilaria","Smear for MP: Negative"))
    }
}
