package org.kundakarlab.nimsresults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic source-display regressions; do not commit real clinical PDFs
 * or hospital patient identifiers to the public repository.
 */
class ClinicalSourceSafetyV208Test {
    private fun row(test:String, field:String, value:String,
                    reference:String="", unit:String="") = LabResult(
        id=field+value, test=test, parameter=field, date="2026-10-12",
        valueText=value, numericValue=value.toDoubleOrNull(),
        refRange=reference, unit=unit
    )

    @Test fun unrelatedMolecularTargetsRemainSourceReviewNotClinicalFindings() {
        val foreign=listOf(
            row("CMV viral load test","Inf A Pdm H1 Target gene","2"),
            row("Parvo virus viral load test","HPV18","218.52"),
            row("Parvo virus viral load test",
                "Real Time PCR based Genotype Identification of Human Papilloma Virus","1")
        )
        for(item in foreign) {
            assertTrue(ClinicalReportRenderer.foreignMolecularTarget(item))
            assertTrue(ClinicalReportRenderer.isUninterpretedCode(item))
            val study=ClinicalChronology.days(listOf(item)).single().studies.single()
            assertTrue(ClinicalChronology.inlineRows(study).isEmpty())
            assertEquals(1,ClinicalChronology.unresolvedRows(study).size)
            val doc=FullClinicalResultsDocument.build(ClinicalBundle(
                PatientInfo(crNo="111111111111111"),listOf(item),emptyList())).single()
            assertTrue(doc.findings.all { it.unresolved })
        }
        val valid=row("CMV viral load test","CMV DNA","Target Not Detected")
        assertFalse(ClinicalReportRenderer.foreignMolecularTarget(valid))
        assertFalse(ClinicalReportRenderer.isUninterpretedCode(valid))
    }

    @Test fun codedCombinedCoagulationPreservesPtApttAndInrLabelsInPdfCsvModel() {
        val test="PT/APTT (40A+41A)"
        val rows=listOf(
            row(test,"APTT Patient","49.2",unit="sec"),
            row(test,"APTT Control","32.0",unit="sec"),
            row(test,"PT Patient","12.7",unit="sec"),
            row(test,"PT Control","11.8",unit="sec"),
            row(test,"PT Patient","1.07",reference="0.9 - 1.1"),
            row(test,"PT Control","1.00")
        )
        assertTrue(CoagulationDisplay.isPTPanel(test))
        assertTrue(CoagulationDisplay.isAPTTPanel(test))
        val labels=rows.map { CoagulationDisplay.displayLabel(it,rows) }
        assertEquals(listOf("aPTT (patient)","aPTT (control)",
            "PT (patient)","PT (control)","INR (patient)","INR (control)"),labels)
        val doc=FullClinicalResultsDocument.build(ClinicalBundle(
            PatientInfo(crNo="111111111111111"),rows,emptyList())).single()
        assertEquals(labels.toSet(),doc.findings.map { it.parameter }.toSet())
        val csv=FullClinicalResultsDocument.csv(listOf(doc))
        assertTrue(csv.contains("INR (patient)"))
        assertTrue(csv.contains("aPTT (control)"))
        assertTrue(csv.contains("PT (patient)"))
    }

    @Test fun ambiguousUrineSpecificGravityAndCbcOtherAreNotClinicalValues() {
        val invalid=listOf(
            row("COMPLETE URINE EXAMINATION","Specific Gravity (Result)","1"),
            row("CBC+DIFF","Others","100"),
            row("DDimer","D-DIMER","."))
        val study=ClinicalChronology.days(invalid)
        assertTrue(study.flatMap { it.studies }
            .flatMap(ClinicalChronology::inlineRows).isEmpty())
        val valid=row("COMPLETE URINE EXAMINATION","Specific Gravity (Result)","1.015")
        assertFalse(ClinicalReportRenderer.isUninterpretedCode(valid))
        assertFalse(ClinicalReportRenderer.isPlaceholder(valid))
    }

    @Test fun bloodCultureClinicalSummaryMayHideOnlyGenericCollectionInstructions() {
        val clinical="CULTURE SHOWS GROWTH OF ORGANISM A. " +
            "SENSITIVITY REPORT MEROPENEM. RESISTANCE REPORT CEFTRIAXONE. " +
            "INSTRUCTIONS FOR BLOOD CULTURE COLLECTION. 1) Collect bottles"
        val clean=ClinicalChronology.clinicalText(clinical)
        assertTrue(clean.contains("CULTURE SHOWS GROWTH"))
        assertTrue(clean.contains("MEROPENEM"))
        assertTrue(clean.contains("CEFTRIAXONE"))
        assertFalse(clean.contains("Collect bottles"))
    }
}
