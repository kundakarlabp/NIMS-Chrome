package org.kundakarlab.nimsresults

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FluidResultsV151Test {
    private fun row(test:String, name:String, value:String): LabResult =
        LabResult(id=test+name,test=test,parameter=name,date="2026-10-29",
            valueText=value)
    private val fluid="CSF/Pleural/Ascitic/Syno fld, (Protein,SU"
    private fun chemistry(glucose:String,protein:String,albumin:String)=listOf(
        row(fluid,"Glucose.",glucose),row(fluid,"Protein.",protein),
        row(fluid,"Albumin",albumin)
    )

    @Test fun asciticFluidWithStrawAppearanceAndThreeValues() {
        val pdf="""
            CR No: 123456789012345
            Fluid Chemistry
            Nature Of Specimen | Ascitic Fluid
            Physical Appearance | Straw Coloured
            CSF
            Glucose | mg/dl | 50 - 80
            Pleural/Ascitic/Drain
            Glucose. | 109 | mg/dl
            Protein. | 2.0 | g/dl
            Albumin | 1.0 | g/dl
        """.trimIndent()
        val e=PdfFluidChemistry.reconcile(chemistry("109","2.0","1.0"),pdf)
        assertNotNull(e)
        assertEquals("Ascitic fluid",e!!.specimen)
        assertEquals("Straw Coloured",e.appearance)
        assertEquals(3,e.matchedValues)
        assertEquals("mg/dL",PdfFluidChemistry.unitFor("Glucose.",e))
        assertEquals("g/dL",PdfFluidChemistry.unitFor("Albumin",e))
    }

    @Test fun asciticFluidWithYellowAppearanceAndLessThanQualifiers() {
        val pdf="""
            Nature Of
            Ascitic Fluid
            Specimen
            Physical
            Yellow
            Appearance
            Pleural/Ascitic/Drain
            Glucose. 111 mg/dl
            Protein. < 2.0 g/dl
            Albumin < 1.0 g/dl
        """.trimIndent()
        val e=PdfFluidChemistry.reconcile(
            chemistry("111","Less than 2.0","<1.0"),pdf)
        assertNotNull(e)
        assertEquals("Ascitic fluid",e!!.specimen)
        assertEquals("Yellow",e.appearance)
    }

    @Test fun wrongResultOrGenericPanelDoesNotTransferSpecimen() {
        val wrong="""Nature Of Specimen | Ascitic Fluid
            Glucose. 111
            Protein. <2.0
            Albumin <1.0""".trimIndent()
        assertNull(PdfFluidChemistry.reconcile(
            chemistry("109","2.0","1.0"),wrong))
        assertNull(PdfFluidChemistry.reconcile(
            chemistry("109","2.0","1.0"),
            "CSF/Pleural/Ascitic/Syno\nGlucose. 109\nProtein. 2.0\nAlbumin 1.0"))
    }

    @Test fun cellCountTechnicalFieldsAreNeverDisplayedAsClinicalValues() {
        val test="FLUID CELL COUNT (ASCITIC/PLEURAL/PERICARDIAL/CSF/OTHERS)"
        for (name in listOf("Nature of Specimen(Result)",
            "Physical Appearance(Result)")) {
            assertTrue(ClinicalReportRenderer.isUninterpretedCode(row(test,name,"3")))
        }
        assertTrue(ClinicalReportRenderer.isUninterpretedCode(
            row(test,"%(Investigation)","Any Other Cells (Type)")))
        assertEquals("Lymphocytes",ClinicalChronology.parameterLabel(
            row(test,"-- Lymphocytes(Result)","40")))
        assertEquals("Polymorphs",ClinicalChronology.parameterLabel(
            row(test,"-- Polymorphs(Result)","60")))
    }

    @Test fun urineValuesAndSquamousCellsUseExplicitPdfTableNotCodeLookup() {
        val sample="""
            COMPLETE URINE EXAMINATION
            pH 5.0
            Specific Gravity 1.030
            Protein +
            Sugar:
            (Glucose) Negative
            Ketones Negative
            Nitrite Negative
            Epithelial Cells:
            (Squamous) 4-6 /HPF
        """.trimIndent()
        val parsed=PdfUrineSummaries.extract(sample).associate { it.label to it.result }
        assertEquals("5.0",parsed["pH"])
        assertEquals("1.030",parsed["Specific gravity"])
        assertEquals("Negative",parsed["Glucose"])
        assertEquals("Negative",parsed["Ketones"])
        assertEquals("4-6",parsed["Epithelial cells"])
    }

    @Test fun combinedDATIATReportExtractsTracesAndNegativeSeparately() {
        val combined="""
            Direct Coombs Test
            Traces     Control: Neg
            Indirect Coombs Test
            Result: NEGATIVE
            Comments: For further workup advice Monospecific DAT
        """.trimIndent()
        assertEquals("Traces",PdfClinicalEvidence.coombs(
            "Direct Coombs Test (GEL)",combined))
        assertEquals("Negative",PdfClinicalEvidence.coombs(
            "Indirect Coombs Test (GEL)",combined))
        assertNull(PdfClinicalEvidence.coombs("Direct Coombs Test (GEL)",
            "Indirect Coombs Test\nResult: NEGATIVE"))
        val source=ReportRef(id="one",title=
            "Direct Coombs Test (GEL), Indirect Coombs Test (GEL)",
            url="https://www.nimsts.edu.in/lab/report", labNumber="26T032807",
            collectionDateTime="2026-10-29")
        val direct=SourceMetadataReview.pdfCandidates(
            listOf(row("Direct Coombs Test (GEL)","cb1","2")),
            listOf(source))
        val indirect=SourceMetadataReview.pdfCandidates(
            listOf(row("Indirect Coombs Test (GEL)","cb2","1")),
            listOf(source))
        assertEquals(1,direct.size)
        assertEquals(1,indirect.size)
        assertEquals(direct.single().url,indirect.single().url)
    }

    @Test fun microMetadataContainsNeitherCandidateDisclaimerNorDoubleDate() {
        val pdf=ReportRef(id="pdf",title="GRAMS STAINING",
            url="https://www.nimsts.edu.in/report.pdf",labNumber="E18848",
            sampleNumber="26BCT22946",collectionDateTime="2026-10-30")
        val lines=ClinicalResultsLayout.compactMicrobiologyHeader(
            listOf(row("GRAMS STAINING","Result","No organisms")),
            listOf(pdf),null,LocalDate.parse("2026-10-30"))
        val all=lines.joinToString(" ")
        assertTrue(lines.size<=2)
        assertFalse(all.contains("candidate",true))
        assertFalse(all.contains("PDF collected",true))
        assertFalse(all.contains("report index",true))
    }
}
