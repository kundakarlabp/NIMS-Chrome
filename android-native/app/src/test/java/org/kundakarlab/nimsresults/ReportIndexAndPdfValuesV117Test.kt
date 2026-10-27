package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class ReportIndexAndPdfValuesV117Test {
    private val cr = "111111111111111"
    @Test fun htmlReportListKeepsCorrectColumnLabelsAndPairedIdentifiers() {
        val html = """
            <html><body>CR No: $cr
            <table><tr>
              <th>LabName</th><th>Test Name</th><th>Sample No/Lab No</th>
              <th>Collection Date</th><th>Report NO.</th><th>Report</th></tr>
              <tr><td>Bacteriology</td><td>Fan Blood Culture Final</td>
                <td>25BCT55555/B12345</td><td>28-Aug-2025</td>
                <td>1</td><td><a onclick="printReport('synthetic.pdf')">View Report</a></td>
              </tr>
            </table></body></html>
        """.trimIndent()
        val reports = ReportPayloadParser.parseHtml(
            html,"https://www.nimsts.edu.in",cr)
        assertEquals(1,reports.size)
        val item = reports.single()
        assertEquals("Bacteriology",item.department)
        assertEquals("Fan Blood Culture Final",item.title)
        assertEquals("25BCT55555",item.sampleNumber)
        assertEquals("B12345",item.labNumber)
        assertEquals("28-Aug-2025",item.sentDate)
        assertEquals("1", item.reportNumber)
        assertEquals("",item.reportedDate)
        assertTrue(item.url.contains("synthetic.pdf"))
    }

    @Test fun pdfValuesAreLimitedToKnownExplicitAnalyses() {
        val text = """
          LIVER FUNCTION TESTS:
          Serum AST (SGOT)       38             U/L          <= 35
          Serum Albumin          4.5            g/dL         3.5 - 5.2
          A patient in room 38 developed a fever 39.2.
          Serum Creatinine       2.25           mg/dL        0.7 - 1.3
        """.trimIndent()
        val rows = PdfClinicalValues.parse(text)
        assertEquals(3,rows.size)
        assertEquals("38",rows.first().value)
        assertEquals("U/L",rows.first().unit)
        assertEquals("2.25",rows.last().value)
        assertFalse(rows.any { it.parameter.contains("room") })
    }

    @Test fun coagulationControlIsNotPatientAptt() {
        val rows = PdfClinicalValues.parse(
            "Plasma APTT (Patient)  27.4  Sec  25 - 39\n" +
            "Plasma APTT (Control)  32.0  Sec  -")
        assertEquals(2,rows.size)
        assertEquals("27.4",rows[0].value)
        assertEquals("32.0",rows[1].value)
        assertNotEquals(rows[0].parameter,rows[1].parameter)
    }

    @Test fun htmlIndexDisambiguatesSameAccessionDifferentInvestigations() {
        val stain=ReportRef("s","STAINING","14-Jul-2025","Bacteriology",
            "https://www.nimsts.edu.in/HISReportAction?hmode=PRINTREPORT&fileName=stain",
            labNumber="E9999")
        val culture=ReportRef("c","AEROBIC CULTURE","14-Jul-2025","Bacteriology",
            "https://www.nimsts.edu.in/HISReportAction?hmode=PRINTREPORT&fileName=culture",
            labNumber="E9999")
        val row = LabResult("r","STAINING","Report","2025-07-14",
            "Gram negative bacilli",labNumber="E9999")
        assertNull(ReportLinkResolver.candidates(
            listOf(row),listOf(stain,culture),cr).verified)
    }
}
