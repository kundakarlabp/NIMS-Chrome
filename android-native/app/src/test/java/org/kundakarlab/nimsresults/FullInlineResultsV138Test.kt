package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

/** Clinical result text is readable by ordinary scroll, without PDF lookup. */
class FullInlineResultsV138Test {
    private fun row(test:String,name:String,date:String,value:String,unit:String="") =
        LabResult(id=test+name+date,test=test,parameter=name,date=date,
            valueText=value,unit=unit)

    @Test fun completeLftAndElectrolytesAreVisibleInHISOrder() {
        val lft=listOf(
            row("Liver Function Tests","Total bilirubin","2026-10-31","1.2","mg/dL"),
            row("Liver Function Tests","Direct bilirubin","2026-10-31","0.3","mg/dL"),
            row("Liver Function Tests","AST","2026-10-31","48","U/L"),
            row("Liver Function Tests","ALT","2026-10-31","62","U/L"),
            row("Liver Function Tests","ALP","2026-10-31","125","U/L"),
            row("Liver Function Tests","Albumin","2026-10-31","3.5","g/dL"),
            row("Liver Function Tests","Total protein","2026-10-31","6.3","g/dL")
        )
        val electrolytes=listOf(
            row("Electrolytes","Sodium","2026-10-30","138","mmol/L"),
            row("Electrolytes","Potassium","2026-10-30","4.1","mmol/L")
        )
        val studies=ClinicalChronology.days(lft+electrolytes).flatMap{it.studies}
        assertEquals(listOf("2026-10-31","2026-10-30"),studies.map{it.date.toString()})
        assertEquals(lft.map{it.parameter},ClinicalChronology.inlineRows(studies[0]).map{it.parameter})
        assertEquals(7,ClinicalChronology.inlineRows(studies[0]).size)
        assertEquals("1.2 mg/dL",ClinicalChronology.fullValue(lft[0]))
        assertEquals("138 mmol/L",ClinicalChronology.fullValue(electrolytes[0]))
        assertEquals("4.1 mmol/L",ClinicalChronology.fullValue(electrolytes[1]))
    }

    @Test fun fullPositiveCultureNarrativeAndAstRemainVerbatim() {
        val text="BLOOD CULTURE REPORT\nOrganism isolated: Klebsiella pneumoniae\n" +
            "Antibiogram\nMeropenem: Resistant\nAmikacin: Susceptible\n" +
            "Clinical comments: " + "long clinical detail ".repeat(40)
        val culture=listOf(
            row("Blood culture","Result","2026-10-29",text),
            row("Blood culture","Colistin","2026-10-29","Susceptible"),
            row("Blood culture","Ceftriaxone","2026-10-29","Resistant")
        )
        val study=ClinicalChronology.days(culture).single().studies.single()
        val displayed=ClinicalChronology.inlineRows(study)
        assertEquals(3,displayed.size)
        assertEquals(culture,displayed)
        assertEquals(text,ClinicalChronology.fullValue(displayed[0]))
        assertTrue(ClinicalChronology.isNarrativeRow(study,displayed[0]))
        assertTrue(ClinicalChronology.fullValue(displayed[0]).contains("\nAntibiogram\n"))
        assertTrue(ClinicalChronology.fullValue(displayed[0]).length>400)
    }

    @Test fun missingPlaceholdersAndTechnicalFieldsStayOutButUnmappedCodesAreLabelled() {
        val rows=listOf(
            row("BioFire","Combo1","2026-10-31","1"),
            row("BioFire","Processed by","2026-10-31","staff"),
            row("BioFire","Unspecified","2026-10-31","--"),
            row("BioFire","Named target","2026-10-31","Detected")
        )
        val study=ClinicalChronology.days(rows).single().studies.single()
        val visible=ClinicalChronology.inlineRows(study)
        assertEquals(listOf("Combo1","Named target"),visible.map{it.parameter})
        assertTrue(ClinicalChronology.isUnmapped(visible[0]))
        assertFalse(ClinicalChronology.isUnmapped(visible[1]))
        assertEquals("1",ClinicalChronology.fullValue(visible[0]))
    }

    @Test fun reportedDateIsPreferredWithoutCallingResultDateAReportDate() {
        val older=row("Creatinine","Creatinine","2026-10-30","1.2")
        val later=row("Culture","Result","2026-10-29","No growth").copy(
            reportedDate="2026-10-31 18:05")
        val days=ClinicalChronology.days(listOf(older,later))
        assertEquals(listOf("2026-10-31","2026-10-30"),
            days.map{it.date.toString()})
        assertTrue(days[0].studies[0].reportedIsExplicit)
        assertFalse(days[1].studies[0].reportedIsExplicit)
    }

    @Test fun unitsAreNotDuplicatedInOriginalValueText() {
        val r=row("Creatinine","Creatinine","2026-10-31","1.6 mg/dL","mg/dL")
        assertEquals("1.6 mg/dL",ClinicalChronology.fullValue(r))
    }
}
