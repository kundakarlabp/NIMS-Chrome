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
        return if (a == "cbc" || a.startsWith("cbc") ||
            a == "completebloodcount" || a == "completehemogram") "cbc" else a
    }
    fun assess(order: EnquiryOrder, reports: List<ReportRef>): Decision {
        val sample=id(order.sampleNumber)
        val lab=id(order.labNumber)
        if (sample.isEmpty() && lab.isEmpty()) return Decision(State.MISSING_IDENTIFIER)
        val test=name(order.testName)
        if (test.isBlank()) return Decision(State.UNMATCHED)
        val matches=reports.filter {
            name(it.title) == test &&
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
