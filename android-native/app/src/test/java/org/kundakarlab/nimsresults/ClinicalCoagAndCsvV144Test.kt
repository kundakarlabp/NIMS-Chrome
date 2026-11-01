package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class ClinicalCoagAndCsvV144Test {
    private fun row(test:String,name:String,value:String,date:String="2026-05-22")=
        LabResult(id=test+name,test=test,parameter=name,valueText=value,date=date)

    @Test fun prothrombinPatientControlAndInrRemainDistinctAndOrdered() {
        val test="Prothrombin Time"
        val rows=listOf(row(test,"INR","1.10"),row(test,"Normal Control Time","11.7"),
            row(test,"Patient Time","13.2"))
        val ordered=ClinicalResultsLayout.ordered(test,rows)
        assertEquals(listOf("PT (patient)","PT (control)","INR"),
            ordered.map(ClinicalChronology::parameterLabel))
        assertEquals(listOf("13.2","11.7","1.10"),ordered.map{it.valueText})
        assertTrue(CoagulationDisplay.missingPTComponents(test,ordered).isEmpty())
    }

    @Test fun standardNimsPtControlPatientFullLabelsAndAbbreviationsRecognized() {
        val t="PROTHROMBIN TIME (PT/INR)"
        assertEquals("PT (patient)",ClinicalChronology.parameterLabel(
            row(t,"Prothrombin Time (PT)(Patient Result)","14.5")))
        assertEquals("PT (control)",ClinicalChronology.parameterLabel(
            row(t,"Prothrombin Time (PT)(Control)","11.7")))
        assertEquals("INR",ClinicalChronology.parameterLabel(
            row(t,"International Normalized Ratio","1.2")))
        assertEquals("PT",ClinicalChronology.parameterLabel(row(t,"PT","14.5")))
    }

    @Test fun missingPtComponentIsSignalledNotFabricatedAndPdfRemainsAnOption() {
        val test="Prothrombin Time"
        val list=listOf(row(test,"INR","1.1"),row(test,"Patient Time","12.6"))
        assertEquals(listOf("PT (control)"),
            CoagulationDisplay.missingPTComponents(test,list))
    }

    @Test fun doNotDropConflictingRepeatedINRReadings() {
        val test="Prothrombin Time"
        val values=listOf(row(test,"INR","1.0"),row(test,"INR","1.10"))
        assertTrue(CoagulationDisplay.divergentINR(values))
        assertEquals(2,ClinicalResultsLayout.ordered(test,values).size)
    }

    @Test fun apttControlNotMislabeledAsProthrombinControl() {
        val t="Activated Partial Thromboplastin Time (APTT)"
        val a=listOf(row(t,"Control","29.0"),row(t,"Patient","32.0"))
        assertEquals(listOf("aPTT (patient)","aPTT (control)"),
            ClinicalResultsLayout.ordered(t,a).map(ClinicalChronology::parameterLabel))
    }

    @Test fun exportOnlySelectedResultsAndQuoteCommaNewline() {
        val test="Liver Function Tests"
        val cbc="CBC"
        val alt=row(test,"ALT","37")
        val rbcs=row(cbc,"RBC'S:","Normocytic, normochromic.\nNo abnormal cells")
        val options=SelectedResultsCsv.options(listOf(alt,rbcs))
        assertEquals(2,options.size)
        val only=options.single { it.title.contains("RBC") }
        val csv=SelectedResultsCsv.render(listOf(alt,rbcs),setOf(only.id))
        assertTrue(csv.contains("Normocytic, normochromic.\nNo abnormal cells"))
        assertFalse(csv.contains("\"37\""))
        assertTrue(csv.contains("\"Result date\",\"Investigation\""))
        assertFalse(csv.contains("patient.crNo"))
    }

    @Test fun selectedCSVPreservesExactSuppliedTimestampAndUnits() {
        val test="Prothrombin Time"
        val row=row(test,"PT (control)","11.7").copy(
            unit="s",reportedDate="2026-05-22 16:35:50.0",
            refRange="11 - 13",collectionDateTime="2026-05-22 09:00")
        val id=SelectedResultsCsv.options(listOf(row)).single().id
        val csv=SelectedResultsCsv.render(listOf(row),setOf(id))
        assertTrue(csv.contains("2026-05-22 16:35:50.0"))
        assertTrue(csv.contains("\"PT (control)\""))
        assertTrue(csv.contains("\"11 - 13\""))
        assertTrue(csv.contains("\"s\""))
    }
    @Test fun standaloneINRTestKeepsPtPatientAndControlIfSupplied() {
        val t="INR"
        val source=listOf(row(t,"INR","1.20"),row(t,"PT Control","12.3"),
            row(t,"PT Patient","15.6"))
        val ordered=ClinicalResultsLayout.ordered(t,source)
        assertEquals(listOf("PT (patient)","PT (control)","INR"),
            ordered.map(ClinicalChronology::parameterLabel))
        assertTrue(CoagulationDisplay.missingPTComponents(t,ordered).isEmpty())
    }

}
