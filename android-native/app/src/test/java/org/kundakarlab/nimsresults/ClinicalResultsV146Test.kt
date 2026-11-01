package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class ClinicalResultsV146Test {
    private fun row(test:String,param:String,value:String,
        date:String="2026-03-17",unit:String="",ref:String="")=
        LabResult(id=test+param,test=test,parameter=param,date=date,
            valueText=value,unit=unit,refRange=ref)

    @Test fun crpLatexLiteralThresholdIsCompactAndNotSourcePdfDependent() {
        val report="Serum C-reactive protein is <6mg/1000ml-Negative (Latex Agglutination)( <6 mg/1000ml- Negative )"
        val result=ClinicalReadableSummaries.crp(
            "C REACTIVE PROTEIN (CRP) AGGLUTINATION",report)
        assertEquals("<6 mg/1000ml · Negative",result?.finding)
        assertTrue(ClinicalResultsLayout.omitRoutineHeading(
            "C REACTIVE PROTEIN (CRP) AGGLUTINATION",
            listOf(row("C REACTIVE PROTEIN (CRP) AGGLUTINATION","Result",report))))
        assertTrue(ClinicalResultsLayout.omitRoutinePdf(
            "C REACTIVE PROTEIN (CRP) AGGLUTINATION",
            listOf(row("C REACTIVE PROTEIN (CRP) AGGLUTINATION","Result",report))))
    }

    @Test fun literalCUEPdfMeasurementsDoNotComeFromUnverifiedCodeDictionary() {
        val source="pH 6.0 4.6-8.0\nSpecific Gravity 1.030 1.003-1.035\n"+
            "Protein Trace Negative\nSugar: (Glucose) ++++ Negative\nKetones Large Negative"
        val results=PdfUrineSummaries.extract(source).associate { it.label to it.result }
        assertEquals("6.0",results["pH"])
        assertEquals("1.030",results["Specific gravity"])
        assertEquals("Trace",results["Protein"])
        assertEquals("++++",results["Glucose"])
        assertEquals("Large",results["Ketones"])
        val test="COMPLETE URINE EXAMINATION (CHEMISTRY + MICROSCOPY)"
        assertEquals("7 (HIS code)",ClinicalResultsLayout.urineCodeDisplay(
            row(test,"Specific Gravity(Result)","7")))
        assertFalse(results.values.contains("7"))
    }

    @Test fun urineCultureSmearAndCultureAreSeparateAndAdviceNotInCulture() {
        val raw="CULTURE REPORT GRAMS SMEAR SHOWS GRAM NEGATIVE BACILLI "+
            "GRAM POSITIVE COCCI ALONG WITH <5 PUS CELLS / HPF "+
            "CULTURE SHOWS NO SIGNIFICANT BACTERIURIA. COLONY COUNT LESS THAN100 CFU/ml. "+
            "PLEASE REPEAT IF CLINICAL CONDITION WARRANTS."
        val sections=ClinicalReadableSummaries.urineCulture(raw)
        assertEquals(listOf("Gram smear","Culture","Laboratory note"),
            sections.map{it.heading})
        assertFalse(sections[1].content.contains("PLEASE REPEAT"))
    }

    @Test fun fluidChemistryLessThanAndRangesRemainTruthful() {
        val fluid="CSF/Pleural/Ascitic/Syno fld"
        assertEquals("<1.0",ClinicalReadableSummaries.compactValue(
            row(fluid,"Albumin","less than 1.0")))
        assertEquals("<2.0",ClinicalReadableSummaries.compactValue(
            row(fluid,"Protein","less than 2.0")))
        assertEquals("",ClinicalReadableSummaries.usefulReference("g/dl - g/dl"))
        assertEquals("0.5 - 1.5 mg/dL",
            ClinicalReadableSummaries.usefulReference("0.5 - 1.5 mg/dL"))
    }

    @Test fun hivMethodResultAndCommentNotMixed() {
        val raw="HIV (Combo - Ag/Ab ELFA) - Non Reactive Comment: "+
            "The sample is non-reactive for HIV-1/2 antibodies and HIV-1 p24 antigen."
        val parsed=ClinicalReadableSummaries.hiv("HIV - ELFA",raw)
        assertEquals("HIV Ag/Ab (ELFA) · Non-reactive",parsed?.finding)
        assertTrue(parsed?.comment?.startsWith("The sample") == true)
    }

    @Test fun coombsNeedsNamedOriginalReportNotNumericHisCode() {
        assertEquals("Negative",PdfClinicalSummaries.coombs(
            "Indirect Coombs Test (GEL)",
            "Indirect Coombs Test (GEL): Negative"))
        assertNull(PdfClinicalSummaries.coombs("Indirect Coombs Test (GEL)",
            "Result: 1"))
        assertNull(PdfClinicalSummaries.coombs("Direct Coombs Test (GEL)",
            "Indirect Coombs Test (GEL): Negative"))
    }

    @Test fun stainSynonymsDoNotCrossLinkOtherCultureTypes() {
        val test="FUNGAL FLUORESCENCE STAINING (CALCOFLUOR)"
        val original=listOf(row(test,"FUNGAL FLUORESCENCE STAINING",
            "CALCOFLUOR WHITE MOUNT SHOWS NO FUNGAL ELEMENTS."))
        val pdf=ReportRef(id="f",title="FUNGAL FLUORESCENCE STAINING",
            url="https://hospital.example/path.pdf",labNumber="F12345",
            collectionDateTime="2026-03-17")
        assertEquals(1,SourceMetadataReview.pdfCandidates(original,listOf(pdf)).size)
        val culture=pdf.copy(id="u",title="Urine Aerobic Culture",labNumber="U12345")
        assertTrue(SourceMetadataReview.pdfCandidates(original,listOf(culture)).isEmpty())
    }

    @Test fun microbiologyStainProvenanceNamesUnverifiedCandidateExplicitly() {
        val test="GRAMS STAINING"
        val row=row(test,"Result","No microorganisms seen")
        val pdf=ReportRef(id="g",title=test,labNumber="B15331",
            collectionDateTime="2026-03-17",sampleNumber="SPEC123")
        val labels=ClinicalResultsLayout.microbiologyProvenance(listOf(row),
            listOf(pdf),null,false)
        assertTrue(labels.any{it=="Possible lab B15331"})
        assertTrue(labels.any{it=="Possible sample SPEC123"})
        assertTrue(labels.any{it.startsWith("Possible collected")})
        assertFalse(labels.any{it.startsWith("Reported")})
        val verified=PdfDocumentHeader(crNo="331012600350500",labNumber="B15331",
            sampleNumber="SPEC123",reportedDate="2026-03-18")
        val confirmed=ClinicalResultsLayout.microbiologyProvenance(
            listOf(row),listOf(pdf),verified,false)
        assertTrue(confirmed.contains("Lab B15331"))
        assertTrue(confirmed.any{it.startsWith("Reported")})
    }

    @Test fun bloodCultureDoesNotShowSampleNumber() {
        val t="Fan Blood Culture(1 set 2 bottle) 48 hrs"
        val lab=listOf(row(t,"Result","CULTURE SHOWS NO GROWTH"))
        val ref=ReportRef(id="pdf",title=t,labNumber="B15336",
            sampleNumber="26IBC06383",collectionDateTime="2026-04-22")
        val parts=ClinicalResultsLayout.microbiologyProvenance(lab,listOf(ref),
            null,bloodCulture=true)
        assertFalse(parts.any{it.startsWith("Sample ") ||
            it.startsWith("Possible sample ")})
    }
}
