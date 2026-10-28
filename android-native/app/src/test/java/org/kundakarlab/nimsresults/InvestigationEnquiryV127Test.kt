package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class InvestigationEnquiryV127Test {
    private val cr="111111111111111"
    private fun doc(patient:String=cr, lines:String):String="""
        <html><body>
          <div>CR No: $patient · NIMS Enquiry</div>
          <form name="searchForm"><input name="patCrNo" value="$patient">
            <table><tbody>
              <tr><th>Request Date/Time</th><th>Test Name</th><th>Sample No</th>
                  <th>Packing List No</th><th>Packing List Date</th><th>Bill No</th>
                  <th>Department</th><th>Status</th></tr>
              $lines
            </tbody></table>
          </form>
        </body></html>
    """.trimIndent()

    @Test fun parsesFullHistoryInsideSearchFormAndPreservesEmptyCells() {
        val html=doc(lines="""
           <tr><td>27-Oct-2026 09:15</td><td>AEROBIC CULTURE</td>
             <td>SN-01</td><td>PACK-88</td><td>27-Oct-2026 10:00</td>
             <td>BILL-10</td><td>Bacteriology</td><td>Report Generated</td></tr>
           <tr><td>28-Oct-2026</td><td>CBC</td>
             <td></td><td>PACK-89</td><td></td>
             <td>BILL-11</td><td>Haematology</td><td>Sample Collected</td></tr>
        """)
        val capture=InvestigationEnquiryParser.parseHtml(html,cr)
        assertEquals(2,capture.rows.size)
        assertEquals("SN-01",capture.rows[0].sampleNumber)
        assertEquals("PACK-88",capture.rows[0].packingListNumber)
        assertEquals("Report Generated",capture.rows[0].status)
        assertEquals("",capture.rows[1].sampleNumber)
        assertEquals("PACK-89",capture.rows[1].packingListNumber)
        assertEquals("",capture.rows[1].labNumber)
        assertEquals("Sample Collected",capture.rows[1].status)
        assertTrue(capture.rows[0].sourceFieldsJson.contains("BILL-10"))
    }

    @Test fun rejectsWrongPatientAndCrossPatientEnquiry() {
        val html=doc(patient="222222222222222",lines="""
           <tr><td>27-Oct-2026</td><td>CBC</td><td>SN</td>
              <td>PACK</td><td></td><td>B</td><td>Lab</td><td>Report Generated</td></tr>
        """)
        assertThrows(IdentityMismatchException::class.java) {
            InvestigationEnquiryParser.parseHtml(html,cr)
        }
    }

    @Test fun typedCRWithoutVisiblePatientConfirmationIsInsufficient() {
        val html=doc(lines="""
            <tr><td>27-Oct-2026</td><td>CBC</td><td>S</td>
            <td>P</td><td></td><td>B</td><td>Lab</td><td>Result Printed</td></tr>
        """).replace("<div>CR No: $cr · NIMS Enquiry</div>","")
        assertThrows(IdentityMismatchException::class.java) {
            InvestigationEnquiryParser.parseHtml(html,cr)
        }
    }

    @Test fun rejectsTablesWithNoGenuineEnquiryHeaders() {
        val html="""<html><body>CR No: $cr
             <table><tr><th>Test Name</th><th>Collection Date</th></tr>
             <tr><td>CBC</td><td>27-Oct-2026</td></tr></table></body></html>"""
        assertThrows(IllegalArgumentException::class.java) {
            InvestigationEnquiryParser.parseHtml(html,cr)
        }
    }

    @Test fun preservesRepeatedTestsAndDoesNotConvertPackingNumberToLabNumber() {
        val rows="""
          <tr><td>27-Oct-2026 08:00</td><td>CBC</td><td>SN-A</td>
              <td>PK-1</td><td></td><td>B</td><td>Lab</td><td>Report Generated</td></tr>
          <tr><td>27-Oct-2026 17:00</td><td>CBC</td><td>SN-B</td>
              <td>PK-2</td><td></td><td>B</td><td>Lab</td><td>Report Generated</td></tr>"""
        val items=InvestigationEnquiryParser.parseHtml(doc(lines=rows),cr).rows
        assertEquals(2,items.size)
        assertEquals(listOf("SN-A","SN-B"),items.map {it.sampleNumber})
        assertTrue(items.all { it.labNumber.isBlank() })
    }

    @Test fun JSONRoundtripRetainsValuesAndRedactedSchemaCannotContainIdentifiers() {
        val capture=InvestigationEnquiryParser.parseHtml(doc(lines="""
          <tr><td>27-Oct-2026</td><td>CBC</td><td>SN-1</td>
            <td>PK-1</td><td></td><td>B</td><td>Lab</td><td>Report Generated</td></tr>
        """),cr)
        val restored=InvestigationEnquiryParser.fromJson(InvestigationEnquiryParser.toJson(capture))
        assertEquals(capture.rows,restored.rows)
        val summary=InvestigationEnquiryParser.schemaSummary(doc(lines="""
          <tr><td>27-Oct-2026</td><td>CBC</td><td>SN-1</td>
            <td>PK-1</td><td></td><td>B</td><td>Lab</td><td>Report Generated</td></tr>
        """))
        assertFalse(summary.contains(cr))
        assertFalse(summary.contains("SN-1"))
        assertFalse(summary.contains("PK-1"))
        assertTrue(summary.contains("enquiry.tableRows="))
    }
}
