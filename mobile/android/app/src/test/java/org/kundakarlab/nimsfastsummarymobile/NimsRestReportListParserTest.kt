package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NimsRestReportListParserTest {
    private val cr = "331012600000001"

    @Test
    fun parsesNestedSyntheticReportList() {
        val payload = """
            {
              "patientDetails": {"patientName":"Synthetic Patient","crNo":"331012600000001","age":"42","sex":"M"},
              "data": {
                "reportList": [
                  {"investigationName":"CBC","resultDate":"28/09/2026","reportUrl":"/HBIMS/report/synthetic-cbc"},
                  {"test_name":"Blood Culture","report_date":"27/09/2026","file_name":"SAFE_FIXTURE_ARG"}
                ]
              }
            }
        """.trimIndent()

        val parsed = NimsRestReportListParser.parse(payload, cr)
        assertEquals("nims_rest_api", parsed.source)
        assertEquals(cr, parsed.returnedCrNo)
        assertEquals("Synthetic Patient", parsed.patientName)
        assertEquals(2, parsed.rows.size)
        assertTrue(parsed.rows.any { it.directUrl.contains("/HBIMS/report/synthetic-cbc") })
        assertTrue(parsed.rows.any { it.token == "SAFE_FIXTURE_ARG" })
    }

    @Test(expected = NimsIdentityMismatchException::class)
    fun rejectsReturnedCrMismatch() {
        NimsRestReportListParser.parse(
            """[{"testName":"CBC","fileName":"SAFE_FIXTURE_ARG","crNo":"331012600000999"}]""",
            cr
        )
    }

    @Test
    fun tokenShapedReportUrlBecomesOpaqueToken() {
        val parsed = NimsRestReportListParser.parse(
            """[{"testName":"CBC","reportUrl":"SAFE_FIXTURE_ARG","crNo":"331012600000001"}]""",
            cr
        )
        assertEquals("", parsed.rows.single().directUrl)
        assertEquals("SAFE_FIXTURE_ARG", parsed.rows.single().token)
    }

    @Test
    fun ignoresOffDomainUrlsAndUnsafeTokens() {
        val parsed = NimsRestReportListParser.parse(
            """
            [
              {"testName":"Unsafe URL","reportUrl":"https://example.com/report.pdf","fileName":"../secret"},
              {"testName":"Safe token","fileName":"SAFE_FIXTURE_ARG"}
            ]
            """.trimIndent(),
            cr
        )
        assertEquals(1, parsed.rows.size)
        assertEquals("SAFE_FIXTURE_ARG", parsed.rows.single().token)
    }
}
