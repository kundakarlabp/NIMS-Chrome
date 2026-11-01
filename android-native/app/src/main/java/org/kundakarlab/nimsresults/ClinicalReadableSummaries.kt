package org.kundakarlab.nimsresults

/** Formatting-only helpers. Source values remain unchanged. */
object ClinicalReadableSummaries {
    fun compactValue(row: LabResult): String {
        val value=row.valueText.trim()
        val lt=Regex("""(?i)^less\s+than\s+(\d+(?:\.\d+)?)$""").matchEntire(value)
        val gt=Regex("""(?i)^greater\s+than\s+(\d+(?:\.\d+)?)$""").matchEntire(value)
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
    data class Summary(val finding:String,val comment:String="")
    fun crp(test:String,source:String):Summary? {
        if(!Regex("""(?i)\bcrp\b|c[\s-]*reactive\s+protein""").containsMatchIn(test))
            return null
        val m=Regex("""(?i)([<>≤≥])\s*(\d+(?:\.\d+)?)\s*(mg\s*/\s*(?:1000\s*ml|l|dl))""")
            .find(source) ?: return null
        val unit=m.groupValues[3].replace(Regex("""\s+"""),"")
        val prefix=source.substringBefore("(")
        val qualitative=Regex("""(?i)\b(?:negative|positive)\b""")
            .find(prefix)?.value?.lowercase()?.replaceFirstChar { it.uppercase() }
        return Summary(m.groupValues[1]+m.groupValues[2]+" "+unit+
            qualitative?.let { " · "+it }.orEmpty())
    }

    fun hiv(test:String,source:String):Summary? {
        if(!test.contains("HIV",true)) return null
        val commentAt=Regex("""(?i)\bcomment\s*:""").find(source)
        val pre=commentAt?.let { source.take(it.range.first) } ?: source
        val finding=Regex("""(?i)\b(?:non[\s-]*reactive|reactive|negative|positive|indeterminate)\b""")
            .find(pre)?.value?.lowercase()?.replace(Regex("""\s+"""),"-")
            ?.replaceFirstChar { it.uppercase() } ?: return null
        val method=if(source.contains("ELFA",true)) "ELFA"
            else if(source.contains("ELISA",true)) "ELISA" else "assay"
        val target=if(source.contains("Ag/Ab",true) || source.contains("p24",true))
            "HIV Ag/Ab" else "HIV"
        return Summary(target+" ("+method+") · "+finding,
            commentAt?.let { source.drop(it.range.last+1).trim() }.orEmpty())
    }

}