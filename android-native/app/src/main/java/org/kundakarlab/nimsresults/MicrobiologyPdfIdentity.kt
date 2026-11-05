package org.kundakarlab.nimsresults

import java.time.LocalDate
import java.util.Locale

/**
 * Display-only source reconciliation. A same-day index candidate is NOT a
 * patient result. Never attach a PDF unless its CR is verified and the
 * report event is distinguished from other specimens/stages.
 */
object MicrobiologyPdfIdentity {
    data class Matched(val report: ReportRef, val header: PdfDocumentHeader,
                       val specimen: String, val discordant: Boolean)

    private fun norm(input: String): String = input.lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9]+"""), " ").trim()
        .replace(Regex("""\s+"""), " ")

    fun displayTitle(test: String, rows: List<LabResult>): String {
        val narrative = rows.joinToString(" ") { it.valueText }.take(500)
        if (Regex("""(?i)gram(?:s)?\s+stain(?:ing)?""").containsMatchIn(test) &&
            Regex("""(?i)\bgiemsa\s+smear\b""").containsMatchIn(narrative))
            return "GIEMSA SMEAR"
        return test
    }

    /**
     * A PDF can contain irreconcilable results (as in the NIMS urine
     * culture with both "no significant bacteriuria" and E. coli growth).
     * Flag the original contradiction; do not silently classify it.
     */
    fun contradictoryCulture(text: String): Boolean {
        if (!Regex("""(?i)\bculture\b""").containsMatchIn(text)) return false
        val absent = Regex("""(?i)\b(?:no\s+significant\s+bacteriuria|no\s+growth|sterile\s+culture)\b""")
            .containsMatchIn(text)
        val growth = Regex("""(?i)\bculture\s+shows\s+growth\b|\bgrowth\s+of\s+(?:escherichia|klebsiella|pseudomonas|enterococcus|staphylococcus|acinetobacter)\b""")
            .containsMatchIn(text)
        return absent && growth
    }

    private fun compatible(test: String, sourceTitle: String,
                           sourceText: String): Boolean {
        val t=norm(test)
        val title=norm(sourceTitle)
        val body=norm(sourceText)
        if("culture" in t) {
            if("culture" !in title && "culture" !in body)return false
            val urine="urine" in t
            val sourceUrine="urine" in title ||
                Regex("""(?i)\burine\s+aerobic\s+culture\b""")
                    .containsMatchIn(sourceText)
            if(urine!=sourceUrine)return false
            if("blood" in t && "urine" in title)return false
            if("fungal" in t && "urine" in title)return false
            if("blood" !in t && "urine" !in t && "blood" in title)return false
            if("fungal" !in t && "fungal" in title)return false
            return true
        }
        // Distinguish fungal KOH, fungal fluorescent staining, AFB
        // fluorescence and Gram/Giemsa. The generic old "staining" rule
        // accidentally rejected EVERY fungal source title.
        val isKoh=Regex("""(?i)\bkoh\b""").containsMatchIn(test)
        val isCalco=Regex("""(?i)calcofluor|calcoflour|fungal\s+fluorescen""")
            .containsMatchIn(test)
        val isFungal=isKoh || isCalco || "fungal" in t
        val isAfb=Regex("""(?i)\bafb\b|acid[\s-]*fast|tb\s+fluorescen|auramine""")
            .containsMatchIn(test)
        val candidateCulture="culture" in title
        if(candidateCulture)return false
        if(isKoh) return "koh" in title || ("stain" in title && "koh" in body)
        if(isCalco)return "calcofluor" in title || "calcoflour" in title ||
            "fungal fluorescence" in title ||
            ("stain" in title && "calcofluor" in body)
        if(isFungal)return "fungal" in title && "koh" !in title &&
            "culture" !in title
        if(isAfb)return "afb" in title || "tb fluorescence" in title ||
            "acid fast" in title || "auramine" in title ||
            ("stain" in title && ("acid fast" in body || "auramine" in body))
        if(Regex("""(?i)gram|giemsa|stain|smear""").containsMatchIn(test)) {
            if(Regex("""(?i)\b(?:fungal|calcofluor|afb|koh|acid\s+fast)\b""")
                    .containsMatchIn(sourceTitle))return false
            return "stain" in title || "smear" in title ||
                "giemsa" in title || "giemsa smear" in body ||
                "gram smear" in body || "grams smear" in body
        }
        return t==title
    }

    /**
     * Source identity is an event, not a title or a collection date.
     * Resolve only after CR, report day, accession (when supplied),
     * culture set/bottle/stage, specimen and patient-result evidence agree.
     * Same-day identical results without bottle or specimen identity are
     * intentionally ambiguous: return null, retain reviewable candidates.
     */
    fun unique(rows: List<LabResult>, candidates: List<ReportRef>,
               patientCr: String, day: LocalDate): Matched? {
        if(rows.isEmpty() || patientCr.isBlank() ||
            candidates.isEmpty() || candidates.size>20)return null
        val test=rows.first().test
        val labs=(rows.map { it.labNumber.trim() }+
            rows.mapNotNull { MicrobiologyNarrativeMetadata.extract(it)?.labNumber })
            .filter(String::isNotBlank).distinct()
        if(labs.size>1)return null
        val lab=labs.singleOrNull().orEmpty()
        val reportDates=rows.mapNotNull { RecentReportDates.parse(it.reportedDate) }
            .distinct()
        if(reportDates.size>1)return null
        val eventDate=reportDates.singleOrNull() ?: day
        val matches=mutableListOf<Matched>()
        for(report in candidates.distinctBy { it.url }) {
            val verified=ReportTitleCache.compactEvidence(report.url) ?:continue
            if(verified.cr!=patientCr)continue
            val header=verified.header
            if(header.crNo!=patientCr)continue
            if(report.labNumber.isNotBlank() &&
                !report.labNumber.equals(header.labNumber,true))continue
            if(lab.isNotBlank() && !lab.equals(header.labNumber,true))continue
            val source=ReportTitleCache.matchingText(report.url)
            if(source.isBlank() || !compatible(test,report.title,source))continue
            if(!cultureIdentityAgrees(test,report.title,source,report.reportStage))
                continue
            val reportDate=RecentReportDates.parse(header.reportedDate)
                ?:RecentReportDates.parse(report.reportedDate)
            if(reportDate==null || reportDate!=eventDate)continue
            val specimen=verified.specimen.ifBlank {
                PdfSourceEvidence.specimen(source) }
            if(!specimenAgrees(test,rows,specimen,report.title))continue
            if(!clinicalContentAgrees(rows,source))continue
            matches+=Matched(report,header,specimen,
                contradictoryCulture(source))
        }
        return matches.singleOrNull()
    }

    private fun specimenAgrees(test:String,rows:List<LabResult>,
        sourceSpecimen:String,sourceTitle:String):Boolean {
        val rowSpecimens=rows.map { it.specimenType.trim() }
            .filter { it.isNotBlank() &&
                !it.equals("any other specimen",true) &&
                !it.equals("other",true) }.distinct()
        if(rowSpecimens.isEmpty() || sourceSpecimen.isBlank())return true
        if(rowSpecimens.size>1)return false
        fun category(text:String):String {
            val x=norm(text)
            return when {
                Regex("""\burine\b|\bclean\s*catch\b|\bmidstream\b""")
                    .containsMatchIn(x) -> "urine"
                "bone marrow" in x || "marrow" in x -> "marrow"
                "csf" in x || "cerebrospinal" in x -> "csf"
                "blood" in x || "bottle" in x -> "blood"
                "pleural" in x -> "pleural"
                "ascitic" in x -> "ascitic"
                "sputum" in x -> "sputum"
                else -> ""
            }
        }
        val rowCat=category(rowSpecimens.single())
        val sourceCat=category(sourceSpecimen).ifBlank {
            category(sourceTitle)
        }
        if(rowCat.isNotBlank() && sourceCat.isNotBlank())
            return rowCat==sourceCat
        return norm(rowSpecimens.single())==norm(sourceSpecimen)
    }

    /** "Set 1 Bottle 2" and "1 set 2 bottle" are the same identity.
     * A final report may use "after 5 days" instead of literal FINAL.
     */
    private fun cultureIdentityAgrees(target:String,title:String,
        source:String,listedStage:String):Boolean {
        val t=norm(target)
        if(!t.contains("culture"))return true
        val names=title+" "+source.lineSequence().take(16).joinToString(" ")
        fun ordinal(value:String,kind:String):String? {
            val after=Regex("""(?i)\b"""+kind+"""\s*[:#-]?\s*(\d+)\b""")
                .find(value)?.groupValues?.get(1)
            val before=Regex("""(?i)\b(\d+)\s*"""+kind+"""\b""")
                .find(value)?.groupValues?.get(1)
            val prefix=Regex("""(?i)\b\d+\s*set\b""").containsMatchIn(value)
            return if(prefix) before?:after else after?:before
        }
        for(k in listOf("set","bottle")) {
            val x=ordinal(target,k)
            val y=ordinal(names,k)
            if(x!=null && y!=null && x!=y)return false
            // Blood cultures sharing accession/day/results cannot be
            // differentiated when PDF/index lacks required bottle identity.
            if(x!=null && y==null)return false
        }
        val targetInterim=Regex("""(?i)\b(?:48\s*h|48\s*hrs?|interim|preliminary)\b""")
            .containsMatchIn(target)
        val targetFinal=Regex("""(?i)\bfinal\b""").containsMatchIn(target)
        val sourceInterim=Regex("""(?i)\b(?:48\s*h|48\s*hrs?|interim|preliminary)\b""")
            .containsMatchIn(title+" "+listedStage) ||
            Regex("""(?i)\b48\s*hours?\b""")
                .containsMatchIn(source.lineSequence().take(26).joinToString(" "))
        val sourceFinal=Regex("""(?i)\bfinal\b""")
            .containsMatchIn(title+" "+listedStage)
        if(targetFinal && sourceInterim && !sourceFinal)return false
        if(targetInterim && sourceFinal && !sourceInterim)return false
        return true
    }

    private fun clinicalContentAgrees(rows:List<LabResult>,
        source:String):Boolean {
        val body=norm(source)
        if(body.isBlank())return false
        val observations=rows.map { it.valueText.trim() }
            .filter { it.length>=15 &&
                !it.equals("-1") &&
                !it.contains("methodology:",true) }.distinct()
        if(observations.isEmpty())return false
        fun words(s:String):Set<String> = norm(s).split(' ')
            .filter { it.length>=3 &&
                it !in setOf("the","and","for","after","with","report",
                    "results","culture","shows","patient","sample") }.toSet()
        return observations.any { observation ->
            val clean=norm(observation)
            if(body.contains(clean))return@any true
            // NIMS reports may differ in punctuation/line wrapping.
            // A high content overlap plus polarity agreement is acceptable,
            // but date/title alone never proves a matching source.
            val target=words(observation)
            if(target.size<5)return@any false
            val present=target.count { it in words(body) }
            if(present.toFloat()/target.size<0.84f)return@any false
            fun polarity(text:String):String = when {
                Regex("""(?i)\b(?:no\s+(?:significant\s+)?growth|no\s+growth|negative|not\s+detected|not\s+seen)\b""")
                    .containsMatchIn(text) -> "negative"
                Regex("""(?i)\b(?:growth\s+of|positive|detected|seen)\b""")
                    .containsMatchIn(text) -> "positive"
                else -> ""
            }
            val x=polarity(observation)
            val y=polarity(source)
            x.isBlank() || y.isBlank() || x==y ||
                contradictoryCulture(source) // report genuinely contradictory
        }
    }

    /** Evidence-only accession list for direct PDF controls. */
    fun accession(rows: List<LabResult>): String =
        rows.map { it.labNumber.trim() }.filter(String::isNotBlank)
            .distinct().singleOrNull().orEmpty()
}
