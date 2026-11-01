package org.kundakarlab.nimsresults

import java.util.Locale

/**
 * Links Enquiry ORDER rows to report-index rows only when a real shared
 * Sample/Lab number and compatible investigation identity are present.
 * Does not attach Custom Sheet observations or assert PDF validity.
 */
object EnquirySourceBridge {
    enum class State { VERIFIED_INDEX, MISSING_IDENTIFIER, UNMATCHED, AMBIGUOUS }
    data class Decision(val state: State, val source: ReportRef? = null)

    private fun id(s: String) = s.trim().uppercase(Locale.US)
    private fun name(s: String): String {
        val a=s.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")
        return when {
            a=="cbc" || a.startsWith("cbc") || a.startsWith("hemogram") ||
                a.startsWith("haemogram") || a=="completebloodcount" ||
                a=="completehemogram" -> "cbc"
            a.contains("bloodculture") -> "bloodculture"
            a.contains("urineaerobicculture") -> "urineculture"
            a=="aerobicculture" -> "aerobicculture"
            a.contains("gramstain") || a.contains("gramsmear") -> "gramstain"
            a.contains("fungalstain") -> "fungalstain"
            a.contains("afbstain") -> "afbstain"
            else -> a
        }
    }

    private fun stage(title:String):String = when {
        title.contains("final",true) -> "FINAL"
        title.contains("48 hrs",true) || title.contains("48 hours",true) -> "48H"
        title.contains("24 hrs",true) || title.contains("24 hours",true) -> "24H"
        else -> ""
    }
    private fun labelNumber(title:String,type:String):String =
        Regex("""(?i)(\d+)\s*(?:st|nd|rd|th)?\s*\b$type\b""")
            .find(title)?.groupValues?.getOrNull(1).orEmpty()
    private fun compatible(order:String,source:String):Boolean {
        if(name(order)!=name(source))return false
        val wantedStage=stage(order)
        if(wantedStage.isNotBlank() && stage(source).isNotBlank() &&
            wantedStage!=stage(source)) return false
        if(name(order)=="bloodculture") {
            for (field in listOf("set","bottle")) {
                val a=labelNumber(order,field)
                val b=labelNumber(source,field)
                if(a.isNotBlank() && b.isNotBlank() && a!=b)return false
            }
        }
        return true
    }
    fun assess(order: EnquiryOrder, reports: List<ReportRef>): Decision {
        val sample=id(order.sampleNumber)
        val lab=id(order.labNumber)
        if (sample.isEmpty() && lab.isEmpty()) return Decision(State.MISSING_IDENTIFIER)
        val test=name(order.testName)
        if (test.isBlank()) return Decision(State.UNMATCHED)
        val matches=reports.filter {
            compatible(order.testName,it.title) &&
                (sample.isEmpty() || id(it.sampleNumber) == sample) &&
                (lab.isEmpty() || id(it.labNumber) == lab)
        }.distinctBy {
            listOf(it.url,it.reportNumber,it.reportStage,it.labNumber,
                it.sampleNumber,it.collectionDateTime).joinToString("|")
        }
        return when(matches.size) {
            0 -> Decision(State.UNMATCHED)
            1 -> Decision(State.VERIFIED_INDEX, matches.single())
            else -> Decision(State.AMBIGUOUS)
        }
    }

    data class Coverage(
        val total:Int,
        val linked:Int,
        val missingIds:Int,
        val ambiguous:Int,
        val unmatched:Int,
        val pending:Int,
        val reported:Int
    )
    fun coverage(bundle:ClinicalBundle):Coverage {
        val states=bundle.enquiryOrders.map { assess(it,bundle.reports).state }
        return Coverage(states.size,
            states.count {it==State.VERIFIED_INDEX},
            states.count {it==State.MISSING_IDENTIFIER},
            states.count {it==State.AMBIGUOUS},
            states.count {it==State.UNMATCHED},
            bundle.enquiryOrders.count {
                Regex("(?i)requisition|sample collected|sample accepted|pending|inprocess")
                    .containsMatchIn(it.status)
            },
            bundle.enquiryOrders.count {
                Regex("(?i)report generated|result printed|completed|final")
                    .containsMatchIn(it.status)
            })
    }
}
