package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class SerologyAndCultureDisplayTest {
    @Test fun pairedDengueIgKeepsEachSourceValueAndOwnCutoff() {
        val source = """
            Anti Dengue IgG antibodies (ELFA) – POSITIVE-IV:1.36 (IV≥1.00 - Positive)
            Anti Dengue virus IgM antibodies (ELFA) – Negative-IV:0.96 (IV < 1.00 - Negative)
        """.trimIndent()
        val findings=SerologyReportDisplay.extract("Dengue (IgG+IgM) ELFA",source)
        assertEquals(2,findings.size)
        assertEquals("Dengue IgG (ELFA)",findings[0].label)
        assertEquals("Positive",findings[0].result)
        assertEquals("IV 1.36",findings[0].testValue)
        assertEquals("IV≥1.00 - Positive",findings[0].reference)
        assertEquals("Dengue IgM (ELFA)",findings[1].label)
        assertEquals("Negative",findings[1].result)
        assertEquals("IV 0.96",findings[1].testValue)
        assertEquals("IV < 1.00 - Negative",findings[1].reference)
    }

    @Test fun dengueNs1AndLeptospiraRetainPrecisionAndLabThresholds() {
        val ns1="Dengue virus NS1 Ag (ELFA) – Negative-IV:0.82 (IV <1.00 - Negative)"
        val n=SerologyReportDisplay.extract("Dengue NS1 Ag ELFA",ns1).single()
        assertEquals("Negative",n.result)
        assertEquals("IV 0.82",n.testValue)
        assertEquals("IV <1.00 - Negative",n.reference)
        val lepto="Leptospira IgM antibody (ELISA)- TV: 0.1 - NEGATIVE (Test value <1 - Negative)."
        val l=SerologyReportDisplay.extract("Leptospira Elisa (IgM + IgM)",lepto).single()
        assertEquals("Negative",l.result)
        assertEquals("TV 0.1",l.testValue)
        assertEquals("Test value <1 - Negative",l.reference)
    }

    @Test fun qualitativeSerologyDoesNotFabricateThresholds() {
        val hcv=SerologyReportDisplay.extract("HCV ELFA / ELISA",
            "Anti Hepatitis C virus (HCV) antibodies(ELFA) - Negative").single()
        assertEquals("Negative",hcv.result)
        assertTrue(hcv.testValue.isBlank())
        assertTrue(hcv.reference.isBlank())
        val hiv=SerologyReportDisplay.extract("HIV RAPID",
            "HIV (Dot blot ELISA) - Non Reactive").single()
        assertEquals("Non-reactive",hiv.result)
        assertTrue(hiv.reference.isBlank())
        assertTrue(SerologyReportDisplay.extract("HIV RAPID","com2: 1").isEmpty())
    }

    @Test fun noGrowth48HoursIsNotAnObservedFiveDayFinal() {
        val title="Fan Blood Culture(1 set 2 bottle) 48 hrs"
        val result="CULTURE SHOWS NO GROWTH AEROBICALLY AFTER INCUBATION FOR ABOUT 36to 48 HOURS. FINAL REPORT AFTER 5 DAYS OF INCUBATION."
        assertEquals("Blood culture · Set 1 · Bottle 2 · 48 h interim",
            BloodCultureCard.title(title))
        assertEquals("48 h interim",BloodCultureCard.stage(title,"FINAL",result))
        assertEquals("No aerobic growth after 36–48 hours",
            BloodCultureCard.finding(title,result))
    }

    @Test fun distinctSetAndBottleLabelsPersistEvenWithSameLabAccession() {
        assertEquals("Blood culture · Set 1 · Bottle 2 · 48 h interim",
            BloodCultureCard.title("Fan Blood Culture(1 set 2 bottle) 48 hrs"))
        assertEquals("Blood culture · Set 2 · Bottle 1 · 48 h interim",
            BloodCultureCard.title("Fan Blood Culture(2 set 1 bottle) 48 hrs"))
        assertEquals("Final",BloodCultureCard.stage(
            "Blood culture set 2 bottle 1","FINAL",
            "BLOOD CULTURE SHOWS NO GROWTH AEROBICALLY AFTER 5 DAYS INCUBATION."))
        assertEquals("No aerobic growth after 5 days",BloodCultureCard.finding(
            "Blood culture set 2 bottle 1",
            "BLOOD CULTURE SHOWS NO GROWTH AEROBICALLY AFTER 5 DAYS INCUBATION."))
    }
}
