package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class IdFocusIndexV130Test {
    private fun observation(test: String, param: String, date: String,
        value: String, numeric: Double? = value.toDoubleOrNull(),
        sample: String = "", stage: String = "") =
        LabResult("synthetic",test,param,date,value,
            numericValue=numeric,sampleNumber=sample,reportStage=stage)

    @Test fun biomarkersUseActualParameterAndGenericResultWithExactTestName() {
        val rows=listOf(
            observation("CRP","Result","2026-10-29","42"),
            observation("C-REACTIVE PROTEIN","C-Reactive Protein","2026-10-28","8"),
            observation("ESR","Result","2026-10-29","40"),
            observation("Procalcitonin","Result","2026-10-29","2.2"),
            observation("CBC","Total Leucocyte Count","2026-10-29","7.2"),
            observation("CBC","Absolute Neutrophil Count","2026-10-29","4100")
        )
        val indexed=IdFocusIndex.series(rows).toMap()
        assertEquals(2,indexed.getValue("CRP").size)
        assertEquals(1,indexed.getValue("ESR").size)
        assertEquals(1,indexed.getValue("Procalcitonin").size)
        assertEquals(1,indexed.getValue("WBC / TLC").size)
        assertEquals(1,indexed.getValue("ANC").size)
        assertTrue(indexed.getValue("hsCRP").isEmpty())
        assertEquals("2026-10-29",indexed.getValue("CRP").first().date)
    }

    @Test fun highSensitivityCrpIsNotClassifiedAsStandardCrp() {
        val indexed=IdFocusIndex.series(listOf(
            observation("hsCRP","Result","2026-10-29","0.7"),
            observation("CRP","hsCRP","2026-10-28","1.1")
        )).toMap()
        assertTrue(indexed.getValue("CRP").isEmpty())
        assertEquals(2,indexed.getValue("hsCRP").size)
    }

    @Test fun narrativeAndQualitativeValuesAreNeverUsedAsNumericMarkers() {
        val indexed=IdFocusIndex.series(listOf(
            observation("CRP","Result","2026-10-29","NOT DETECTED",null),
            observation("AEROBIC CULTURE","CRP","2026-10-28","12"),
            observation("CRP","Result","2026-10-28","-1",null),
            observation("Other chemistry","Result","2026-10-29","100")
        )).toMap()
        assertEquals(0,indexed.values.sumOf { it.size })
    }

    @Test fun repeatedSameDayCultureSamplesAndStagesRemainSeparate() {
        val samples=listOf(
            observation("FAN BLOOD CULTURE","Result","2026-10-29","NO GROWTH",
                null,sample="A",stage="PRELIM"),
            observation("FAN BLOOD CULTURE","Result","2026-10-29","NO GROWTH",
                null,sample="B",stage="PRELIM"),
            observation("FAN BLOOD CULTURE","Result","2026-10-29","NO GROWTH",
                null,sample="A",stage="FINAL")
        )
        val episodes=IdFocusIndex.cultureEpisodes(samples)
        assertEquals(3,episodes.size)
        assertEquals(3,episodes.map { it.key }.toSet().size)
    }

    @Test fun cultureChronologySortsNewestFirst() {
        val episodes=IdFocusIndex.cultureEpisodes(listOf(
            observation("BLOOD CULTURE","Result","2026-04-20","X",null),
            observation("URINE CULTURE","Result","2026-10-20","X",null)
        ))
        assertEquals("2026-10-20",episodes.first().date)
    }
}
