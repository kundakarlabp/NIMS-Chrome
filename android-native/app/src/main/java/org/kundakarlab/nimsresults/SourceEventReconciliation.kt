package org.kundakarlab.nimsresults

import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * A verified SOURCE-INDEX identity is not a verified PDF or a verified
 * result-to-PDF association. Never infer a specimen identity from title/date.
 *
 * A Custom Sheet result has to carry an explicit Lab/Study or Sample number,
 * the source index has to carry that same exact identifier, and the source
 * title has to name the same investigation. Conflicting/grouped specimens
 * and duplicate source events remain unresolved.
 */
object SourceEventReconciliation {
    enum class State { VERIFIED_INDEX, NO_RESULT_ID, CONFLICTING_RESULT_ID,
        NO_INDEX_MATCH, AMBIGUOUS_INDEX, DATE_CONFLICT }

    data class Decision(val state: State, val source: ReportRef? = null) {
        val verified: Boolean get() = state == State.VERIFIED_INDEX && source != null
    }

    private fun identifier(value: String) = value.trim().uppercase(Locale.US)
    private fun title(value: String): String {
        val normalized = value.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "")
        return when {
            normalized == "cbc" || normalized.startsWith("cbc") ||
                normalized == "completebloodcount" || normalized == "completehemogram" -> "cbc"
            else -> normalized
        }
    }
    private fun uniqueIds(rows: List<LabResult>, field: (LabResult) -> String): Set<String> =
        rows.map(field).map(::identifier).filter(String::isNotBlank).toSet()

    fun assess(rows: List<LabResult>, sources: List<ReportRef>): Decision {
        if (rows.isEmpty()) return Decision(State.NO_RESULT_ID)
        val labs = uniqueIds(rows) { it.labNumber.ifBlank { it.accessionNumber } }
        val samples = uniqueIds(rows) { it.sampleNumber }
        if (labs.size > 1 || samples.size > 1)
            return Decision(State.CONFLICTING_RESULT_ID)
        val lab = labs.singleOrNull().orEmpty()
        val sample = samples.singleOrNull().orEmpty()
        if (lab.isEmpty() && sample.isEmpty()) return Decision(State.NO_RESULT_ID)

        val test = title(rows.first().test)
        if (test.isBlank() || rows.any { title(it.test) != test })
            return Decision(State.CONFLICTING_RESULT_ID)
        val matches = sources.filter { source ->
            // Reported source titles are not interchangeable with arbitrary
            // test labels, even when a number happens to coincide.
            title(source.title) == test &&
                (lab.isEmpty() || identifier(source.labNumber) == lab) &&
                (sample.isEmpty() || identifier(source.sampleNumber) == sample)
        }
        if (matches.isEmpty()) return Decision(State.NO_INDEX_MATCH)
        // Only duplicate snapshots of precisely the same source event may
        // collapse here. Distinct report stages and specimen IDs remain distinct.
        val unique = matches.distinctBy {
            listOf(identifier(it.labNumber), identifier(it.sampleNumber),
                identifier(it.reportNumber), identifier(it.reportStage),
                it.collectionDateTime.trim(), it.url.trim()).joinToString("|")
        }
        if (unique.size != 1) return Decision(State.AMBIGUOUS_INDEX)
        val source = unique.single()
        val resultDate = rows.mapNotNull { RecentReportDates.parse(it.date) }
            .distinct().singleOrNull()
        val collected = RecentReportDates.parse(source.collectionDateTime)
            ?: RecentReportDates.parse(source.sentDate)
        if (collected != null && resultDate != null) {
            val elapsed = ChronoUnit.DAYS.between(collected, resultDate)
            if (elapsed !in 0L..45L) return Decision(State.DATE_CONFLICT)
        }
        return Decision(State.VERIFIED_INDEX, source)
    }

    data class Coverage(
        val groups: Int,
        val verified: Int,
        val withoutSourceId: Int,
        val ambiguous: Int,
        val dateConflicts: Int,
        val noIndexMatch: Int
    )
    fun coverage(bundle: ClinicalBundle): Coverage {
        val groups = UnifiedResultsFeed.baseEntries(bundle).filter { it.report == null }
        val decisions = groups.map { assess(it.rows, bundle.reports).state }
        return Coverage(
            groups = decisions.size,
            verified = decisions.count { it == State.VERIFIED_INDEX },
            withoutSourceId = decisions.count { it == State.NO_RESULT_ID },
            ambiguous = decisions.count {
                it == State.CONFLICTING_RESULT_ID || it == State.AMBIGUOUS_INDEX
            },
            dateConflicts = decisions.count { it == State.DATE_CONFLICT },
            noIndexMatch = decisions.count { it == State.NO_INDEX_MATCH }
        )
    }
}
