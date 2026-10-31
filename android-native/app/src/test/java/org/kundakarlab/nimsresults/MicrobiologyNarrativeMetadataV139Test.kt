package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class MicrobiologyNarrativeMetadataV139Test {
    private fun lab(test:String,value:String) = LabResult(
        id="r1", test=test, parameter="Result", date="2026-10-31",
        valueText=value)

    @Test fun explicitCultureHeaderProvidesEachIndependentDate() {
        val value="""Lab/Study No: B26823
Sample No: BOTTLE001
Collection Date: 29-Oct-2026 09:10
Date Sent: 29-Oct-2026 11:00
Reported Date: 31-Oct-2026 18:12
BLOOD CULTURE REPORT
Growth of Klebsiella pneumoniae"""
        val meta=MicrobiologyNarrativeMetadata.extract(lab("Blood culture",value))
        assertEquals("B26823",meta?.labNumber)
        assertEquals("BOTTLE001",meta?.sampleNumber)
        assertEquals("29-Oct-2026 09:10",meta?.collectionDate)
        assertEquals("29-Oct-2026 11:00",meta?.sentDate)
        assertEquals("31-Oct-2026 18:12",meta?.reportedDate)
    }

    @Test fun prefixAccessionIsMarkedAsSourceOnlyWhenLabelUnavailable() {
        val m=MicrobiologyNarrativeMetadata.extract(lab("Aerobic culture",
            "E9855 CULTURE REPORT\nNo growth after 48 h"))
        assertEquals("E9855",m?.labNumber)
        assertEquals("",m?.collectionDate)
    }

    @Test fun noInferenceFromUnrelatedNarrativeOrPastCultureReferences() {
        assertNull(MicrobiologyNarrativeMetadata.extract(lab("CT chest",
            "Historical blood culture Lab No: E9855")))
        assertNull(MicrobiologyNarrativeMetadata.extract(lab("Blood culture",
            "No growth. Prior lab sample S001")) )
    }
}
