package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class IdFocusIndexV131Test {
    private fun row(test:String,parameter:String,date:String,value:String,
                    sample:String="",stage:String="")=
        LabResult(id="synthetic",test=test,parameter=parameter,date=date,
            valueText=value,numericValue=value.toDoubleOrNull(),sampleNumber=sample,
            reportStage=stage)

    @Test fun tracksCrpAndHscrpSeparately() {
        val indexed=IdFocusIndex.series(listOf(
            row("CRP","Result","2026-09-29","50"),
            row("hsCRP","Result","2026-09-29","3"),
            row("ESR","Result","2026-09-29","30"),
            row("Procalcitonin","Result","2026-09-29","1.2")))
            .toMap()
        assertEquals("50",indexed["CRP"]?.single()?.valueText)
        assertEquals("3",indexed["hsCRP"]?.single()?.valueText)
        assertEquals("30",indexed["ESR"]?.single()?.valueText)
        assertEquals("1.2",indexed["Procalcitonin"]?.single()?.valueText)
    }

    @Test fun ignoresNumbersInsideMicrobiologyAndUnrelatedResultFields() {
        val groups=IdFocusIndex.series(listOf(
            row("AEROBIC CULTURE","CRP","2026-09-29","12"),
            row("CBC","Result","2026-09-29","7"),
            row("Vitamin B12","Result","2026-09-29","120"),
            row("CBC","Total Leucocyte Count","2026-09-29","7.1")))
            .toMap()
        assertEquals(0,groups["CRP"]?.size)
        assertEquals(1,groups["WBC / TLC"]?.size)
        assertEquals("7.1",groups["WBC / TLC"]?.single()?.valueText)
    }

    @Test fun separateCultureSpecimensAndReportStagesAreNotCollapsed() {
        val episodes=IdFocusIndex.cultureEpisodes(listOf(
            row("Fan Blood Culture","Report","2026-04-20","NEGATIVE",
                sample="A",stage="PRELIM"),
            row("Fan Blood Culture","Report","2026-04-20","NEGATIVE",
                sample="A",stage="FINAL"),
            row("Fan Blood Culture","Report","2026-04-20","NEGATIVE",
                sample="B",stage="FINAL")))
        assertEquals(3,episodes.size)
        assertEquals(3,episodes.map{it.key}.distinct().size)
    }
}
