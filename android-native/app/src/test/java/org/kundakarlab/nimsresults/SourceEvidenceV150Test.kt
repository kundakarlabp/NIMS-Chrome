package org.kundakarlab.nimsresults

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class SourceEvidenceV150Test {
    private fun row(test:String,field:String,value:String) = LabResult(
        id = "$test$field", test=test, parameter=field,
        date="2026-10-30", valueText=value
    )

    @Test fun validNamedSpecimenAndAppearanceMayBeShownButHISCodesCannot() {
        val study="CSF/Pleural/Ascitic/Syno fld, (Protein,SU"
        assertFalse(ClinicalReportRenderer.isUninterpretedCode(
            row(study,"Nature Of Specimen","CSF")))
        assertFalse(ClinicalReportRenderer.isUninterpretedCode(
            row(study,"Physical Appearance","Hazy")))
        for (code in listOf("3","8","+","-","2.0","unknown", "CSF/Pleural/Ascitic")) {
            assertTrue("Unsafe specimen label: $code",
                ClinicalReportRenderer.isUninterpretedCode(
                    row(study,"Nature Of Specimen",code)))
        }
    }

    @Test fun genericFluidPanelTitleIsNotPatientSpecimen() {
        assertEquals("",PdfClinicalEvidence.specimen(
            "Specimen: CSF/Pleural/Ascitic/Syno"))
        assertEquals("Pleural fluid",PdfClinicalEvidence.specimen(
            "Sample Type/No: Pleural Fluid/P5631"))
    }

    @Test fun urineResultsAreClinicallyNamedWithoutLosingRawHIS() {
        val original=row("COMPLETE URINE EXAMINATION (CHEMISTRY + MICROSCOPY)",
            "Bacteria(Result)","Nil")
        assertEquals("Bacteria",ClinicalChronology.parameterLabel(original))
        assertEquals("Bacteria(Result)", original.parameter)
    }

    @Test fun labelledUrinePdfAcceptsWordsAndQualitativeSymbols() {
        val pdf="pH 6.0\nSpecific gravity 1.030\nKetones +/-\nGlucose Negative\nProtein +++\nNitrite Negative"
        val results=PdfUrineSummaries.extract(pdf).associate { it.label to it.result }
        assertEquals("6.0",results["pH"])
        assertEquals("1.030",results["Specific gravity"])
        assertEquals("+/-",results["Ketones"])
        assertEquals("Negative",results["Glucose"])
        assertEquals("+++",results["Protein"])
    }

    @Test fun coombsOnlyExplicitNamedQualitativeResultCanBeRead() {
        val test="Direct Coombs Test (GEL)"
        assertEquals("Negative",ClinicalSourceQualitative.coombs(
            listOf(row(test,"Result","Negative"))))
        assertEquals("Weak positive",ClinicalSourceQualitative.coombs(
            listOf(row("Indirect Coombs Test (GEL)","IAT Result","Weak Positive"))))
        assertNull(ClinicalSourceQualitative.coombs(
            listOf(row(test,"com1","2"))))
        assertNull(ClinicalSourceQualitative.coombs(
            listOf(row(test,"com1","Negative"))))
        assertNull(ClinicalSourceQualitative.coombs(
            listOf(row(test,"Result","+"))))
    }

    @Test fun directAndIndirectAliasesMatchSeparateDocumentTitles() {
        val direct=SourceMetadataReview.pdfCandidates(
            listOf(row("Direct Coombs Test (GEL)","Result","1")),
            listOf(ReportRef(id="d",title="DAT Gel",url="https://www.nimsts.edu.in/HIS/test",
                collectionDateTime="2026-10-30")))
        assertEquals(1,direct.size)
        val indirect=SourceMetadataReview.pdfCandidates(
            listOf(row("Indirect Coombs Test (GEL)","Result","2")),
            listOf(ReportRef(id="i",title="IAT Gel",url="https://www.nimsts.edu.in/HIS/test",
                collectionDateTime="2026-10-30")))
        assertEquals(1,indirect.size)
        assertEquals("Negative",PdfClinicalEvidence.coombs("Direct Coombs Test (GEL)",
            "DAT (Gel)\nResult: Negative"))
        assertEquals("Positive",PdfClinicalEvidence.coombs("Indirect Coombs Test (GEL)",
            "IAT (Gel)\nResult: Positive"))
    }

    @Test fun unprovenReportIndexIsLabeledCandidateWithoutVerboseIndexText() {
        val r=row("GRAMS STAINING","Result","No organisms")
        val pdf=ReportRef(id="p",title="GRAMS STAINING",labNumber="E18848",
            sampleNumber="26BCT22946",collectionDateTime="2026-10-30")
        val compact=ClinicalResultsLayout.compactMicrobiologyHeader(
            listOf(r), listOf(pdf), null, LocalDate.parse("2026-10-30"))
        assertTrue(compact.size<=2)
        assertTrue(compact.first().contains("PDF candidate"))
        assertFalse(compact.joinToString(" ").contains("Report index"))
        assertTrue(compact.any { it.contains("E18848") })
    }
}
