package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class ClinicalDiagnosticsV135Test {
    private fun row(test:String, parameter:String, value:String, date:String = "2026-09-29") =
        LabResult(id="source",test=test,parameter=parameter,date=date,
            valueText=value,numericValue=value.toDoubleOrNull())

    @Test fun clinicalHematologyRemovesAbsentSourcePlaceholdersButPreservesNumbersAndSmear() {
        val rows=listOf(
            row("CBC+DIFF","Hemoglobin","14.7"),
            row("CBC+DIFF","Metamyelocytes","Not reported in structured feed"),
            row("CBC+DIFF","Others1","Others"),
            row("CBC+DIFF","WBC'S","Normal in count, distribution and morphology."),
            row("CBC+DIFF","Neutrophils","60")
        )
        val visible=LabPanelMatrix.build(rows,clinical=true).single()
        assertEquals(3,visible.parameters.size)
        assertTrue(visible.parameters.any { it.title=="WBC'S" })
        assertTrue(visible.parameters.none { it.title=="Metamyelocytes" })
        assertEquals(5,LabPanelMatrix.build(rows).single().observationCount)
    }

    @Test fun clinicalOtherRequiresDistinctDateRepeatedNumericResults() {
        val rows=listOf(
            row("TSH","Result","1.2"),
            row("TSH","Result","2.0","2026-07-07"),
            row("Imaging","Findings","No abnormality reported"),
            row("Blood Grouping","Result","A POSITIVE"),
            row("CRP","Result","Serum CRP less than 6 mg/L")
        )
        val clinical=LabPanelMatrix.build(rows,clinical=true).single()
        assertEquals(listOf("TSH"),clinical.parameters.map { it.title })
        assertEquals(5,LabPanelMatrix.build(rows).single().observationCount)
    }

    @Test fun barcodeRequiresValidPatientCrNotOtherNumbers() {
        assertEquals("331012600350500", CrScanParser.parse("331012600350500"))
        assertEquals("331012600350500",
            CrScanParser.parse("CR No: 331012600350500"))
        assertEquals("331012600350500",
            CrScanParser.parse("https://www.nimsts.edu.in/labs?crNo=331012600350500"))
        assertNull(CrScanParser.parse("3310126"))
        assertNull(CrScanParser.parse("https://attacker.example/?crNo=331012600350500"))
        assertNull(CrScanParser.parse("https://www.nimsts.edu.in/labs?crNo=331012600350500&cr=111111111111111"))
        assertNull(CrScanParser.parse("scan barcode 331012600350500 extra digits 666"))
    }

    @Test fun coverageDiagnosticsDoNotExposePatientIdentifiersOrClinicalValues() {
        val bundle=ClinicalBundle(
            patient=PatientInfo(name="Example Patient",crNo="331012600350500"),
            results=listOf(row("CBC","Hemoglobin","14.7")),
            reports=emptyList()
        )
        val report=AppDiagnostics.summarize(bundle)
        assertTrue(report.contains("diagnostics.clinical.structured_numeric=1"))
        assertTrue(report.contains("diagnostics.source.pdf_candidate_none=1"))
        assertFalse(report.contains("Example Patient"))
        assertFalse(report.contains("331012600350500"))
        assertFalse(report.contains("14.7"))
    }
}
