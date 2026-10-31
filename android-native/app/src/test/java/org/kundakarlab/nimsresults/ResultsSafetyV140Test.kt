package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class ResultsSafetyV140Test {
    private fun r(test: String, parameter: String, value: String) =
        LabResult(id=parameter, test=test, parameter=parameter,
            date="2026-10-31", valueText=value)

    @Test fun urineRoutineDoesNotDisplayWidgetCodesAsClinicalReadings() {
        val test="COMPLETE URINE EXAMINATION (CHEMISTRY + MICROSCOPY)"
        val source=listOf(
            r(test,"/ HPF(Investigation)","1"),
            r(test,"/ HPF(Investigation)","-1"),
            r(test,"/ HPF(Result)","4-6"),
            r(test,"/ LPF(Investigation)","-1"),
            r(test,"Negative(Result)","-1"),
            r(test,"Nitrite(Result)","-1"),
            r(test,"Protein(Result)","3"),
            r(test,"Specific Gravity(Result)","7"),
            r(test,"pH(Result)","1"),
            r(test,"Pus Cells(Result)","2-3"),
            r(test,"Red Cells(Result)","Nil"),
            r(test,"Yeast Cells(Result)","Nil")
        )
        val study=ClinicalChronology.days(source).single().studies.single()
        assertEquals(listOf("Pus Cells(Result)","Red Cells(Result)",
            "Yeast Cells(Result)"),ClinicalChronology.inlineRows(study).map{it.parameter})
        val codeNames=ClinicalChronology.unresolvedRows(study).map{it.parameter}
        assertTrue(codeNames.contains("Protein(Result)"))
        assertTrue(codeNames.contains("Specific Gravity(Result)"))
        assertTrue(codeNames.contains("pH(Result)"))
        assertTrue(codeNames.contains("/ HPF(Result)"))
        assertTrue(source.all { it in study.rows }) // source never destroyed
    }

    @Test fun quantitativeUrineResultsRemainVisibleWhenPlausibleAndExplicit() {
        val test="COMPLETE URINE EXAMINATION (CHEMISTRY + MICROSCOPY)"
        val source=listOf(
            r(test,"pH(Result)","7.0"),
            r(test,"Specific Gravity(Result)","1.015"),
            r(test,"RBC /HPF","4-6"),
            r(test,"WBC /HPF","12-15"))
        val study=ClinicalChronology.days(source).single().studies.single()
        assertEquals(source, ClinicalChronology.inlineRows(study))
        assertTrue(ClinicalChronology.unresolvedRows(study).isEmpty())
    }

    @Test fun fluidCategoricalCodesAreNotMistakenForSpecimenLabels() {
        val test="CSF/Pleural/Ascitic/Syno fld"
        val source=listOf(r(test,"Nature Of Specimen","3"),
            r(test,"Physical Appearance","2"),
            r(test,"Protein.","2.0"),
            r(test,"Glucose.","109"))
        val study=ClinicalChronology.days(source).single().studies.single()
        assertEquals(listOf("Protein.","Glucose."),
            ClinicalChronology.inlineRows(study).map{it.parameter})
        assertEquals(2,ClinicalChronology.unresolvedRows(study).size)
    }

    @Test fun ambiguousQualitativeCodesRemainReviewableWithoutDecoding() {
        val row=r("BioFire FilmArray","Combo1","1")
        val study=ClinicalChronology.days(listOf(row)).single().studies.single()
        assertTrue(ClinicalChronology.inlineRows(study).isEmpty())
        assertEquals(row,ClinicalChronology.unresolvedRows(study).single())
    }
}
