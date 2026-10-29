package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class ClinicalPanelsV134Test {
    private fun result(test:String,param:String,date:String,value:String) =
        LabResult(id="synthetic",test=test,parameter=param,date=date,
            valueText=value,numericValue=value.toDoubleOrNull())

    @Test fun metabolicSortFollowsRenalLftElectrolytes() {
        val rows = listOf(
            result("ELECTROLYTES(Na,K&Cl)","Serum Sodium","2026-09-29","139"),
            result("Liver Function Tests","Serum Albumin","2026-09-29","4.6"),
            result("SERUM UREA","Serum Urea","2026-09-29","30"),
            result("CREATININE","Serum Creatinine","2026-09-29","2.25"),
            result("Liver Function Tests","Serum ALT (SGPT)","2026-09-29","10"),
            result("ELECTROLYTES(Na,K&Cl)","Serum Potassium","2026-09-29","3.8")
        )
        val names=LabPanelMatrix.build(rows,clinical=true).single()
            .parameters.map { it.title }
        assertEquals(listOf("Serum Creatinine","Serum Urea",
            "Serum ALT (SGPT)","Serum Albumin",
            "Serum Sodium","Serum Potassium"),names)
    }

    @Test fun hemogramPriorityPreservesAllDifferentialsIndicesAndSmear() {
        val rows=listOf(
            result("CBC","Platelet Count","2026-09-29","254"),
            result("CBC","RBC Count","2026-09-29","5.13"),
            result("CBC","Neutrophils","2026-09-29","60"),
            result("CBC","Hemoglobin","2026-09-29","14.7"),
            result("CBC","Total Leucocyte Count","2026-09-29","7.1"),
            result("CBC","Lymphocytes","2026-09-29","30"),
            result("ESR","ESR","2026-09-29","20"),
            result("CBC","WBC'S","2026-09-29","Normal in count")
        )
        val names=LabPanelMatrix.build(rows,clinical=true).single()
            .parameters.map { it.title }
        assertEquals(listOf("Hemoglobin","Total Leucocyte Count","Neutrophils",
            "Lymphocytes","Platelet Count","ESR","WBC'S","RBC Count"),names)
    }

    @Test fun otherClinicalPanelRequiresNumericRepeatedOnDistinctDates() {
        val rows=listOf(
            result("Vitamin B12","Result","2026-09-29","300"),
            result("TSH","Result","2026-09-29","1.2"),
            result("TSH","Result","2026-07-07","2.1"),
            result("Radiology Report","Findings","2026-09-29",
                "Long clinical narrative with no numeric observation"),
            result("Vitamin D","Result","2026-07-07","25")
        )
        val clinical=LabPanelMatrix.build(rows,clinical=true)
        assertEquals(1,clinical.size)
        val other=clinical.single()
        assertEquals(LabPanelMatrix.Panel.OTHERS,other.panel)
        assertEquals(listOf("TSH"),other.parameters.map { it.title })
        assertEquals(5,other.observationCount)
        // The comprehensive source view has not lost singleton values.
        assertEquals(4,LabPanelMatrix.build(rows).single().parameters.size)
    }

    @Test fun repeatedSameDayIsNotCalledLongitudinalTrend() {
        val rows=listOf(
            result("TSH","Result","2026-09-29","1.2"),
            result("TSH","Result","2026-09-29","1.8")
        )
        assertTrue(LabPanelMatrix.build(rows,clinical=true).isEmpty())
        assertEquals(2,LabPanelMatrix.build(rows).single().observationCount)
    }

    @Test fun sourceDateAndReportingTimeRemainDistinctFields() {
        assertEquals("22-04-2026",LabPanelMatrix.displayDate("22-Apr-2026"))
        assertEquals("30-04-2026 17:00",LabPanelMatrix.displayDateTime(
            "30-Apr-2026 17:00"))
    }
}
