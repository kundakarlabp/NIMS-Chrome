package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class ResultsClinicalV145Test {
    private fun row(t:String,p:String,v:String,date:String="2026-05-22",
        ref:String=""):LabResult=LabResult(
        id="$t|$p|$v",test=t,parameter=p,date=date,valueText=v,refRange=ref)

    @Test fun sameDayCBCThenRenalElectrolytesCalciumMagnesiumLFT() {
        val a=listOf(
            row("Liver Function Tests","ALT","19"),
            row("Magnesium","Sr. Magnesium","0.9"),
            row("Calcium","Sr. Calcium","9.2"),
            row("ELECTROLYTES(Na,K&Cl)","K","4.0"),
            row("SERUM UREA","Urea","18"),
            row("CREATININE","Creatinine","1.0"),
            row("CBC (HB, PCV,TLC, PLT, PS)","Hb","13.1"))
        assertEquals(listOf("CBC (HB, PCV,TLC, PLT, PS)","CREATININE",
            "SERUM UREA","ELECTROLYTES(Na,K&Cl)","Calcium","Magnesium",
            "Liver Function Tests"),
            ClinicalChronology.days(a).single().studies.map {it.title})
    }

    @Test fun serumElectrolytesOrderSodiumThenPotassiumThenChloride() {
        val test="ELECTROLYTES(Na,K&Cl)"
        val rows=listOf(row(test,"Cl","99"),row(test,"K","4.0"),row(test,"Na","136"))
        assertEquals(listOf("Na","K","Cl"),
            ClinicalResultsLayout.ordered(test,rows).map{it.parameter})
    }

    @Test fun noRoutineBoldHeadingsButPreserveInterpretiveReportTitles() {
        for(t in listOf("CBC+DIFF","CREATININE","SERUM UREA",
            "ELECTROLYTES(Na,K&Cl)","Liver Function Tests",
            "Lipid Profile","Glycosylated (Hb A1c)","Magnesium")) {
            assertTrue(t,ClinicalResultsLayout.omitRoutineHeading(t,listOf(row(t,"Hb","13"))))
        }
        for(t in listOf("Fan Blood Culture Final","Prothrombin Time",
            "F18 FDG Cardiac Study","Blood Grouping & Antibody Screening")) {
            assertFalse(t,ClinicalResultsLayout.omitRoutineHeading(t,listOf(row(t,"Result","NO GROWTH"))))
        }
    }

    @Test fun removeOnlyEmptyCBCOthersWithoutLosingRealSmear() {
        val cbc="CBC+DIFF"
        assertTrue(ClinicalResultsLayout.suppressCbcRow(row(cbc,"Others1","Others")))
        assertFalse(ClinicalResultsLayout.suppressCbcRow(
            row(cbc,"RBC'S:","Normocytic normochromic.")))
        assertFalse(ClinicalResultsLayout.suppressCbcRow(row(cbc,"Others1","Blasts seen")))
    }

    @Test fun ptPatientINRAndControlRatioDoNotBecomeSeconds() {
        val t="Prothrombin Time"
        val rows=listOf(row(t,"PT (Patient)","1.61",ref="0.9 - 1.1"),
            row(t,"PT (Patient)","19.3"),
            row(t,"PT (Control)","1.0"),
            row(t,"PT (Control)","11.7"))
        val labels=ClinicalResultsLayout.ordered(t,rows).map {
            CoagulationDisplay.displayLabel(it,rows)
        }
        assertEquals(listOf("PT (patient)","PT (control)",
            "INR (patient)","INR (control)"),labels)
    }

    @Test fun invalidClotTimesDoNotPresentAsMeasuredSeconds() {
        val t="Activated Partial Thromboplastin Time (APTT)"
        assertTrue(CoagulationDisplay.isAbsentValue(row(t,"APTT Patient",".")))
        assertFalse(CoagulationDisplay.isAbsentValue(row(t,"APTT Patient","CLOTTED")))
        assertEquals(listOf("aPTT (patient)"),
            CoagulationDisplay.missingAPTTComponents(t,
                listOf(row(t,"APTT Patient","."),row(t,"APTT Control","32.0"))))
    }

    @Test fun cultureMetadataDoesNotDuplicateIndexProvenance() {
        val t="Fan Blood Culture(1 set 1 bottle) Final"
        val source=ReportRef(id="pdf",title=t,labNumber="B15335",
            sampleNumber="26IBC05939",collectionDateTime="15-Apr-2026",
            reportedDate="20-Apr-2026")
        val values=listOf(row(t,"Result","CULTURE SHOWS NO GROWTH"))
        val metadata=ClinicalResultsLayout.cultureProvenance(values,listOf(source))
        assertTrue(metadata.contains("Lab B15335"))
        assertTrue(metadata.contains("Collected 15-04-2026"))
        assertTrue(metadata.contains("Reported 20-04-2026"))
        assertFalse(metadata.any{it.contains("Source-index")})
    }

    @Test fun petStudySectionsSeparateHistoryTechniqueFindingsAndImpression() {
        val text="CARDIAC PET CT SCAN Clinical Diagnosis:- Fever, ? stent abscess. "+
            "Technique:- FDG PET-CT after preparation. Findings:- Increased FDG uptake. "+
            "IMPRESSION:- Hypermetabolic uptake along LAD stent."
        val sections=ClinicalResultsLayout.radiology(text).sections
        assertEquals(listOf("Study details","Clinical diagnosis","Technique",
            "Findings","Impression"),sections.map {it.heading})
    }

    @Test fun urineCodesCannotImplySpecificGravityButPDFTextCan() {
        val t="COMPLETE URINE EXAMINATION (CHEMISTRY + MICROSCOPY)"
        assertEquals("7 (HIS code)",ClinicalResultsLayout.urineCodeDisplay(
            row(t,"Specific Gravity(Result)","7")))
        val measured=PdfUrineSummaries.extract(
            "pH 6.5\nSpecific Gravity 1.020\nKetones Negative\nProtein Trace")
        assertTrue(measured.any{it.label=="pH" && it.result=="6.5"})
        assertTrue(measured.any{it.label=="Specific gravity" && it.result=="1.020"})
        assertTrue(measured.any{it.label=="Ketones" && it.result=="Negative"})
    }

    @Test fun labelledSignedBloodBankResultIsUsedWithoutDecodingDropdownCodes() {
        val txt="Grouping and Antibody Screening\nAnti-A Neg Anti-B 4+ Rh 4+\n"+
            "2. Antibody Screening Negative\nRESULT: B POSITIVE"
        val summary=PdfClinicalSummaries.bloodGrouping(txt)
        assertEquals("B Positive",summary?.group)
        assertEquals("Negative",summary?.antibodyScreen)
        assertTrue(PdfClinicalSummaries.bloodGrouping(
            "Blood Grouping & Antibody Screening\nAnti-A 1\nAnti-B 7\nRESULT 2")==null)
    }
}
