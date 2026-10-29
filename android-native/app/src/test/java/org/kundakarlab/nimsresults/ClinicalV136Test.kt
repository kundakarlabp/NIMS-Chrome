package org.kundakarlab.nimsresults

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClinicalV136Test {
    private fun value(test: String, parameter: String, date: String, number: String) =
        LabResult(id="source", test=test, parameter=parameter, date=date,
            valueText=number, numericValue=number.toDouble())

    @Test fun codedRenalPanelAndLftAreTogetherNotInOther() {
        val rows=listOf(
            value("BU/SC/SE","Serum Potassium","2025-07-19","4.9"),
            value("BU/SC/SE","Serum Creatinine","2025-07-19","0.18"),
            value("BU/SC/SE","Serum Urea","2025-07-19","11"),
            value("Liver Function Tests","Serum ALT (SGPT)","2025-07-19","51"),
            value("BU/SC/SE","Serum Sodium","2025-07-19","142")
        )
        val tables=LabPanelMatrix.build(rows,clinical=true)
        assertEquals(1,tables.size)
        assertEquals(LabPanelMatrix.Panel.METABOLIC,tables.single().panel)
        assertEquals(listOf("Creatinine","Urea","ALT","Na","K"),
            tables.single().parameters.map { LabPanelMatrix.shortLabel(it.title) })
    }

    @Test fun parameterDisplayShortensDuplicatedAstSgotNamesOnly() {
        assertEquals("AST",LabPanelMatrix.shortLabel("Serum AST (SGOT)"))
        assertEquals("ALT",LabPanelMatrix.shortLabel("Serum ALT (SGPT)"))
        assertEquals("ALP",LabPanelMatrix.shortLabel("Serum Alk. Phosphatase (ALP)"))
        assertEquals("Creatinine",LabPanelMatrix.shortLabel("Serum Creatinine"))
        assertEquals("Urine Creatinine",LabPanelMatrix.shortLabel("Urine Creatinine"))
    }

    @Test fun restReqdnoIsOrderNotSpecimenOrLabNumber() {
        val source=JSONArray().put(JSONObject()
            .put("TESTNAME","BU/SC/SE")
            .put("REPORTDATE","2025-07-19 01:39:47.0")
            .put("REQDNO","ORDER-123"))
        val rows=ReportPayloadParser.parseRest(source,"331012500445538",
            verifiedPatientContext=true)
        assertEquals(1,rows.size)
        assertEquals("ORDER-123",rows[0].sourceOrderId)
        assertEquals("",rows[0].sampleNumber)
        assertEquals("",rows[0].labNumber)
        assertEquals("",rows[0].url)
    }

    @Test fun datesRemainChronologicalNotAlphabetical() {
        val rows=listOf(value("BU/SC/SE","Serum Creatinine","2025-07-19","1.2"),
            value("BU/SC/SE","Serum Creatinine","2025-06-28","1.1"))
        assertEquals(listOf("2025-07-19","2025-06-28"),
            LabPanelMatrix.build(rows,clinical=true).single().dates)
    }
}
