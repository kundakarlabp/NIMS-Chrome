package org.kundakarlab.nimsresults

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RestReportDiagnosticsV128Test {
    private val cr="111111111111111"
    private fun payload()=JSONObject("""
      {"patient":{"crNo":"111111111111111","name":"PRIVATE PATIENT"},
       "data":{"reports":[
         {"CRNO":"111111111111111","REPORTNAME":"Blood culture",
          "LABNO":"LAB-77","SAMPLENO":"SN-44","COLLECTIONDATE":"28-Oct-2026 08:10",
          "REPORTINGDATE":"28-Oct-2026 13:21","REPORTNO":"R-88",
          "narrative":"Sensitive to an antibiotic",
          "findings":{"organism":"PRIVATE-ORGANISM","remarks":"PRIVATE-COMMENT"},
          "measurements":[{"parameterName":"Result","resultValue":"PRIVATE-VALUE"}]},
         {"CRNO":"111111111111111","REPORTNAME":"CBC","LABNO":"LAB-78",
          "SAMPLENO":"SN-45","COLLECTIONDATE":"28-Oct-2026"}
       ]}}
    """.trimIndent())

    @Test fun findsNestedReportCollectionWithoutChoosingOneObservation() {
        val data=payload()
        val candidates=RestReportDiagnostics.candidates(data)
        assertTrue(candidates.size>=2)
        val best=RestReportDiagnostics.bestArray(data)
        assertEquals(2,best.length())
        assertEquals("Blood culture",best.getJSONObject(0).getString("REPORTNAME"))
    }

    @Test fun honorsCaseInsensitiveLabAndSampleKeysAndRetainsAllRawText() {
        val items=ReportPayloadParser.parseRest(payload(),cr)
        assertEquals(2,items.size)
        val culture=items.first()
        assertEquals("LAB-77",culture.labNumber)
        assertEquals("SN-44",culture.sampleNumber)
        assertEquals("28-Oct-2026 08:10",culture.collectionDateTime)
        assertEquals("28-Oct-2026 13:21",culture.reportedDate)
        assertEquals("nims_rest_reportList",culture.sourceTag)
        assertTrue(culture.sourceRow.contains("PRIVATE-ORGANISM"))
        assertTrue(culture.sourceRow.contains("PRIVATE-VALUE"))
        assertTrue(culture.sentDate.isBlank())
    }

    @Test fun diagnosticNeverCopiesClinicalValuesOrPatientIdentity() {
        val data=payload()
        RestReportDiagnostics.reset()
        RestReportDiagnostics.status(200,1500,164,"application/json")
        RestReportDiagnostics.inspect(data,cr)
        val log=RestReportDiagnostics.snapshot()
        assertTrue(log.contains("rest.httpStatus=200"))
        assertTrue(log.contains("rest.candidateRows=2"))
        assertTrue(log.contains("rest.rowsWithLabNo=2"))
        assertTrue(log.contains("rest.rowsWithSampleNo=2"))
        assertTrue(log.contains("rest.possibleDetailRecords=1"))
        for (secret in listOf(cr,"PRIVATE PATIENT","SN-44","LAB-77",
                "PRIVATE-VALUE","PRIVATE-ORGANISM","PRIVATE-COMMENT"))
            assertFalse("Leaked protected data",log.contains(secret))
    }

    @Test fun missingCrDoesNotBecomeVerifiedJustBecauseRequestIncludedCr() {
        val response=JSONObject("""{"reports":[
           {"REPORTNAME":"CBC","LABNO":"LAB-10","REPORTNO":"R-1"}]}""")
        assertTrue(ReportPayloadParser.parseRest(response,cr).isEmpty())
        RestReportDiagnostics.inspect(response,cr)
        assertTrue(RestReportDiagnostics.snapshot().contains("rest.crEvidence=not_returned"))
    }

    @Test fun responseWithOtherPatientCrIsRejectedBeforeSourceAdoption() {
        val wrong=JSONObject("""{"patient":{"crNo":"222222222222222"},
          "reports":[{"reportName":"CBC","labNo":"LAB-1"}]}""")
        assertThrows(IdentityMismatchException::class.java) {
            ReportPayloadParser.parseRest(wrong,cr)
        }
    }

    @Test fun knownJsonShapeStillWorksForExistingReportTokenContract() {
        val x=JSONObject("""{"patient":{"crNo":"111111111111111"},
          "reports":[{"reportName":"CBC","reportDate":"28-Oct-2026",
          "fileName":"blood-123.pdf","crNo":"111111111111111"}]}""")
        val parsed=ReportPayloadParser.parseRest(x,cr)
        assertEquals(1,parsed.size)
        assertTrue(parsed.single().url.contains("PRINTREPORT"))
    }
}
