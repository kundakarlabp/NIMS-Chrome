package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

/** Synthetic fixtures only; no patient identifiers or real PDF contents. */
class SourceIntegrityV121Test {
    private val cr = "111111111111111"

    private fun bundle(vararg rows: LabResult): ClinicalBundle =
        ClinicalBundle(
            PatientInfo(name = "Synthetic", crNo = cr),
            rows.toList(),
            emptyList()
        )

    private fun result() =
        LabResult("source-1", "CBC", "Platelet Count", "2026-10-26", "50")

    @Test fun repeatedIdenticalRowsInSingleBatchAreNotSilentlyLost() {
        val first = BulkAccumulator.append(
            null, bundle(result(), result().copy(id = "source-2")), 1
        )
        assertEquals(2, first.results.size)
        assertEquals(2, first.results.map { it.id }.distinct().size)
    }

    @Test fun retriedWindowSkipsPreviouslyAcceptedRepeatedRows() {
        val first = BulkAccumulator.append(
            null, bundle(result(), result().copy(id = "source-2")), 1
        )
        val retried = BulkAccumulator.append(
            first, bundle(result(), result().copy(id = "source-2")), 2
        )
        assertEquals(2, retried.results.size)
    }

    @Test fun extraRepeatedOccurrenceInOlderWindowRemainsVisible() {
        val first = BulkAccumulator.append(null, bundle(result()), 1)
        val second = BulkAccumulator.append(
            first, bundle(result(), result().copy(id = "source-2")), 2
        )
        assertEquals(2, second.results.size)
        assertEquals(2, second.results.map { it.id }.distinct().size)
    }

    @Test fun radiologyNarrativeMentioningCreatinineIsStillRadiology() {
        assertEquals(
            PdfStudyKind.RADIOLOGY,
            PdfSourceEvidence.kind(
                "DEPARTMENT OF RADIO DIAGNOSIS\nCT ABDOMEN REPORT\n" +
                "Serum creatinine was evaluated before intravenous contrast."
            )
        )
    }

    @Test fun nuclearMedicineNarrativeMentioningLftsRemainsImaging() {
        assertEquals(
            PdfStudyKind.RADIOLOGY,
            PdfSourceEvidence.kind(
                "DEPARTMENT OF NUCLEAR MEDICINE\n" +
                "PET-CT findings with incidental liver function test results."
            )
        )
    }

    @Test fun biofirePanelMentioningCalciumRemainsMolecular() {
        assertEquals(
            PdfStudyKind.MOLECULAR,
            PdfSourceEvidence.kind(
                "BIOFIRE FILMARRAY MENINGITIS PANEL\n" +
                "Clinical note: serum calcium available."
            )
        )
    }

    @Test fun bloodBankReportMentioningChemistryRemainsBloodBank() {
        assertEquals(
            PdfStudyKind.BLOOD_BANK,
            PdfSourceEvidence.kind(
                "BLOOD GROUPING AND ANTIBODY SCREENING\n" +
                "Additional bilirubin level was mentioned."
            )
        )
    }

    @Test fun trueChemistryPdfIsStillChemistry() {
        assertEquals(
            PdfStudyKind.CHEMISTRY,
            PdfSourceEvidence.kind(
                "ROUTINE CHEMISTRY\nLIVER FUNCTION TESTS\nAlbumin 3.2"
            )
        )
    }
}
