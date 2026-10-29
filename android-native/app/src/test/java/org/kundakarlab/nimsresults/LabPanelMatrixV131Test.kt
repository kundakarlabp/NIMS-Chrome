package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class LabPanelMatrixV131Test {
    private fun result(test:String,param:String,date:String,value:String,
        unit:String="")=LabResult(id="synthetic",test=test,parameter=param,
        date=date,valueText=value,numericValue=value.toDoubleOrNull(),unit=unit)

    @Test fun consolidatesMetabolicCreatinineUreaElectrolytesAndLft() {
        val rows=listOf(
            result("CREATININE","Serum Creatinine","2026-09-29","2.25"),
            result("SERUM UREA","Serum Urea","2026-09-29","30"),
            result("ELECTROLYTES(Na,K&Cl)","Serum Sodium","2026-09-29","139"),
            result("Liver Function Tests","Serum ALT (SGPT)","2026-09-29","10"),
            result("CREATININE","Serum Creatinine","2026-07-07","0.75")
        )
        val tables=LabPanelMatrix.build(rows)
        assertEquals(1,tables.size)
        val t=tables.single()
        assertEquals(LabPanelMatrix.Panel.METABOLIC,t.panel)
        assertEquals(5,t.observationCount)
        assertEquals(listOf("2026-09-29","2026-07-07"),t.dates)
        assertEquals(4,t.parameters.size)
        assertEquals(2,t.parameters.first { it.title=="Serum Creatinine" }
            .byDate.size)
    }

    @Test fun unifiesCbcCbpAndHemogramAsOnePanel() {
        val rows=listOf(
            result("CBC+DIFF (HB, PCV,RBC INDICES)","Hemoglobin","2026-09-29","14.7"),
            result("CBP","Hemoglobin","2026-07-07","13.2"),
            result("Complete Blood Count","Platelet Count","2026-09-29","254")
        )
        val panel=LabPanelMatrix.build(rows).single()
        assertEquals(LabPanelMatrix.Panel.HEMOGRAM,panel.panel)
        assertEquals(2,panel.parameters.size)
        assertEquals(2,panel.parameters.first{it.title=="Hemoglobin"}.byDate.size)
    }

    @Test fun otherLaboratoryTestsShareThirdTableNotOneCardPerTest() {
        val p=LabPanelMatrix.build(listOf(
            result("Lipid Profile","LDL cholesterol","2026-09-29","90"),
            result("Vitamin B12","Result","2026-09-29","300"),
            result("TSH","Result","2026-09-29","2.0")
        )).single()
        assertEquals(LabPanelMatrix.Panel.OTHERS,p.panel)
        assertEquals(3,p.parameters.size)
        assertTrue(p.parameters.any{it.title=="TSH"})
        assertTrue(p.parameters.any{it.title=="Vitamin B12"})
    }

    @Test fun datesUseFullDayMonthYearAndRetainSourceOnBadFormat() {
        assertEquals("29-09-2026",LabPanelMatrix.displayDate("2026-09-29"))
        assertEquals("29-09-2026",LabPanelMatrix.displayDate("29-Sep-2026"))
        assertEquals("29-09-2026",LabPanelMatrix.displayDate("29-09-2026"))
        assertEquals("29-09-2026",LabPanelMatrix.displayDate("29/09/2026"))
        assertEquals("07-07-2026",LabPanelMatrix.displayDate("2026-07-07"))
        assertEquals("unknown-date",LabPanelMatrix.displayDate("unknown-date"))
    }

    @Test fun sameDayRepeatedResultsNeverOverwriteOrBecomeFalseAverage() {
        val rows=listOf(
            result("CREATININE","Serum Creatinine","2026-09-29","2.25"),
            result("CREATININE","Serum Creatinine","2026-09-29","1.98"))
        val cell=LabPanelMatrix.build(rows).single().parameters.single()
            .byDate["2026-09-29"].orEmpty()
        assertEquals(2,cell.size)
        assertEquals(listOf("2.25","1.98"),cell.map{it.valueText})
    }

    @Test fun presentationNeverChangesRawObservationsOrMergesDifferentUnits() {
        val a=result("SERUM UREA","Serum Urea","2026-09-29","30","mg/dL")
        val b=result("SERUM UREA","Serum Urea","2026-07-07","5","mmol/L")
        val panels=LabPanelMatrix.build(listOf(a,b))
        assertEquals(2,panels.single().parameters.size)
        assertEquals("30",a.valueText)
        assertEquals("5",b.valueText)
    }

    @Test fun noSourceObservationsDroppedIncludingUndatedEntries() {
        val rows=listOf(
            result("TSH","Result","unknown-date","2.0"),
            result("CBC","Hemoglobin","2026-09-29","13.1"),
            result("SERUM UREA","Serum Urea","2026-09-29","30"))
        val panels=LabPanelMatrix.build(rows)
        assertEquals(rows.size,panels.sumOf{it.observationCount})
        assertEquals(1,panels.sumOf{it.undatedCount})
    }
}
