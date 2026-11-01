package org.kundakarlab.nimsresults

/**
 * A named result in a patient-verified pathology PDF may supersede an opaque
 * HIS dropdown integer; never translate 1/2 into positive/negative.
 */
object PdfParasiteSmears {
    private val value=Regex(
        """(?i)^(negative|positive|not\s+detected|detected|no\s+parasites?\s+seen)$"""
    )
    fun extract(test:String,document:String):String? {
        if(document.isBlank()) return null
        val label=when {
            Regex("""(?i)microfilaria|micro\s*filaria""").containsMatchIn(test) ->
                """(?:smear\s+for\s+microfilaria|peripheral\s+smear\s+for\s+microfilaria)"""
            Regex("""(?i)strip\s+test.*(?:\bPV\b|\(PV\))""").containsMatchIn(test) ->
                """strip\s+test\s+for\s+(?:malaria\s*)?\(?PV\)?"""
            Regex("""(?i)strip\s+test.*(?:\bPF\b|\(PF\))""").containsMatchIn(test) ->
                """strip\s+test\s+for\s+(?:(?:malaria|MP)\s*)?\(?PF\)?"""
            Regex("""(?i)malaria|smear\s+for\s+MP""").containsMatchIn(test) ->
                """smear\s+for\s+(?:MP|malaria)"""
            else -> return null
        }
        val lines=document.replace("\r\n","\n").lines()
            .map { it.trim().replace(Regex("""\s*\|\s*""")," ")
                .replace(Regex("""\s+""")," ") }.filter(String::isNotBlank)
        val matching=Regex("""(?i)^"""+label+"""\s*[:=-]?\s*(.*)$""")
        val found=mutableListOf<String>()
        for((i,line) in lines.withIndex()) {
            val match=matching.find(line) ?: continue
            val suffix=match.groupValues[1].trim()
            val exact=value.matchEntire(suffix)?.groupValues?.get(1)
            val next=lines.getOrNull(i+1)?.let { value.matchEntire(it)?.groupValues?.get(1) }
            if(exact!=null) found+=exact
            else if(suffix.isBlank() && next!=null) found+=next
        }
        return found.map { it.lowercase().replaceFirstChar(Char::uppercase) }
            .distinct().singleOrNull()
    }
}
