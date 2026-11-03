package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

/** Synthetic report fragments, never real patients or accession numbers. */
class VerifiedPdfResultsV203Test {
    @Test fun pcrShowsPatientResultButNeverReferenceTable() {
        val pdf="""
            CMV DNA - Quantitative (Viral Load) - PCR       Target Not Detected
            Methodology: Real time PCR for detection.
            Interpretation:
            Target Not Detected: Sample does not contain detectable CMV DNA.
            >25 to 60000000 IU/mL: Detected within linear range.
        """.trimIndent()
        assertEquals(listOf(VerifiedPdfItem("CMV DNA","Target Not Detected")),
            VerifiedPdfResults.extract("CMV viral load test",pdf))
        assertTrue(VerifiedPdfResults.extract("CMV viral load test",
            "Methodology\nInterpretation: Target Not Detected: Sample does not contain CMV DNA")
            .isEmpty())
    }

    @Test fun pcrRetainsSourceUnitAndMagnitude() {
        assertEquals("2,450 IU/mL",VerifiedPdfResults.extract("CMV viral load test",
            "CMV DNA Quantitative PCR Result: 2,450 IU/mL\nMethodology: RT PCR")
            .single().result)
        assertEquals("15,000 copies/mL",VerifiedPdfResults.extract("EBV viral load test",
            "EBV Virus - PCR Quantitative 15,000 copies/mL\nMethodology: RT PCR")
            .single().result)
        assertEquals("Target Not Detected",VerifiedPdfResults.extract(
            "Parvo virus viral load test",
            "Real Time PCR Detection of Parvo Virus B19  Target Not Detected\nMethodology:")
            .single().result)
    }

    @Test fun quantiferonReferenceTableIsNotPatientResult() {
        val sample="""
            IGRA (QUANTIFERON TB Gold Plus Assay)
            Nil tube TB1 TB2 Mitogen-Nil Final Result
            0.35 0.33 0.80 4.51 POSITIVE
            INTERPRETATION:
            Any Any Positive M.tuberculosis infection likely
        """.trimIndent()
        assertEquals("POSITIVE",VerifiedPdfResults.extract(
            "IGRA QuantiFERON TB Gold Plus",sample).single().result)
        assertTrue(VerifiedPdfResults.extract("IGRA QuantiFERON TB Gold Plus",
            "INTERPRETATION: Final Result POSITIVE for any positive response")
            .isEmpty())
    }

    @Test fun fibrinogenAndInvalidPtMustNotBecomeControlValues() {
        assertEquals("52 mg/dl",VerifiedPdfResults.extract("Plasma Fibrinogen",
            "PLASMA FIBRINOGEN\nFibrinogen 52 mg/dl\nNormal 200-400 mg/dl")
            .single().result)
        val rejected="""
            Prothrombin Time (PT)        11.7     9.0 -13.0 Sec
            PT INR                      1.0
            APTT                        32.0    25.0-39.0 Sec
            Comments: Improper sample collection. Please repeat the test.
        """.trimIndent()
        val facts=VerifiedPdfResults.extract("PT/APTT (40A+41A)",rejected)
        assertEquals("Not reported",facts.first().result)
        assertFalse(facts.any { it.label=="PT (patient)" || it.label=="aPTT (patient)" })
    }

    @Test fun marrowBiopsyAndAspirateDoNotOverwriteEachOther() {
        val aspirate="""
            BONE MARROW ASPIRATION
            Type of Specimen : Peripheral Smear & Bone Marrow Aspirate
            Aspirate: Particulate
            Cellularity: Hypercellular
            Erythropoiesis : Normoblastic
            Megakaryocytes : Increased in number with normal morphology
            M E Ratio : 3:1
            Impression / Diagnosis : Reactive marrow with increased megakaryocytes.
        """.trimIndent()
        val biopsy="""
            BONE MARROW BIOPSY
            Adequacy : Adequate biopsy
            Bony Trabeculae : Normal
            Cellularity : 55%
            Megakaryocytes : 4-5/HPF
            Reticulin Stain : Grade I condensation.
            Impression / Diagnosis : Features consistent with aspiration findings.
        """.trimIndent()
        assertEquals("Hypercellular",VerifiedPdfResults.extract(
            "Bone marrow aspiration with procedure",aspirate)
            .single { it.label=="Cellularity" }.result)
        assertEquals("55%",VerifiedPdfResults.extract(
            "Bone Marrow Biopsy",biopsy).single { it.label=="Cellularity" }.result)
        assertTrue(VerifiedPdfResults.extract("Bone Marrow Biopsy",aspirate).isEmpty())
    }

    @Test fun culturesNeverBorrowUrineOrWoundPdf() {
        val rows=listOf(LabResult(id="1",test="Blood culture Set 1 Bottle 2",
            parameter="Result",valueText="No growth after 48 h",date="2026-10-23"))
        val source=listOf(
            ReportRef(id="u",title="Urine Aerobic Culture",labNumber="U221",
                collectionDateTime="2026-10-23",url="https://hospital.example/u.pdf"),
            ReportRef(id="b",title="Fan Blood Culture (1 set 2 bottle) 48 hrs",
                labNumber="B221",collectionDateTime="2026-10-23",
                url="https://hospital.example/b.pdf"),
            ReportRef(id="e",title="Aerobic Culture",labNumber="E221",
                collectionDateTime="2026-10-23",url="https://hospital.example/e.pdf"))
        assertEquals(listOf("B221"),SourceMetadataReview.pdfCandidates(rows,source)
            .map { it.labNumber })
    }

    @Test fun anticardiolipinAndBeta2GpiAreSeparateMeasurements() {
        val text="""
          Anticardiolipin antibodies(IgG)-Negative: 5 GPL Units (<10 GPL units-Negative)
          Anticardiolipin antibodies(IgM)-Negative: 6 MPL Units (<15 MPL units-Negative)
          Anti -beta-2 Glycoprotein -1 antibodies IgG - Negative (ELISA) - 1 U/ml (<7 U/ml-Negative)
          Anti -beta-2 Glycoprotein -1 antibodies IgM - Negative (ELISA) - 1 U/ml (<7 U/ml-Negative)
        """.trimIndent()
        val values=SerologyReportDisplay.extract("Antiphospholipid antibodies",text)
        assertEquals(4,values.size)
        assertEquals(listOf("5 GPL","6 MPL","1 U/ml","1 U/ml"),
            values.map { it.testValue })
        assertTrue(values.all { it.result == "Negative" })
    }

    @Test fun structuredSourceCodesCannotDisplayAsMarrowValues() {
        val row=LabResult(id="code",test="BONE MARROW ASPIRATION",
            date="2026-10-24",parameter="Cellularity",valueText="6")
        assertTrue(ClinicalResultsLayout.suppressStructuredArtifact(row))
        assertFalse(ClinicalResultsLayout.suppressStructuredArtifact(
            row.copy(valueText="Hypercellular")))
        assertFalse(ClinicalResultsLayout.omitRoutineHeading(
            "CRP",listOf(row.copy(test="CRP",valueText="<6"))))
    }
}
