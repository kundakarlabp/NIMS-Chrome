package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class StagedBulkV120Test {
    private val cr = "111111111111111"
    private fun payload(date: String, value: String): String =
        """{"status":1,"crNo":"$cr","groups":[{"testName":"CBC",
          "rows":[{"parameterName":"Platelet Count","unit":"10^3/uL",
          "valuesByDate":{"$date":"$value"}}]}]}"""

    @Test fun twoWindowsBuildOneStableHistoryWithoutDuplicateLazyKeys() {
        val person = PatientInfo(name="Synthetic",crNo=cr)
        val recent = BulkParser.parse(payload("26-Oct-2026","50"),cr,person,emptyList())
        val old = BulkParser.parse(payload("15-Oct-2026","100"),cr,person,emptyList())
        val first = BulkAccumulator.append(null,recent,1)
        val all = BulkAccumulator.append(first,old,2)
        assertEquals(2,all.results.size)
        assertEquals(setOf("2026-10-26","2026-10-15"),all.results.map{it.date}.toSet())
        assertEquals(2,all.results.map{it.id}.distinct().size)
        assertEquals("batch-1-row-0", all.results.first().id)
        assertEquals("batch-2-row-0", all.results.last().id)
    }

    @Test fun overlappingWindowAvoidsOnlyCrossBatchExactDuplicates() {
        val person = PatientInfo(crNo=cr)
        val part = BulkParser.parse(payload("26-Oct-2026","50"),cr,person,emptyList())
        val a = BulkAccumulator.append(null,part,1)
        val b = BulkAccumulator.append(a,part,2)
        assertEquals(1,b.results.size)
    }

    @Test fun mismatchedPatientCannotMerge() {
        val first = ClinicalBundle(PatientInfo(crNo=cr),
            listOf(LabResult("id","CBC","WBC","2026-10-26","7")),emptyList())
        val second = ClinicalBundle(PatientInfo(crNo="222222222222222"),
            listOf(LabResult("other","CBC","WBC","2026-10-25","8")),emptyList())
        assertThrows(IllegalStateException::class.java) {
            BulkAccumulator.append(first,second,2)
        }
    }

    @Test fun byDateMetadataIsReadOnlyForMatchingDate() {
        val raw = """
           {"status":1,"crNo":"$cr","groups":[
            {"testName":"CBC","rows":[{
               "parameterName":"Platelet Count",
               "valuesByDate":{"26-Oct-2026":"50","15-Oct-2026":"100"},
               "collectionDate":{"26-Oct-2026":"26-Oct-2026 10:15",
                                 "15-Oct-2026":"15-Oct-2026 09:00"},
               "reportedDate":{"26-Oct-2026":"26-Oct-2026 11:40"},
               "labNo":{"26-Oct-2026":"LAB01","15-Oct-2026":"LAB02"}
             }]}
           ]}
        """.trimIndent()
        val rows=BulkParser.parse(raw,cr,PatientInfo(crNo=cr),emptyList()).results
            .associateBy { it.date }
        assertEquals("LAB01",rows.getValue("2026-10-26").labNumber)
        assertEquals("LAB02",rows.getValue("2026-10-15").labNumber)
        assertEquals("26-Oct-2026 11:40",rows.getValue("2026-10-26").reportedDate)
        assertEquals("",rows.getValue("2026-10-15").reportedDate)
        assertEquals("15-Oct-2026 09:00",rows.getValue("2026-10-15").sentDate)
    }
}
