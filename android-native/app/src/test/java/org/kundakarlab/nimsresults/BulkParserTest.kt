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
              "crNo":"123456789012345",
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
            "123456789012345",
            PatientInfo(name = "Test", crNo = "123456789012345"),
            emptyList()
        )

        assertEquals(2, bundle.results.size)
        assertEquals("2026-10-15", bundle.results.first().date)
        assertEquals(12.5, bundle.results.first().numericValue!!, 0.001)
        assertEquals("H", bundle.results.first().abnormal)
    }

    @Test
    fun narrativeNumbersNeverBecomeQuantitativeTrends() {
        val raw = """
            {"status":1,"crNo":"123456789012345","groups":[
              {"testName":"Culture","rows":[
                {"parameterName":"Report","valuesByDate":{
                  "15-Oct-2026":"No growth after 48 hours. Final after 5 days",
                  "14-Oct-2026":"Negative (<6 mg/1000mL)"
                }}
              ]},
              {"testName":"CBC","rows":[
                {"parameterName":"WBC","valuesByDate":{"15-Oct-2026":"12.5"}}
              ]}
            ]}
        """.trimIndent()
        val bundle = BulkParser.parse(raw, "123456789012345",
            PatientInfo(crNo = "123456789012345"), emptyList())
        assertEquals(3, bundle.results.size)
        assertTrue(bundle.results.filter { it.test == "Culture" }.all { it.numericValue == null })
        assertEquals(12.5, bundle.results.first { it.test == "CBC" }.numericValue!!, 0.001)
    }

    @Test
    fun preservesCultureFormattingAndLabSourceText() {
        val raw = """
            {"status":1,"crNo":"123456789012345","groups":[
              {"testName":"Blood culture","rows":[
                {"parameterName":"Result","valuesByDate":{
                  "15-Oct-2026":"CULTURE REPORT &ndash; NO GROWTH<br>SENSITIVITY: Not provided"
                }}
              ]}
            ]}
        """.trimIndent()
        val bundle = BulkParser.parse(raw, "123456789012345",
            PatientInfo(crNo = "123456789012345"), emptyList())
        val value = bundle.results.single().valueText
        assertTrue(value.contains("–"))
        assertTrue(value.contains("\n"))
        assertTrue(value.contains("SENSITIVITY: Not provided"))
        assertEquals(null, bundle.results.single().numericValue)
    }

    @Test
    fun rejectsWrongCr() {
        var failed = false
        try {
            BulkParser.parse(
                """{"status":1,"crNo":"123456789012346","groups":[{"testName":"RFT","rows":[{"parameterName":"Creatinine","valuesByDate":{"15-Oct-2026":"1.1"}}]}]}""",
                "123456789012345",
                PatientInfo(crNo = "123456789012345"),
                emptyList()
            )
        } catch (_: IllegalStateException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun onlyClinicallyUsefulNumericParametersAppearInTrends() {
        val sample = LabResult("1","AEROBIC CULTURE","Sample Processed:","2025-06-23","80",80.0)
        val wbc = LabResult("2","CBC","WBC","2025-06-23","10.4",10.4,"10^3/uL")
        val lowCount = LabResult("3","Blood culture","Test Done By","2025-06-23","-1",-1.0)
        assertTrue(!ClinicalResultRules.isTrendEligible(sample))
        assertTrue(!ClinicalResultRules.isTrendEligible(lowCount))
        assertTrue(ClinicalResultRules.isTrendEligible(wbc))
        assertTrue(ClinicalResultRules.inWindow("2025-06-23","2025-07-15",30))
        assertTrue(!ClinicalResultRules.inWindow("2025-06-23","2025-07-15",7))
    }

    @Test
    fun salmonellaSensitivityAndResistanceAreNotFlattenedTogether() {
        val raw = "CULTURE REPORT CULTURE SHOWS GROWTH OF SALMONELLA SPECIES " +
            "SENSITIVITY REPORT CEFOTAXIME CEFTRIAXONE CIPROFLOXACIN LEVOFLOXACIN COTRIMOXAZOLE " +
            "RESISTANCE REPORT NIL ADVICE TO SEND BLOOD CULTURES"
        val summary = CultureNarrative.parse(raw)
        assertTrue(summary.culture.contains("SALMONELLA SPECIES"))
        assertTrue(summary.susceptible.contains("CEFTRIAXONE"))
        assertTrue(!summary.susceptible.contains("RESISTANCE REPORT"))
        assertEquals("NIL", summary.resistant)
        assertTrue(summary.advice.contains("SEND BLOOD CULTURES"))
    }

    @Test
    fun nonSectionedCultureRetainsFullNarrativeAndDoesNotInventAst() {
        val raw = "NO GROWTH AFTER 5 DAYS; SENSITIVITY NOT APPLICABLE"
        val result = CultureNarrative.parse(raw)
        assertEquals(raw, result.culture)
        assertEquals("", result.susceptible)
        assertEquals("", result.resistant)
    }

}
