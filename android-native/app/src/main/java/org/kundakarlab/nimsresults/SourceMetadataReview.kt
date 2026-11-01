package org.kundakarlab.nimsresults

import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Candidate source-index metadata for MANUAL review. These are not linked
 * laboratory events and must never be copied into LabResult provenance.
 * A Custom Sheet column date is NOT proof of collection/reporting time.
 */
object SourceMetadataReview {
    private fun key(name: String): String = name.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "")
    private fun titleKey(name: String): String {
        val value = key(name)
        return when {
            value == "cbc" || value.startsWith("cbc") ||
                value.startsWith("cbp") || value == "completebloodcount" ||
                value == "completebloodpicture" || value.startsWith("hemogram") ||
                value.startsWith("haemogram") || value == "completehemogram" -> "cbc"
            value.startsWith("electrolyte") -> "electrolytes"
            value.startsWith("liverfunctiontest") || value == "lft" -> "lft"
            value.startsWith("renalfunctiontest") || value.startsWith("kidneyfunctiontest") ||
                value == "rft" || value == "kft" -> "rft"
            value == "serumcreatinine" || value == "creatinine" -> "creatinine"
            value == "serumurea" || value == "urea" -> "urea"
            value.startsWith("grams") || value.startsWith("gramstain") ||
                value.startsWith("gramsmear") -> "gramstain"
            value.startsWith("fungalfluorescen") ||
                value.startsWith("calcofluor") -> "calcofluor"
            value.startsWith("fungalkoh") || value.startsWith("kohpreparation") ||
                value.startsWith("kohmount") -> "fungalkoh"
            value.startsWith("afbsmear") || value.startsWith("afbstain") ||
                value.startsWith("acidfastbacilli") -> "afbstain"
            value=="urineculture" || value=="urineaerobicculture" -> "urineculture"
            value.startsWith("indirectcoombs") ||
                value.startsWith("indirectantiglobulin") ||
                value in setOf("iat","iatgel","ict","ictgel") -> "indirectcoombs"
            value.startsWith("directcoombs") ||
                value.startsWith("directantiglobulin") ||
                value in setOf("dat","datgel","dct","dctgel") -> "directcoombs"
            else -> value
        }
    }

    /** REST reportList is a catalogue, not a specimen-identifying source.
     * Find *manual review choices* in the independently authenticated report
     * inventory. Neither title nor any date delta establishes a source event.
     */
    fun catalogCandidates(rest: ReportRef, sourceIndex: List<ReportRef>): List<ReportRef> {
        if (!rest.sourceTag.startsWith("nims_rest_reportList")) return emptyList()
        val title = titleKey(rest.title)
        val reported = RecentReportDates.parse(rest.reportedDate.ifBlank { rest.date })
            ?: return emptyList()
        if (title.isBlank()) return emptyList()
        return sourceIndex.asSequence()
            .filter { !it.sourceTag.startsWith("nims_rest_reportList") &&
                it.url.isNotBlank() && titleKey(it.title) == title }
            .filter { candidate ->
                val indexDate = RecentReportDates.parse(
                    candidate.collectionDateTime.ifBlank { candidate.sentDate }
                        .ifBlank { candidate.date })
                indexDate != null &&
                    ChronoUnit.DAYS.between(indexDate, reported) in 0L..14L
            }.distinctBy { it.url }.take(8).toList()
    }

    fun candidates(rows: List<LabResult>, sourceIndex: List<ReportRef>): List<ReportRef> {
        val test = rows.firstOrNull()?.test.orEmpty()
        val key = titleKey(test)
        val date = rows.firstNotNullOfOrNull { RecentReportDates.parse(it.date) }
        if (key.isBlank() || date == null) return emptyList()
        return sourceIndex.filter { report ->
            // REST reportList contains only testname/reportdate on this HIS
            // deployment. Such rows must not be presented as specimen-linked
            // source records or as candidate PDFs with unknown identifiers.
            if (report.sourceTag.startsWith("nims_rest_reportList")) return@filter false
            if (report.url.isBlank() && report.labNumber.isBlank() &&
                report.sampleNumber.isBlank() && report.reportNumber.isBlank())
                return@filter false
            if (titleKey(report.title) != key) return@filter false
            val collection = RecentReportDates.parse(
                report.collectionDateTime.ifBlank { report.sentDate }.ifBlank { report.date }
            ) ?: return@filter false
            ChronoUnit.DAYS.between(collection, date) in 0L..14L
        // A bundled NIMS PDF can be indexed in several rows. Present a single
        // review choice per physical PDF, never five copies of "unknown".
        }.distinctBy { report ->
            if (report.url.isNotBlank()) "document:" + report.url
            else "metadata:" + report.id + "|" + report.labNumber +
                "|" + report.sampleNumber + "|" + report.reportNumber
        }.sortedWith(compareByDescending<ReportRef> {
            RecentReportDates.parse(it.collectionDateTime.ifBlank { it.date })
        }.thenBy { it.labNumber }.thenBy { it.reportNumber })
    }

    private fun sameInvestigation(test: String, source: String): Boolean {
        val target = titleKey(test)
        val candidate = titleKey(source)
        if (target == candidate) return true
        // One physical chemistry PDF often contains several separate HIS tests.
        val component = when(target) {
            "creatinine" -> "creatinine"
            "urea" -> "urea"
            "lft" -> "liverfunction"
            "electrolytes" -> "electrolyte"
            else -> ""
        }
        if (component.isNotEmpty() && candidate.contains(component)) return true
        // For cultures preserve set/bottle and preliminary/final distinction.
        val culture = target.contains("bloodculture") || target.contains("aerobicculture")
        if (!culture) return false
        if (!candidate.contains("culture")) return false
        val set = Regex("""(?i)(\d+)\s*set""").find(test)?.groupValues?.get(1)
        val otherSet = Regex("""(?i)(\d+)\s*set""").find(source)?.groupValues?.get(1)
        val bottle = Regex("""(?i)(\d+)\s*bottle""").find(test)?.groupValues?.get(1)
        val otherBottle = Regex("""(?i)(\d+)\s*bottle""").find(source)?.groupValues?.get(1)
        if (set != null && otherSet != null && set != otherSet) return false
        if (bottle != null && otherBottle != null && bottle != otherBottle) return false
        val isFinal = test.contains("final",true)
        val otherFinal = source.contains("final",true)
        if ((test.contains("48",true) || isFinal) &&
            (source.contains("48",true) || otherFinal) && isFinal != otherFinal) return false
        return true
    }

    private fun sameAccession(one: String, two: String): Boolean =
        one.isNotBlank() && two.isNotBlank() && key(one) == key(two)

    /**
     * Prefer an accession from the actual structured source narrative.
     * Otherwise show ONLY same-day, test-compatible PDF choices. The broad
     * historical +/-14 day scan caused unrelated CBC/culture PDFs to appear.
     * Date alone is a review candidate, never a verified specimen link.
     */
    fun pdfCandidates(rows: List<LabResult>, sourceIndex: List<ReportRef>): List<ReportRef> {
        val test = rows.firstOrNull()?.test.orEmpty()
        val date = rows.firstNotNullOfOrNull { RecentReportDates.parse(it.date) }
        if (test.isBlank()) return emptyList()
        val source = sourceIndex.asSequence().filter { report ->
            !report.sourceTag.startsWith("nims_rest_reportList") &&
                report.url.isNotBlank()
        }.distinctBy { it.url }.toList()
        val directLab=rows.mapNotNull { it.labNumber.trim().takeIf(String::isNotBlank) }
            .distinct().singleOrNull().orEmpty()
        val labelled=rows.mapNotNull { MicrobiologyNarrativeMetadata.extract(it)?.labNumber }
            .filter(String::isNotBlank).distinct().singleOrNull().orEmpty()
        val lab=directLab.ifBlank { labelled }
        if (lab.isNotBlank()) {
            val matches=source.filter { report ->
                (sameAccession(lab,report.labNumber) ||
                    sameAccession(lab,report.sampleNumber)) &&
                    sameInvestigation(test,report.title)
            }
            if (matches.isNotEmpty()) return matches.sortedByDescending {
                RecentReportDates.dateFor(it).first
            }
            // Explicit narrative lab ID disagrees with index. Do not silently
            // choose a date-only PDF with a different lab identifier.
            return emptyList()
        }
        if (date == null) return emptyList()
        return source.filter { report ->
            if (!sameInvestigation(test,report.title)) return@filter false
            val candidateDate = RecentReportDates.parse(
                report.collectionDateTime.ifBlank { report.sentDate }.ifBlank { report.date }
            )
            if(candidateDate == null) return@filter false
            if(ClinicalReportRenderer.isMicrobiologyInvestigation(test) &&
                test.contains("culture",ignoreCase=true)) {
                // Culture FINAL results may be recorded days after specimen
                // collection. A title+set+bottle+stage match in the same
                // 0–7 day episode can be offered for review, never auto-linked.
                ChronoUnit.DAYS.between(candidateDate,date) in 0L..7L
            } else candidateDate == date
        }.sortedWith(compareBy<ReportRef> { it.title }.thenBy { it.labNumber })
    }

}
