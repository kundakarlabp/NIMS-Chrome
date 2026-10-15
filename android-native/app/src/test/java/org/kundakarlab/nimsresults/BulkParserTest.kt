package org.kundakarlab.nimsresults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkParserTest {
    @Test
    fun parsesStructuredGroupsAndDates() {
        val raw = """
            {
              "status":1,
              "crNo":"331012600350500",
              "groups":[
                {
                  "testName":"Complete Blood Count",
                  "rows":[
                    {
                      "parameterName":"WBC",
                      "unit":"10^3/uL",
                      "refRange":"4-11",
                      "valuesByDate":{"15-Oct-2026":"12.5","14-Oct-2026":"9.2"},
                      "flagsByDate":{"15-Oct-2026":"H"}
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val bundle = BulkParser.parse(
            raw,
            "331012600350500",
            PatientInfo(name = "Test", crNo = "331012600350500"),
            emptyList()
        )

        assertEquals(2, bundle.results.size)
        assertEquals("2026-10-15", bundle.results.first().date)
        assertEquals(12.5, bundle.results.first().numericValue!!, 0.001)
        assertEquals("H", bundle.results.first().abnormal)
    }

    @Test
    fun rejectsWrongCr() {
        var failed = false
        try {
            BulkParser.parse(
                """{"status":1,"crNo":"331012600350501","groups":[{"testName":"RFT","rows":[{"parameterName":"Creatinine","valuesByDate":{"15-Oct-2026":"1.1"}}]}]}""",
                "331012600350500",
                PatientInfo(crNo = "331012600350500"),
                emptyList()
            )
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
    }
}
