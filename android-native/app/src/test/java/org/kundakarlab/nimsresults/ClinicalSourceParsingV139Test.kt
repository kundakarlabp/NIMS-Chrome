package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class ClinicalSourceParsingV139Test {
    @Test fun preservesSeparateLabSampleCollectionSentAndReportedFields() {
        val text = """
            CR No : 331012601170629
            Lab / Study No.: B26823
            Sample No: S102344
            Collection Date: 23-Oct-2026 09:20
            Date Sent: 23-Oct-2026 09:43
            Reported Date: 24-Oct-2026 15:05
            Requisition Date: 22-Oct-2026 19:00
            AEROBIC BLOOD CULTURE
        """.trimIndent()
        val header = PdfDocumentHeaders.parse(text)
        assertEquals("331012601170629", header.crNo)
        assertEquals("B26823", header.labNumber)
        assertEquals("S102344", header.sampleNumber)
        assertEquals("23-Oct-2026 09:20", header.collectionDate)
        assertEquals("23-Oct-2026 09:43", header.sentDate)
        assertEquals("24-Oct-2026 15:05", header.reportedDate)
        assertEquals("22-Oct-2026 19:00", header.orderDate)
    }

    @Test fun dateSentAloneNeverBecomesCollectionDate() {
        val h = PdfDocumentHeaders.parse("CR No: 331012601170629\nLab No: E9855\nDate Sent: 20-Oct-2026\nReport Date: 23-Oct-2026")
        assertEquals("",h.collectionDate)
        assertEquals("20-Oct-2026",h.sentDate)
        assertEquals("23-Oct-2026",h.reportedDate)
        assertEquals("E9855",h.labNumber)
    }

    @Test fun separateKindsForMicrobiologyStainsAndCultures() {
        assertEquals(PdfStudyKind.GRAM_STAIN, PdfSourceEvidence.kind("Gram staining\nMany WBCs"))
        assertEquals(PdfStudyKind.FUNGAL_STAIN,PdfSourceEvidence.kind("Fungal stain\nNo fungal elements"))
        assertEquals(PdfStudyKind.AFB_STAIN,PdfSourceEvidence.kind("AFB stain\nNo acid fast bacilli"))
        assertEquals(PdfStudyKind.CULTURE,PdfSourceEvidence.kind("Aerobic culture\nGrowth of isolate"))
        assertEquals(PdfStudyKind.CULTURE,PdfSourceEvidence.kind("Culture Final Report\nNo growth"))
    }

    @Test fun repeatedAccessionButDifferentSampleDoesNotMergeSourceParts() {
        val pages = listOf(
            "CR No: 331012601170629\nLab No: E9855\nSample No: SAM101\nGram staining\nGram negative bacilli",
            "CR No: 331012601170629\nLab No: E9855\nSample No: SAM102\nGram staining\nNo bacteria seen"
        )
        val parts = PdfSourceEvidence.parts(pages,"331012601170629")
        assertEquals(2,parts.size)
        assertEquals(listOf("SAM101","SAM102"),parts.map{it.sampleNumber})
    }

    @Test fun sameAccessionGramAndCultureRemainDifferentReports() {
        val parts=PdfSourceEvidence.parts(listOf(
            "CR No: 331012601170629\nLab No: E9855\nSample No: SAM101\nGram staining\nFew gram negative bacilli",
            "CR No: 331012601170629\nLab No: E9855\nSample No: SAM101\nAerobic Culture\nGrowth"
        ),"331012601170629")
        assertEquals(2,parts.size)
        assertEquals(listOf(PdfStudyKind.GRAM_STAIN,PdfStudyKind.CULTURE),parts.map{it.kind})
    }

    @Test fun biofireOnlyNamedTargetsNotOpaqueLISControls() {
        val text = """
            FilmArray BioFire panel
            Escherichia coli K1 | Detected
            Streptococcus pneumoniae | Not detected
            combo2 | 1
            cb30: Detected
            Internal Control | Positive
            Result: 1
        """.trimIndent()
        val cells=SourceReportTables.biofire(text)
        assertEquals(listOf("Escherichia coli K1","Streptococcus pneumoniae"),cells.map{it.label})
        assertEquals(listOf("Detected","Not detected"),cells.map{it.value})
    }

    @Test fun bgtPreservesForwardReverseRhAndAntibodyRowsWithoutGuessingBloodGroup() {
        val text = """
            BLOOD GROUPING AND ANTIBODY SCREEN
            Anti-A | 0
            Anti-B | 4+
            Anti-D | Positive
            A1 Cells | 4+ | 0
            B Cells | 0
            Method | Gel card
            Control | 0
            Antibody screening | Negative
            combo2 | 2
            cb30 | 1
        """.trimIndent()
        val cells=SourceReportTables.bloodGrouping(text)
        assertEquals(listOf("Anti-A","Anti-B","Anti-D","A1 Cells","B Cells","Method","Control","Antibody screening"),
            cells.map{it.label})
        assertEquals("4+ 0",cells.first{it.label=="A1 Cells"}.value)
        assertNull(PdfClinicalSummaries.bloodGrouping(text))
    }

    @Test fun explicitSignedConclusionIsSeparateFromReactionControls() {
        val text = "Blood grouping and antibody screening\nFinal Result: B Positive\nAntibody screening: Negative"
        val interpretation=PdfClinicalSummaries.bloodGrouping(text)
        assertEquals("B Positive",interpretation?.group)
        assertEquals("Negative",interpretation?.antibodyScreen)
    }
}
