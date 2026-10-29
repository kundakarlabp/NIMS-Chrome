package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class ClinicalChronologyV137Test {
    private fun result(test:String,parameter:String,date:String,value:String,
                       numeric:Double?=null) = LabResult(
        id=test+parameter+date,test=test,parameter=parameter,date=date,
        valueText=value,numericValue=numeric)

    @Test fun resultsAreGroupedByDateWithoutDroppingParametersOrMicrobiology() {
        val rows=listOf(
            result("CBC","Hemoglobin(Result)","2025-07-17","10.0",10.0),
            result("CBC","TLC","2025-07-17","6.6",6.6),
            result("BU/SC/SE","Serum Creatinine","2025-07-19","0.18",.18),
            result("AEROBIC CULTURE","Result","2025-07-18","NO GROWTH"),
            result("CBC","Hemoglobin(Result)","2025-07-19","10.1",10.1)
        )
        val days=ClinicalChronology.days(rows)
        assertEquals(listOf("2025-07-19","2025-07-18","2025-07-17"),
            days.map { it.date.toString() })
        assertEquals(5,days.flatMap { it.studies }.sumOf { it.rows.size })
        assertEquals(2,days.last().studies.single().visible.size)
        assertTrue(days.flatMap { it.studies }.any {
            it.title=="AEROBIC CULTURE" })
    }

    @Test fun sourceResultDateIsNotRelabeledAsReported() {
        val rows=listOf(result("CBC","Hemoglobin","2025-07-17","10",10.0))
        assertFalse(ClinicalChronology.days(rows)[0].studies[0].reportedIsExplicit)
    }

    @Test fun prothrombinControlsAndPatientReadingsStayDistinct() {
        val day="2025-07-17"
        val p=result("PT/APTT","Prothrombin Time (PT)(Patient Result)",day,"13.8",13.8)
        val c=result("PT/APTT","Prothrombin Time (PT)(Control)",day,"11.7",11.7)
        assertEquals("PT (patient)",ClinicalChronology.parameterLabel(p))
        assertEquals("PT (control)",ClinicalChronology.parameterLabel(c))
        assertEquals(2,ClinicalChronology.days(listOf(p,c))[0].studies[0].visible.size)
    }

    @Test fun clinicalLabTablesDoNotExposeMissingPlaceholdersOrAdministrativeCodes() {
        val rows=listOf(
            result("CBC","Band Forms","2025-07-17","--"),
            result("CBC","Hemoglobin","2025-07-17","10",10.0),
            result("Fluid cell count","Nature of specimen","2025-07-17","1",1.0),
            result("Fluid cell count","Glucose","2025-07-17","125",125.0),
            result("CBC","Platelet Count","2025-07-17","150",150.0)
        )
        val clinical=LabPanelMatrix.build(rows,true)
        assertEquals(listOf("Hb","Platelets"),
            clinical.single { it.panel==LabPanelMatrix.Panel.HEMOGRAM }
                .parameters.map { LabPanelMatrix.shortLabel(it.title) })
        assertTrue(clinical.flatMap { it.parameters }.none {
            it.title.equals("Nature of specimen",true) || it.title=="Band Forms" })
        assertEquals(LabPanelMatrix.Panel.OTHERS,
            LabPanelMatrix.panel(rows[3]))
    }

    @Test fun cultureInstructionsRemovedFromDisplayOnly() {
        val original="CULTURE SHOWS NO GROWTH AFTER 48 HOURS. " +
            "INSTRUCTIONS FOR BLOOD CULTURE COLLECTION. 1) COLLECT 2 SETS"
        assertEquals("CULTURE SHOWS NO GROWTH AFTER 48 HOURS.",
            ClinicalChronology.clinicalText(original))
    }
}
