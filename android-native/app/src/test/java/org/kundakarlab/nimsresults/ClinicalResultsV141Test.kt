package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class ClinicalResultsV141Test {
    private fun row(test: String, name: String, value: String, date: String = "2026-05-07") =
        LabResult(id=name+date, test=test, parameter=name, valueText=value, date=date)

    private fun pdf(date: String, test: String, lab: String) =
        ReportRef(id=lab, title=test, date=date, collectionDateTime=date,
            labNumber=lab,url="https://nimsts.edu.in/report/"+lab+".pdf",
            sourceTag="nims_html_index")

    @Test fun pdfSelectionExcludesOtherDatesEvenForSameNamedCbc() {
        val test="CBC (HB, PCV,TLC, PLT, PS)"
        val source=listOf(pdf("2026-04-23",test,"26CP165365"),
            pdf("2026-04-26",test,"26CP169832"),
            pdf("2026-05-07",test,"26CP188246"))
        val chosen=SourceMetadataReview.pdfCandidates(listOf(row(test,"Hb","13.1")),source)
        assertEquals(listOf("26CP188246"),chosen.map{it.labNumber})
    }

    @Test fun exactCultureAccessionOverridesCollectionDateDifference() {
        val test="Fan Blood Culture(1 set 1 bottle) Final"
        val source=listOf(pdf("2026-04-15",test,"B14327"),
            pdf("2026-04-22",test,"B15335"))
        val culture=listOf(row(test,"Result",
            "B15335 CULTURE REPORT CULTURE SHOWS GROWTH OF BURKHOLDERIA PSEUDOMALLEI",
            "2026-04-28"))
        val chosen=SourceMetadataReview.pdfCandidates(culture,source)
        assertEquals(listOf("B15335"),chosen.map{it.labNumber})
    }

    @Test fun mismatchedExplicitCultureAccessionNeverFallsBackToNearDate() {
        val test="Fan Blood Culture(1 set 1 bottle) Final"
        val chosen=SourceMetadataReview.pdfCandidates(listOf(row(test,"Result",
            "B15335 CULTURE REPORT CULTURE SHOWS GROWTH",
            "2026-04-22")),listOf(pdf("2026-04-22",test,"B14327")))
        assertTrue(chosen.isEmpty())
    }

    @Test fun cultureSeparatesSetBottleStageAndCollectionInstructions() {
        val culture=ClinicalResultsLayout.culture("Fan Blood Culture(2 set 1 bottle) 48 hrs",
            "B15337 CULTURE REPORT BLOOD CULTURE SHOWS NO GROWTH AFTER 48 HOURS. "+
            "FINAL REPORT AFTER 5 DAYS. INSTRUCTIONS FOR BLOOD CULTURE COLLECTION. "+
            "COLLECT TWO SETS.")
        assertEquals("B15337",culture.accession)
        assertEquals("2",culture.set)
        assertEquals("1",culture.bottle)
        assertEquals("48-hour interim",culture.stage)
        assertFalse(culture.result.contains("INSTRUCTIONS"))
        assertTrue(culture.guidance.contains("INSTRUCTIONS"))
        assertTrue(culture.result.contains("NO GROWTH"))
    }

    @Test fun positiveCultureTreatmentAdviceIsOutsideFinding() {
        val display=ClinicalResultsLayout.culture(
            "Fan Blood Culture(1 set 1 bottle) Final",
            "B15335 CULTURE REPORT CULTURE SHOWS GROWTH OF BURKHOLDERIA PSEUDOMALLEI. "+
                "TREATMENT: Initial intensive phase etc.")
        assertTrue(display.result.contains("BURKHOLDERIA"))
        assertFalse(display.result.contains("TREATMENT"))
        assertTrue(display.guidance.contains("TREATMENT:"))
    }

    @Test fun dopplerImpressionAndReportingDoctorSeparated() {
        val parsed=ClinicalResultsLayout.radiology(
            "BILATERAL LOWER LIMB ARTERIAL AND VENOUS SYSTEM " +
            "FINDINGS: No thrombosis. IMPRESSION Negative study for DVT. " +
            "DR KAAVYA(SR)")
        assertTrue(parsed.sections.any { it.heading=="Findings" && it.content.contains("No thrombosis") })
        assertTrue(parsed.sections.any { it.heading=="Impression" && it.content.contains("Negative study") })
        assertEquals("DR KAAVYA(SR)",parsed.reporter)
        assertFalse(parsed.sections.last().content.contains("DR KAAVYA"))
    }

    @Test fun lftAndCbcOrdersAreClinicallyUsefulAndPreserveComments() {
        val lft="Liver Function Tests"
        val lfts=listOf(row(lft,"Albumin","3.3"),row(lft,"ALT","164"),
            row(lft,"Bilirubin (direct)","0.27"),row(lft,"ALP","543"),
            row(lft,"Bilirubin (total)","0.8"),row(lft,"AST","48"),
            row(lft,"Total protein","6.1"))
        assertEquals(listOf("Bilirubin (total)","Bilirubin (direct)","AST","ALT","ALP",
            "Total protein","Albumin"),ClinicalResultsLayout.ordered(lft,lfts).map{it.parameter})
        val cbc="CBC+DIFF"
        val cbcRows=listOf(row(cbc,"Platelets:","Adequate."),
            row(cbc,"RBC'S:","Normocytic normochromic."),
            row(cbc,"Lymphocytes %","30"),row(cbc,"Hb","14.7"),
            row(cbc,"Platelets","254"),row(cbc,"TLC","7.1"),
            row(cbc,"Neutrophils %","60"))
        assertEquals(listOf("Hb","TLC","Neutrophils %","Lymphocytes %",
            "Platelets","RBC'S:","Platelets:"),
            ClinicalResultsLayout.ordered(cbc,cbcRows).map{it.parameter})
    }

    @Test fun singleCreatinineDoesNotRequireRepeatedHeading() {
        assertTrue(ClinicalResultsLayout.omitRedundantHeading(
            "CREATININE",listOf(row("CREATININE","Creatinine","2.25"))))
        assertTrue(ClinicalResultsLayout.omitRedundantHeading(
            "SERUM UREA",listOf(row("SERUM UREA","Urea","19"))))
        assertFalse(ClinicalResultsLayout.omitRedundantHeading(
            "Liver Function Tests",listOf(row("Liver Function Tests","ALT","37"))))
    }

    @Test fun coagulationOrderDoesNotConfusePatientControlInr() {
        val test="Prothrombin Time"
        val studies=listOf(row(test,"INR","1.10"),
            row(test,"Prothrombin Time (PT)(Control)","11.7"),
            row(test,"Prothrombin Time (PT)(Patient Result)","13.2"))
        assertEquals(listOf("PT (patient)","PT (control)","INR"),
            ClinicalResultsLayout.ordered(test,studies).map{ClinicalChronology.parameterLabel(it)})
    }
}
