package org.kundakarlab.nimsresults

import java.util.Locale

/**
 * Patient-report display from an independently CR/accession-verified PDF.
 * No lookup-table row, machine-code value or generic assay explanation is a
 * patient measurement. The original PDF remains the source of truth.
 */
data class VerifiedPdfItem(val label: String, val result: String)

object VerifiedPdfResults {
    private fun clean(s: String): String = s.replace(Regex("""[ \t]+"""), " ").trim()
    private fun key(s: String): String = s.lowercase(Locale.ROOT)
        .replace(Regex("""[^a-z0-9]"""), "")

    private fun lines(s: String): String =
        s.replace("\r\n", "\n").replace('\r', '\n')

    private fun value(s: String, label: String, pattern: String): String? {
        // Match only an explicitly named value, not a general reference table.
        val re = Regex(pattern, RegexOption.IGNORE_CASE)
        val found = re.findAll(s).map { clean(it.groupValues[1]) }
            .filter(String::isNotBlank).distinct().take(3).toList()
        return found.singleOrNull()
    }

    private fun item(s: String, label: String, pattern: String): VerifiedPdfItem? =
        value(s, label, pattern)?.let { VerifiedPdfItem(label, it) }

    private fun patientPcr(test: String, source: String): List<VerifiedPdfItem> {
        val t = key(test)
        val pathogen = when {
            "cmv" in t || "cytomegalovirus" in t -> "CMV DNA"
            "ebv" in t || "epsteinbarr" in t -> "EBV DNA"
            "parvo" in t -> "Parvovirus B19 DNA"
            else -> return emptyList()
        }
        // NIMS puts the measured result ahead of the method and its
        // interpretation reference table. Exclude both, even when the latter
        // contains "Target Not Detected" and multiple concentration thresholds.
        val first = listOf("methodology", "interpretation", "biological reference interval")
            .mapNotNull { marker ->
                Regex(Regex.escape(marker),RegexOption.IGNORE_CASE)
                    .find(source)?.range?.first
            }.minOrNull() ?: source.length
        val patient = source.take(first).takeLast(1200)
        val label = Regex(
            when (pathogen) {
                "CMV DNA" -> """(?i)CMV\s*(?:DNA)?\s*[-–]?\s*(?:quantitative|viral\s*load|PCR)?"""
                "EBV DNA" -> """(?i)EBV\s*(?:virus|DNA)?\s*[-–]?\s*(?:PCR|quantitative|viral\s*load)?"""
                else -> """(?i)(?:human\s*)?parvo\s*virus\s*B\s*19"""
            }
        )
        val start = label.find(patient)?.range?.first ?: return emptyList()
        val scope = patient.substring(start).take(350)
        val explicit = Regex(
            """(?i)(?:target\s+not\s+detected|not\s+detected|target\s+detected|detected\s+(?:below|above)\s+(?:the\s+)?(?:linear|quantification|detection)\s+(?:range|limit)|[<>]?\s*[\d,]+(?:\.\d+)?\s*(?:IU|copies)\s*/\s*mL)"""
        ).find(scope)?.value?.let(::clean) ?: return emptyList()
        val normalized=when {
            explicit.equals("target not detected",true) || explicit.equals("not detected",true) ->
                "Target Not Detected"
            explicit.equals("target detected",true) -> "Target Detected"
            else -> explicit
        }
        return listOf(VerifiedPdfItem(pathogen, normalized))
    }

    private fun fibrinogen(source: String): List<VerifiedPdfItem> =
        listOfNotNull(item(source,"Fibrinogen",
            """(?i)\bfibrinogen\s*[:\-]?\s*([\d,.]+\s*(?:mg\s*/\s*d[lL]|g\s*/\s*[lL]))\b"""))

    private fun molecularOrIgRa(test: String, source: String): List<VerifiedPdfItem> {
        val t=key(test)
        if ("cmv" in t || "ebv" in t || "parvo" in t)
            return patientPcr(test,source)
        if ("igra" !in t && "quantiferon" !in t && "tb gold" !in test.lowercase())
            return emptyList()
        val marker=Regex("""(?i)\bINTERPRETATION\s*:""").find(source)?.range?.first
            ?: source.length
        val patient=source.take(marker).takeLast(1200)
        val final=Regex("""(?is)\bfinal\s+result\b.{0,250}?\b(POSITIVE|NEGATIVE|INDETERMINATE)\b""")
            .find(patient)?.groupValues?.get(1)
            ?: Regex("""(?i)\b(?:QFT[\s-]*plus\s+result|IGRA\s+result)\s*[:\-]?\s*(POSITIVE|NEGATIVE|INDETERMINATE)\b""")
                .find(patient)?.groupValues?.get(1)
        return if (final==null) emptyList()
            else listOf(VerifiedPdfItem("Final result",final.uppercase(Locale.ROOT)))
    }

    private fun coagulation(test:String, source:String):List<VerifiedPdfItem> {
        val t=key(test)
        if (!("ptaptt" in t || t=="ptinr" || "prothrombin" in t ||
                    "aptt" in t || "partialthromboplastin" in t)) return emptyList()
        val result=mutableListOf<VerifiedPdfItem>()
        val patient=source.substringBefore("Comments:",source).substringBefore("COMMENTS:",source)
        val rejected=Regex("""(?i)improper\s+sample\s+collection|please\s+repeat\s+the\s+test""")
            .containsMatchIn(source)
        if(!rejected) {
            item(patient,"PT (patient)",
                """(?i)\bprothrombin\s*time\s*\(\s*PT\s*\)\s*[:\-]?\s*([\d.]+\s*(?:sec|seconds?))\b""")?.let(result::add)
            item(patient,"INR",
                """(?i)\bPT\s*INR\s*[:\-]?\s*([\d.]+)\b""")?.let(result::add)
            item(patient,"aPTT (patient)",
                """(?i)\b(?:aPTT|activated\s+partial\s+thromboplastin\s+time)\s*[:\-]?\s*([\d.]+\s*(?:sec|seconds?))\b""")?.let(result::add)
        }
        if(rejected)result+=VerifiedPdfItem("Patient result","Not reported")
        Regex("""(?i)improper\s+sample\s+collection[^\r\n.]*\.?\s*(?:please\s+repeat\s+the\s+test\.?)?""")
            .find(source)?.value?.takeIf(String::isNotBlank)?.let {
                result+=VerifiedPdfItem("Comment",clean(it))
            }
        return result
    }

    private fun marrow(test:String,source:String):List<VerifiedPdfItem> {
        val t=key(test)
        if("bonemarrow" !in t && "trephine" !in t)return emptyList()
        val biopsy = "biopsy" in t || "trephine" in t
        val s=lines(source)
        // Restrict to the named section; an aspirate and later biopsy can
        // legitimately share an accession but not their report dates.
        if(biopsy && !Regex("""(?i)\bBONE\s+MARROW\s+BIOPSY\b""").containsMatchIn(s))
            return emptyList()
        if(!biopsy && !Regex("""(?i)\bBONE\s+MARROW\s+ASPIRATION\b""").containsMatchIn(s))
            return emptyList()
        val output=mutableListOf<VerifiedPdfItem>()
        fun add(label:String, pattern:String) {
            item(s,label,pattern)?.let(output::add)
        }
        if(biopsy) {
            add("Adequacy", """(?im)^\s*Adequacy\s*:\s*([^\r\n]+)""")
            add("Bony trabeculae", """(?im)^\s*Bony\s+Trabeculae\s*:\s*([^\r\n]+)""")
            add("Cellularity", """(?im)^\s*Cellularity\s*:\s*(\d{1,3}\s*%)""")
            add("Megakaryocytes", """(?im)^\s*Megakaryocytes\s*:\s*([\d\-]+\s*/\s*HPF)""")
            add("Reticulin stain", """(?im)^\s*Reticulin\s+Stain\s*:\s*([^\r\n]+)""")
        } else {
            add("Specimen", """(?im)^\s*Type\s+of\s+Specimen\s*:\s*([^\r\n]+)""")
            add("Aspirate", """(?im)^\s*Aspirate\s*:\s*([^\r\n]+)""")
            add("Cellularity", """(?im)^\s*Cellularity\s*:\s*([^\r\n]+)""")
            add("Erythropoiesis", """(?im)^\s*Erythropoiesis\s*:\s*([^\r\n]+)""")
            add("Myelopoiesis", """(?im)^\s*Myelopoiesis\s*:\s*([^\r\n]+)""")
            add("Megakaryocytes", """(?im)^\s*Megakaryocytes\s*:\s*([^\r\n]+)""")
            add("M:E ratio", """(?im)^\s*M\s*:?\s*E\s*Ratio\s*:\s*([\d.]+\s*:\s*[\d.]+)""")
            add("Iron", """(?im)^\s*Iron\s*:\s*([^\r\n]+)""")
            add("Cytochemistry", """(?im)^\s*Cytochemistry\s*:\s*([^\r\n]+)""")
        }
        add("Impression", """(?im)^\s*Impression\s*/\s*Diagnosis\s*:\s*([^\r\n]+)""")
        return output
    }

    private fun lupusAnticoagulant(test:String,source:String):List<VerifiedPdfItem> {
        val t=key(test)
        val isScreen="lacscreen" in t || ("lupusanticoagulant" in t && "screen" in t)
        val isConfirm="lacconfirm" in t || ("lupusanticoagulant" in t && "confirm" in t)
        if(!isScreen && !isConfirm)return emptyList()
        val sourceText=lines(source)
        val wanted=if(isScreen) "SCREENING TESTS" else "CONFIRMATORY TESTS"
        val start=sourceText.indexOf(wanted,ignoreCase=true)
        if(start<0)return emptyList()
        val next=if(isScreen) "CONFIRMATORY TESTS" else "END OF THE REPORT"
        val fullSection=sourceText.substring(start).take(5000)
        val boundary=Regex(Regex.escape(next),RegexOption.IGNORE_CASE)
            .find(fullSection)?.range?.first ?: fullSection.length
        val section=fullSection.take(boundary)
        // The laboratory's final conclusion takes priority over every
        // threshold in the generic interpretation reference table.
        val interpretation=if(isScreen) {
            Regex("""(?i)LUPUS\s+ANTICOAGULANT\s+SCREENING\s+TESTS\s+ARE\s+(POSITIVE|NEGATIVE)""")
                .find(section)?.groupValues?.get(1)
        } else {
            Regex("""(?i)(POSITIVE|NEGATIVE)\s+FOR\s+LUPUS\s+ANTICOAGULANT""")
                .find(section)?.value
        }
        val result=mutableListOf<VerifiedPdfItem>()
        if(!interpretation.isNullOrBlank())
            result+=VerifiedPdfItem("Interpretation",interpretation)

        fun subsection(startPattern:String,endPattern:String):String {
            val at=Regex(startPattern,RegexOption.IGNORE_CASE).find(section)
                ?.range?.first ?: return ""
            val fragment=section.substring(at)
            val end=Regex(endPattern,RegexOption.IGNORE_CASE).find(
                fragment,1)?.range?.first ?: fragment.length
            return fragment.take(end).take(1100)
        }
        fun append(block:String,label:String,pattern:String) {
            item(block,label,pattern)?.let(result::add)
        }
        if(isScreen) {
            append(section,"PT",
                """(?i)\bProthrombin\s+Time\s*\(\s*PT\s*\)\s*([\d.]+\s*Sec)\b""")
            append(section,"aPTT",
                """(?i)\b(?:Activated\s+Partial\s+Thromboplastin\s+Time|APTT)\s*([\d.]+\s*Sec)\b""")
            append(section,"Thrombin time",
                """(?i)\bThrombin\s+Time\s*\(\s*TT\s*\)\s*([\d.]+\s*Sec)\b""")
            val ptt=subsection("""PTT[\s-]*LA""","""DRVVT[\s-]*SCREEN""")
            append(ptt,"PTT-LA patient",
                """(?i)Patient\s+Plasma\s*[:\-]?\s*([\d.]+\s*Sec)\b""")
            append(ptt,"PTT-LA control",
                """(?i)Pooled\s+Normal\s+Plasma\s*(?:\(\s*PNP\s*\))?\s*[:\-]?\s*([\d.]+\s*Sec)\b""")
            append(ptt,"PTT-LA ratio",
                """(?i)\bRatio\s*[:\-]?\s*([\d.]+)\b""")
            val drvvt=subsection("""DRVVT[\s-]*SCREEN""","""Interpretation""")
            append(drvvt,"DRVVT screen patient",
                """(?i)Patient\s+Plasma\s*[:\-]?\s*([\d.]+\s*Sec)\b""")
            append(drvvt,"DRVVT screen control",
                """(?i)Pooled\s+Normal\s+Plasma\s*(?:\(\s*PNP\s*\))?\s*[:\-]?\s*([\d.]+\s*Sec)\b""")
            append(drvvt,"DRVVT screen ratio",
                """(?i)Screen\s+Ratio\s*[:\-]?\s*([\d.]+)\b""")
        } else {
            val ptt=subsection("""PTT[\s-]*LA\s*Mix""","""DRVVT[\s-]*Mix""")
            append(ptt,"PTT-LA mixing",
                """(?i)Patient\s+plasma\s+with\s+PNP\s*\(\s*1:1\s*\)\s*[:\-]?\s*([\d.]+\s*Sec)\b""")
            val mixed=subsection("""DRVVT[\s-]*Mix""","""DRVVT[\s-]*CONFIRM""")
            append(mixed,"DRVVT mixing",
                """(?i)Patient\s+plasma\s+with\s+PNP\s*\(\s*1:1\s*\)\s*[:\-]?\s*([\d.]+\s*Sec)\b""")
            val confirm=subsection("""DRVVT[\s-]*CONFIRM""","""Interpretation""")
            append(confirm,"DRVVT confirm patient",
                """(?i)Patient\s+Plasma\s*[:\-]?\s*([\d.]+\s*Sec)\b""")
            append(confirm,"DRVVT confirm control",
                """(?i)Pooled\s+Normal\s+Plasma\s*(?:\(\s*PNP\s*\))?\s*[:\-]?\s*([\d.]+\s*Sec)\b""")
            append(confirm,"DRVVT confirm ratio",
                """(?i)Confirm\s+Ratio\s*[:\-]?\s*([\d.]+)\b""")
            append(confirm,"Normalized ratio",
                """(?i)Normalis(?:ed|ed)\s+Ratio\s*[:\-]?\s*([\d.]+)\b""")
        }
        return result.distinctBy { it.label }
    }

    fun extract(test: String, pdfText: String): List<VerifiedPdfItem> {
        if(pdfText.isBlank()) return emptyList()
        val s=lines(pdfText)
        val t=key(test)
        return when {
            "fibrinogen" in t -> fibrinogen(s)
            "bonemarrow" in t || "trephine" in t -> marrow(test,s)
            "lacscreen" in t || "lacconfirm" in t ||
                ("lupusanticoagulant" in t && ("screen" in t || "confirm" in t)) ->
                lupusAnticoagulant(test,s)
            "ptaptt" in t || t=="ptinr" || "prothrombin" in t ||
                t=="aptt" || "partialthromboplastin" in t -> coagulation(test,s)
            else -> molecularOrIgRa(test,s)
        }
    }
}
