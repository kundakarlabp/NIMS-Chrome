package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

/** No production patient data. Provenance and fail-closed association regressions. */
class ProvenanceV122Test {
    private val cr = "111111111111111"

    private fun parse(value: String, secondary: Boolean = false): ClinicalBundle {
        val raw = """
          {"status":1,"crNo":"$cr","groups":[{"testName":"CBC","rows":[
            {"parameterName":"WBC","labNo":"A11111","sampleNo":"S11111",
             "reportNo":"R1","packingListNo":"PACK99",
             "valuesByDate":{"15-Oct-2026":"$value"}}
            ${if (secondary) """,{ "parameterName":"WBC","labNo":"A22222",
             "sampleNo":"S22222","valuesByDate":{"15-Oct-2026":"$value"}}""" else ""}
          ]}]}
        """.trimIndent()
        return BulkParser.parse(raw, cr, PatientInfo(crNo = cr), emptyList())
    }

    @Test fun amendedValueDoesNotChangeSourceKey() {
        val before = parse("10.0").results.single()
        val after = parse("12.4").results.single()
        assertEquals(before.sourceKey, after.sourceKey)
        assertNotEquals(before.valueText, after.valueText)
        assertEquals("PACK99", after.packingListNumber)
        assertEquals("R1", after.reportNumber)
        assertEquals("S11111", after.sampleNumber)
        assertTrue(after.sourceRow.contains("valuesByDate"))
    }

    @Test fun sameDayRepeatedSpecimensGetDistinctKeys() {
        val results = parse("10.0", secondary = true).results
        assertEquals(2, results.size)
        assertEquals(2, results.map { it.sourceKey }.distinct().size)
    }

    @Test fun restIdentityFieldsAreIndependent() {
        val payload = org.json.JSONObject(
            """{"patient":{"crNo":"$cr"},"reports":[
             {"crNo":"$cr","reportName":"CBC","reportNo":"R12",
              "reportStage":"Final","labNo":"A11111","sampleNo":"S11111",
              "packingListNo":"PACK99","fileName":"safe.pdf"}]}"""
        )
        val r = ReportPayloadParser.parseRest(payload, cr).single()
        assertEquals("R12", r.reportNumber)
        assertEquals("Final", r.reportStage)
        assertEquals("S11111", r.sampleNumber)
        assertEquals("A11111", r.labNumber)
        assertEquals("PACK99", r.packingListNumber)
        assertTrue(r.sourceRow.contains("reportName"))
    }

    @Test fun wrongPatientPdfNeverBecomesVerifiedByMatchingInventoryAccession() {
        ReportTitleCache.clear()
        val u = "https://www.nimsts.edu.in/HISReportAction?hmode=PRINTREPORT&fileName=synthetic.pdf"
        val row = LabResult("a", "AEROBIC CULTURE", "Result", "2026-10-15",
             "No growth", labNumber = "A11111")
        val report = ReportRef("r", "AEROBIC CULTURE", "15-Oct-2026", "Microbiology", u,
             labNumber = "A11111")
        assertFalse(ReportTitleCache.learnPages(u,
            listOf("CR No: 222222222222222\\nLab/Study No.: A11111\\nAEROBIC CULTURE"),cr))
        val association = ReportLinkResolver.candidates(listOf(row), listOf(report), cr)
        assertNull(association.verified)
        ReportTitleCache.clear()
    }

    @Test fun specimenIdentifierDoesNotMasqueradeAsLabAccession() {
        val json = """{"status":1,"crNo":"$cr","groups":[{"testName":"CBC","rows":[
          {"parameterName":"WBC","sampleNo":"S123456",
           "valuesByDate":{"15-Oct-2026":"9.1"}}]}]}"""
        val result = BulkParser.parse(json, cr, PatientInfo(crNo=cr), emptyList()).results.single()
        assertEquals("S123456", result.sampleNumber)
        assertEquals("", result.labNumber)
    }

    @Test fun sharedPdfUrlDoesNotCollapseSeparateReportNumbers() {
        val base = NimsUrls.CR_REPORTS
        val html = """<html><body>CR No: $cr<table>
           <tr><td>Microbiology</td><td>Blood culture</td><td>Sample/A123</td>
           <td>15-Oct-2026</td><td>1</td>
           <td><a onclick="printReport('blood.pdf')">View Report</a></td></tr>
           <tr><td>Microbiology</td><td>Blood culture</td><td>Sample/A123</td>
           <td>15-Oct-2026</td><td>2</td>
           <td><a onclick="printReport('blood.pdf')">View Report</a></td></tr>
           </table></body></html>"""
        val reports = ReportPayloadParser.parseHtml(html, base, cr)
        assertEquals(2, reports.size)
        assertEquals(setOf("1","2"), reports.map { it.reportNumber }.toSet())
    }
}
