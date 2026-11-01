package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class ClinicalResultsV142Test {
    private fun result(test: String, name: String, value: String,
        sent: String = "", reported: String = "", lab: String = "") =
        LabResult(id=test+name, test=test, parameter=name, date="2026-10-31",
            valueText=value, sentDate=sent, reportedDate=reported, labNumber=lab)

    @Test fun tlcFollowsHemoglobinForLeucocyteAndWbcAliases() {
        val cbc="CBC+DIFF (HB, PCV,RBC INDICES, PLT,TLC,DLC, PS)"
        val rows=listOf(result(cbc,"Neutrophils %","70"),result(cbc,"Platelets","230"),
            result(cbc,"Total Leucocyte Count","6.4"),result(cbc,"Hb","12.9"),
            result(cbc,"Lymphocytes %","19"))
        assertEquals(listOf("Hb","Total Leucocyte Count","Neutrophils %",
            "Lymphocytes %","Platelets"),ClinicalResultsLayout.ordered(cbc,rows).map{it.parameter})
        val wbc=rows[2].copy(parameter="Total White Blood Cell Count")
        assertEquals("Hb",ClinicalResultsLayout.ordered(cbc,
            listOf(wbc,rows[3])).first().parameter)
        assertEquals("Total White Blood Cell Count",ClinicalResultsLayout.ordered(cbc,
            listOf(wbc,rows[3])).last().parameter)
    }

    @Test fun routineNumericalPanelsDoNotNeedInlinePdfButtons() {
        val cbc=listOf(result("CBC+DIFF","Hb","12.1"),
            result("CBC+DIFF","RBC'S:","Normocytic normochromic"))
        assertTrue(ClinicalResultsLayout.omitRoutinePdf("CBC+DIFF",cbc))
        assertTrue(ClinicalResultsLayout.omitRoutinePdf("CREATININE",
            listOf(result("CREATININE","Creatinine","2.25"))))
        assertTrue(ClinicalResultsLayout.omitRoutinePdf("Liver Function Tests",
            listOf(result("Liver Function Tests","Bilirubin (total)","0.8"))))
        assertTrue(ClinicalResultsLayout.omitRoutinePdf("SERUM CALCIUM",
            listOf(result("SERUM CALCIUM","Calcium","8.9"))))
        assertFalse(ClinicalResultsLayout.omitRoutinePdf("Fan Blood Culture Final",
            listOf(result("Fan Blood Culture Final","Result","NO GROWTH"))))
        assertFalse(ClinicalResultsLayout.omitRoutinePdf("Color Doppler",
            listOf(result("Color Doppler","Result","NO THROMBOSIS SEEN"))))
    }

    @Test fun urineCodedDropdownsVisibleButNeverMistakenForPhysicalValues() {
        val test="COMPLETE URINE EXAMINATION (CHEMISTRY + MICROSCOPY)"
        val ph=result(test,"pH(Result)","1")
        val sg=result(test,"Specific Gravity(Result)","7")
        val ketones=result(test,"Ketones(Result)","1")
        assertTrue(ClinicalResultsLayout.urineChemistryCode(ph))
        assertTrue(ClinicalResultsLayout.urineChemistryCode(sg))
        assertTrue(ClinicalResultsLayout.urineChemistryCode(ketones))
        assertEquals("pH",ClinicalResultsLayout.urineChemistryLabel(ph))
        assertEquals("Specific gravity",ClinicalResultsLayout.urineChemistryLabel(sg))
        assertEquals("1 (HIS code)",ClinicalResultsLayout.urineCodeDisplay(ph))
        assertEquals("7 (HIS code)",ClinicalResultsLayout.urineCodeDisplay(sg))
        assertFalse(ClinicalResultsLayout.urineChemistryCode(result(test,"pH(Result)","6.5")))
        assertFalse(ClinicalResultsLayout.urineChemistryCode(result(test,
            "Specific Gravity(Result)","1.020")))
        assertFalse(ClinicalResultsLayout.urineChemistryCode(result(test,
            "Ketones(Result)","Negative")))
        assertFalse(ClinicalResultsLayout.urineChemistryCode(result(test,
            "Ketones(Result)","-1")))
    }

    @Test fun cultureProvenancePreservesSentAndAccessionWithoutDateInference() {
        val culture="Fan Blood Culture(1 set 1 bottle) Final"
        val r=result(culture,"Result","B15335 CULTURE REPORT SHOWS GROWTH",
            sent="2026-04-23 10:45")
        val meta=ClinicalResultsLayout.cultureProvenance(listOf(r))
        assertEquals(listOf("Lab B15335","Sent 23-04-2026 10:45"),meta)
    }

    @Test fun distinctCollectionDateNeverCalledSentDate() {
        val test="Fan Blood Culture(1 set 1 bottle) Final"
        val r=result(test,"Result","B15335 CULTURE REPORT CULTURE SHOWS GROWTH")
        val source=ReportRef(id="pdf",title=test,date="2026-04-23",
            labNumber="B15335",collectionDateTime="2026-04-22 09:00")
        val meta=ClinicalResultsLayout.cultureProvenance(listOf(r),listOf(source))
        assertTrue(meta.contains("Collected 22-04-2026 09:00"))
        assertFalse(meta.any { it.startsWith("Sent") })
    }
}
