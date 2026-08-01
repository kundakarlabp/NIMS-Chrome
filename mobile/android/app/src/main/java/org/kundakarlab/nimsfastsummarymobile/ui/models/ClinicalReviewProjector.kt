package org.kundakarlab.nimsfastsummarymobile.ui.models

import org.kundakarlab.nimsfastsummarymobile.data.processing.DateNormalizer
import kotlin.math.abs

enum class ClinicalPanel {
    ALL,
    HEMOGRAM,
    RENAL_METABOLIC,
    LIVER,
    INFLAMMATORY,
    MOLECULAR_PCR,
    OTHER
}

data class ClinicalReviewFilter(
    val panel: ClinicalPanel = ClinicalPanel.ALL,
    val abnormalOnly: Boolean = false,
    val days: Int? = null
) {
    init {
        require(days == null || days > 0) { "days must be positive when supplied" }
    }
}

data class ClinicalTimelineEvent(
    val sortEpoch: Long?,
    val date: String,
    val category: String,
    val title: String,
    val detail: String,
    val sourceKey: String = ""
)

data class ClinicalReviewView(
    val patient: UiPatientSnapshot,
    val labs: List<UiLabTrendRow>,
    val cultures: List<UiCultureRow>,
    val timeline: List<ClinicalTimelineEvent>,
    val changeStatements: List<String>,
    val reportsNeedingReview: List<UiSourceReport>
)

/**
 * Pure projection layer shared by Compose screens and clinician exports.
 * It never fetches, persists, or logs patient data.
 */
object ClinicalReviewProjector {
    fun project(summary: UiSummary, filter: ClinicalReviewFilter = ClinicalReviewFilter()): ClinicalReviewView {
        val latestEpoch = latestEpoch(summary)
        val cutoff = filter.days?.let { days -> latestEpoch?.minus(days * DAY_MS) }

        val labs = summary.labTrends.mapNotNull { row ->
            val panel = panelFor(row.parameter)
            if (panel == null || (filter.panel != ClinicalPanel.ALL && panel != filter.panel)) return@mapNotNull null
            if (filter.abnormalOnly && row.abnormality !in ABNORMAL) return@mapNotNull null
            trimLabHistory(row, cutoff)
        }

        val cultures = summary.cultures.filter { culture ->
            val epoch = DateNormalizer.normalize(culture.collectionDate).sortEpoch
            cutoff == null || epoch == null || epoch >= cutoff
        }

        return ClinicalReviewView(
            patient = summary.patientSnapshot,
            labs = labs,
            cultures = cultures,
            timeline = timeline(summary, cutoff),
            changeStatements = labs.mapNotNull(::changeStatement),
            reportsNeedingReview = summary.reportsNeedingReview
        )
    }

    fun panelFor(parameter: String): ClinicalPanel? {
        val p = parameter.uppercase()
        if (HIDDEN_HEMOGRAM.any(p::contains)) return null
        return when {
            p == "HB" || p.contains("HEMOGLOBIN") || p.contains("HAEMOGLOBIN") ||
                p == "WBC" || p.contains("WBC/TLC") || p.contains("TOTAL WBC") ||
                p.contains("TOTAL LEUCO") || p.contains("PLATELET") -> ClinicalPanel.HEMOGRAM
            RENAL.any(p::contains) -> ClinicalPanel.RENAL_METABOLIC
            LIVER.any(p::contains) -> ClinicalPanel.LIVER
            INFLAMMATORY.any(p::contains) -> ClinicalPanel.INFLAMMATORY
            MOLECULAR.any(p::contains) -> ClinicalPanel.MOLECULAR_PCR
            else -> ClinicalPanel.OTHER
        }
    }

    internal fun changeStatement(row: UiLabTrendRow): String? {
        val latest = numeric(row.latestValue) ?: return null
        val previous = numeric(row.previousValue.orEmpty()) ?: return null
        if (latest == previous) return "${row.parameter} unchanged at ${formatNumber(latest)}"

        val direction = if (latest > previous) "increased" else "decreased"
        val absolute = abs(latest - previous)
        val percent = if (previous == 0.0) null else absolute / abs(previous) * 100.0
        val magnitude = percent?.takeIf { it.isFinite() }?.let { " (${formatNumber(it)}%)" }.orEmpty()
        return "${row.parameter} $direction from ${formatNumber(previous)} to ${formatNumber(latest)}$magnitude"
    }

    private fun trimLabHistory(row: UiLabTrendRow, cutoff: Long?): UiLabTrendRow? {
        if (cutoff == null) return row
        val kept = row.history.filter { (date, _) ->
            val epoch = DateNormalizer.normalize(date).sortEpoch
            epoch == null || epoch >= cutoff
        }
        if (kept.isEmpty()) return null
        val latest = kept.first()
        val previous = kept.drop(1).firstOrNull()
        return row.copy(
            latestDate = latest.first,
            latestValue = latest.second,
            previousDate = previous?.first,
            previousValue = previous?.second,
            history = kept
        )
    }

    private fun timeline(summary: UiSummary, cutoff: Long?): List<ClinicalTimelineEvent> {
        val reportEvents = summary.sourceReports.mapNotNull { report ->
            event(
                date = report.dateSent,
                category = "Report",
                title = report.reportName,
                detail = report.status.replace('_', ' '),
                sourceKey = report.sourceKey,
                cutoff = cutoff
            )
        }
        val cultureEvents = summary.cultures.mapNotNull { culture ->
            val organism = culture.organism.ifBlank { culture.growth.ifBlank { culture.status.replace('_', ' ') } }
            event(
                date = culture.collectionDate,
                category = "Culture",
                title = culture.site.ifBlank { culture.specimen }.ifBlank { "Culture" },
                detail = organism,
                sourceKey = culture.sourceKey,
                cutoff = cutoff
            )
        }
        val labEvents = summary.labTrends.flatMap { row ->
            row.history.take(3).mapNotNull { (date, value) ->
                event(
                    date = date,
                    category = "Laboratory",
                    title = row.parameter,
                    detail = value,
                    cutoff = cutoff
                )
            }
        }
        return (reportEvents + cultureEvents + labEvents)
            .distinctBy { listOf(it.date, it.category, it.title, it.detail, it.sourceKey) }
            .sortedWith(compareByDescending<ClinicalTimelineEvent> { it.sortEpoch ?: Long.MIN_VALUE }
                .thenBy { it.category }
                .thenBy { it.title })
    }

    private fun event(
        date: String,
        category: String,
        title: String,
        detail: String,
        sourceKey: String = "",
        cutoff: Long?
    ): ClinicalTimelineEvent? {
        if (date.isBlank() && title.isBlank() && detail.isBlank()) return null
        val epoch = DateNormalizer.normalize(date).sortEpoch
        if (cutoff != null && epoch != null && epoch < cutoff) return null
        return ClinicalTimelineEvent(epoch, date, category, title, detail, sourceKey)
    }

    private fun latestEpoch(summary: UiSummary): Long? = sequence {
        summary.sourceReports.forEach { yield(DateNormalizer.normalize(it.dateSent).sortEpoch) }
        summary.cultures.forEach { yield(DateNormalizer.normalize(it.collectionDate).sortEpoch) }
        summary.labTrends.forEach { row -> row.history.forEach { yield(DateNormalizer.normalize(it.first).sortEpoch) } }
    }.filterNotNull().maxOrNull()

    private fun numeric(value: String): Double? = NUMBER.find(value.replace(',', ''))?.value?.toDoubleOrNull()

    private fun formatNumber(value: Double): String = when {
        value % 1.0 == 0.0 -> value.toLong().toString()
        else -> "%.2f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')
    }

    private val ABNORMAL = setOf(Abnormality.HIGH, Abnormality.LOW, Abnormality.CRITICAL)
    private val NUMBER = Regex("[-+]?\\d+(?:\\.\\d+)?")
    private const val DAY_MS = 86_400_000L

    private val HIDDEN_HEMOGRAM = listOf(
        "MCV", "MCH", "MCHC", "NEUTRO", "LYMPH", "MONOCYTE", "EOSINOPHIL",
        "BASOPHIL", "RBC", "HCT", "PCV", "RDW", "ANC", "ALC"
    )
    private val RENAL = listOf("CREATININE", "UREA", "EGFR", "SODIUM", "POTASSIUM", "CHLORIDE", "BICARBONATE", "GLUCOSE")
    private val LIVER = listOf("BILIRUBIN", "ALT", "AST", "SGOT", "SGPT", "ALP", "GGT", "ALBUMIN", "TOTAL PROTEIN", "LDH")
    private val INFLAMMATORY = listOf("CRP", "HSCRP", "HS-CRP", "PROCALCITONIN", "PCT", "ESR", "GALACTOMANNAN", "BETA-D-GLUCAN", "BETA D GLUCAN", "BDG")
    private val MOLECULAR = listOf("PCR", "VIRAL LOAD", "MOLECULAR", "CBNAAT", "GENE XPERT", "GENEXPERT")
}
