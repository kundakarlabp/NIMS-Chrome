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

    fun urineCulture(text:String):List<SourceSection> {
        val source=text.trim()
        val smear=Regex("""(?i)\bGRAM(?:S|['’]S)?\s+(?:SMEAR|STAIN(?:ING)?)\s+SHOWS\b""")
            .find(source)
        val culture=Regex("""(?i)\bCULTURE\s+SHOWS\b""").find(source)
            ?:return listOf(SourceSection("Finding",source))
        val advice=Regex("""(?i)\b(?:PLEASE\s+REPEAT|RECOMMENDATIONS?\s*:|COMMENTS?\s*:)""")
            .find(source.substring(culture.range.first))?.let{
                culture.range.first+it.range.first
            }
        val sections=mutableListOf<SourceSection>()
        if(smear!=null && smear.range.first<culture.range.first)
            sections+=SourceSection("Gram smear",
                source.substring(smear.range.first,culture.range.first).trim())
        sections+=SourceSection("Culture",
            source.substring(culture.range.first,advice?:source.length).trim())
        if(advice!=null)sections+=SourceSection("Laboratory note",
            source.substring(advice).trim())
        return sections
    }
    fun isStain(test:String):Boolean =
        Regex("""(?i)(?:gram(?:s)?\s*stain|gram\s*smear|fungal|calcofluor|koh|afb\s*stain|acid[\s-]*fast|fluorescen(?:ce|t)\s*stain)""")
            .containsMatchIn(test)

    fun redundantNarrativeLabel(label:String,test:String):Boolean {
        val normal={ value:String -> value.lowercase().replace(Regex("[^a-z0-9]"),"") }
        val p=normal(label);val t=normal(test)
        return p=="result" || (p.isNotEmpty() && (p==t ||
            (t.contains(p) && p.length>=7)))
    }

}