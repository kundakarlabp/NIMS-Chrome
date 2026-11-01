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
        val aptt=isAPTTPanel(test) && !isPTPanel(test)
        val pt=isPTPanel(test)
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
        val found=rows.mapNotNull(::label).toSet()
        return listOfNotNull(
            "PT (patient)".takeIf { it !in found },
            "PT (control)".takeIf { it !in found }
        )
    }

    fun divergentINR(rows:List<LabResult>):Boolean =
        rows.filter { label(it)=="INR" }.map { it.valueText.trim() }
            .filter(String::isNotBlank).distinct().size > 1
}
