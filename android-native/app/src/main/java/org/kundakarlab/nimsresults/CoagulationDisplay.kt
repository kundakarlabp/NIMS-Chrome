package org.kundakarlab.nimsresults

import java.util.Locale

/**
 * Conservative display mapping for PT/INR and aPTT source rows.
 * Nothing is calculated or inferred from INR/controls. Original HIS labels,
 * numerical precision and all repeated measurements remain untouched.
 */
object CoagulationDisplay {
    private fun normalized(text: String): String = text.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]"), "")

    fun isPTPanel(test: String): Boolean {
        val t=normalized(test)
        if (t == "aptt" || t.startsWith("activatedpartialthromboplastin"))
            return false
        return t.contains("prothrombin") || t == "ptinr" || t == "inr" ||
            t=="pt" || t=="pttest" || t.contains("prothrombintime")
    }

    fun isAPTTPanel(test: String): Boolean {
        val t=normalized(test)
        return t.contains("aptt") || t.contains("partialthromboplastin")
    }

    fun label(row: LabResult): String? {
        val name=normalized(row.parameter.ifBlank { row.test })
        val test=row.test
        val combined=normalized(test).let { it=="ptaptt" || it=="apttpt" }
        val explicitlyPT=name.contains("prothrombin") || name.startsWith("pt")
        val explicitlyAPTT=name.contains("aptt") ||
            name.contains("partialthromboplastin")
        val aptt=isAPTTPanel(test) &&
            (explicitlyAPTT || (!combined && !isPTPanel(test)))
        val pt=!aptt && (isPTPanel(test) || (combined && explicitlyPT))
        // An unqualified "Control" in a combined PT/aPTT panel is ambiguous.
        if(combined && !explicitlyPT && !explicitlyAPTT &&
            name!="inr" && name!="internationalnormalizedratio") return null
        if (!pt && !aptt && name != "inr" &&
            name != "internationalnormalizedratio") return null
        if(name == "inr" || name == "ptinr" ||
            name == "internationalnormalizedratio" ||
            name == "internationalnormalisedratio" ||
            name.startsWith("inrresult")) return "INR"
        val prefix=if(aptt) "aPTT" else "PT"
        val category=when {
            name.contains("control") || name.contains("normalplasma") ||
                name=="normal" || name=="referenceplasma" -> "control"
            name.contains("patient") || name.contains("testplasma") ||
                name=="testtime" || name=="patienttime" ||
                name=="patientresult" || name=="testresult" -> "patient"
            else -> ""
        }
        if(category.isNotBlank())return "$prefix ($category)"
        // Never assume that a generic 'PT' result is the patient or control
        // without an explicit source label. Unqualified assay name is retained.
        if(pt && (name=="prothrombintime" || name=="pt" ||
                name=="prothrombintimept")) return "PT"
        if(aptt && (name=="aptt" || name=="activatedpartialthromboplastintime"))
            return "aPTT"
        return null
    }

    fun isAbsentValue(row: LabResult): Boolean =
        (isPTPanel(row.test) || isAPTTPanel(row.test)) &&
            row.valueText.trim().lowercase() in setOf("", ".", "..", "-", "--", "n/a", "not reported")

    private fun category(row: LabResult):String {
        val p=normalized(row.parameter)
        return when {
            p.contains("patient") || p.contains("testplasma") ||
                p=="testtime" || p=="testresult" -> "patient"
            p.contains("control") || p.contains("normalplasma") ||
                p=="normal" || p=="referenceplasma" -> "control"
            else -> ""
        }
    }

    /** A source-provided 0.9–1.1 reference is evidence for a dimensionless
     * ratio, not a PT in seconds. Repeated explicitly labelled controls in
     * the same test can then be separated only where a distinct plausible
     * clotting time and ratio coexist. Never derive the INR by arithmetic. */
    private fun ratioReference(row: LabResult):Boolean {
        val ref=row.refRange.lowercase().replace(" ", "")
        if(ref.contains("inr"))return true
        val vals=Regex("""\d+(?:\.\d+)?""").findAll(ref)
            .mapNotNull { it.value.toDoubleOrNull() }.toList()
        return vals.size>=2 && vals[0] in 0.75..1.15 &&
            vals[1] in 1.05..1.45 && vals[1]>vals[0]
    }

    private fun explicitSeconds(row: LabResult):Boolean =
        Regex("""(?i)\b(?:sec|second|seconds)\b""")
            .containsMatchIn(row.unit+" "+row.refRange)

    fun displayLabel(row:LabResult, peers:List<LabResult>):String {
        val base=label(row) ?: return ClinicalChronology.parameterLabelFallback(row)
        if(!isPTPanel(row.test) || isAPTTPanel(row.test) &&
            normalized(row.test) !in listOf("ptaptt","apttpt"))return base
        if(base=="INR" || base.startsWith("INR ("))return base
        val role=category(row)
        if(role.isEmpty() || base !in setOf("PT (patient)","PT (control)"))
            return base
        val sourceName=normalized(row.parameter)
        if(sourceName.contains("inr") || ratioReference(row))return "INR ($role)"
        if(explicitSeconds(row))return base
        val value=row.valueText.trim().toDoubleOrNull() ?: return base
        val same=peers.filter {
            it !== row && category(it)==role &&
                label(it)==base && it.valueText.trim().toDoubleOrNull()!=null
        }
        if(same.size!=1) return base
        val other=same.single()
        val otherValue=other.valueText.trim().toDoubleOrNull() ?: return base
        val ratioLike=value in 0.5..8.0 && otherValue in 8.0..180.0
        // Only disambiguate within a paired, same-role source row. The small
        // number is a ratio measurement, not a PT time in seconds.
        return if(ratioLike) "INR ($role)" else base
    }

    fun missingAPTTComponents(test:String,rows:List<LabResult>):List<String> {
        if(!isAPTTPanel(test) || rows.isEmpty()) return emptyList()
        val valid=rows.filterNot(::isAbsentValue)
        val found=valid.mapNotNull(::label).toSet()
        return listOfNotNull(
            "aPTT (patient)".takeIf { it !in found },
            "aPTT (control)".takeIf { it !in found }
        )
    }

    fun sortPriority(test:String,row:LabResult):Int {
        val label=label(row).orEmpty()
        return when(label) {
            "PT (patient)", "aPTT (patient)" -> 0
            "PT (control)", "aPTT (control)" -> 1
            "PT", "aPTT" -> 2
            "INR" -> 3
            else -> if(isPTPanel(test)) 10 else 20
        }
    }

    /** Caution only when an explicitly named PT panel lacks a documented
     * control or patient time in the accessible structured feed. */
    fun missingPTComponents(test:String, rows:List<LabResult>):List<String> {
        if(!isPTPanel(test) || rows.isEmpty())return emptyList()
        val found=rows.filterNot(::isAbsentValue).mapNotNull(::label).toSet()
        return listOfNotNull(
            "PT (patient)".takeIf { it !in found },
            "PT (control)".takeIf { it !in found }
        )
    }

    fun divergentINR(rows:List<LabResult>):Boolean =
        rows.filter { label(it)=="INR" }.map { it.valueText.trim() }
            .filter(String::isNotBlank).distinct().size > 1
}
