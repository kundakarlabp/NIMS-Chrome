package org.kundakarlab.nimsresults

/**
 * A culture/stain narrative may contain its own labelled specimen provenance.
 * Extract only explicit header labels from the same structured observation.
 * This is descriptive evidence from the HIS value, not proof of a PDF match,
 * and must never overwrite LabResult's independently supplied identity.
 */
data class MicrobiologyNarrativeFields(
    val labNumber: String,
    val sampleNumber: String,
    val collectionDate: String,
    val sentDate: String,
    val reportedDate: String
) {
    fun hasFields() = listOf(labNumber, sampleNumber, collectionDate,
        sentDate, reportedDate).any(String::isNotBlank)
    fun display(): String = listOfNotNull(
        labNumber.takeIf(String::isNotBlank)?.let { "Lab/Study " + it },
        sampleNumber.takeIf(String::isNotBlank)?.let { "Sample " + it },
        collectionDate.takeIf(String::isNotBlank)?.let { "Collected " + it },
        sentDate.takeIf(String::isNotBlank)?.let { "Sent " + it },
        reportedDate.takeIf(String::isNotBlank)?.let { "Reported " + it }
    ).joinToString(" · ")
}

object MicrobiologyNarrativeMetadata {
    fun extract(row: LabResult): MicrobiologyNarrativeFields? {
        if (!ClinicalReportRenderer.isMicrobiologyInvestigation(row.test)) return null
        // Avoid treating an old specimen ID mentioned later in the comments
        // as the current source header.
        val first = row.valueText.replace("\r\n","\n").take(1800)
        val lines = first.lines()
        val header = lines.take(18).joinToString("\n")
        val pdf = PdfDocumentHeaders.parse(header)
        val prefixLab = Regex(
            """(?im)^\s*([A-Z]{1,3}\d{4,8})\s+(?:(?:FAN\s+)?(?:BLOOD\s+|AEROBIC\s+|FUNGAL\s+)?CULTURE\s+REPORT)\b"""
        ).find(header)?.groupValues?.getOrNull(1).orEmpty()
        val value = MicrobiologyNarrativeFields(
            labNumber = pdf.labNumber.ifBlank { prefixLab },
            sampleNumber = pdf.sampleNumber,
            collectionDate = pdf.collectionDate,
            sentDate = pdf.sentDate,
            reportedDate = pdf.reportedDate
        )
        return value.takeIf { it.hasFields() }
    }
}
