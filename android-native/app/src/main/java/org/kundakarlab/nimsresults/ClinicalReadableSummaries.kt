package org.kundakarlab.nimsresults

/** Formatting-only helpers. Source values remain unchanged. */
object ClinicalReadableSummaries {
    fun compactValue(row: LabResult): String {
        val value=row.valueText.trim()
        val lt=Regex("""(?i)^less\\s+than\\s+(\\d+(?:\\.\\d+)?)$""").matchEntire(value)
        val gt=Regex("""(?i)^greater\\s+than\\s+(\\d+(?:\\.\\d+)?)$""").matchEntire(value)
        val display=if(lt!=null) "<"+lt.groupValues[1]
            else if(gt!=null) ">"+gt.groupValues[1] else row.valueText
        return display+row.unit.takeIf { it.isNotBlank() &&
            !display.endsWith(it,true) }?.let { " "+it }.orEmpty()
    }

    fun usefulReference(source:String):String {
        val ref=source.trim()
        return if(ref.any(Char::isDigit) ||
            ref.contains("<") || ref.contains(">") ||
            ref.contains("negative",true) || ref.contains("positive",true))
            ref else ""
    }
}