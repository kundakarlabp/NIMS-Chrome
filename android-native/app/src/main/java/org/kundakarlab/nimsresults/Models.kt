package org.kundakarlab.nimsresults

data class PatientInfo(
    val name: String = "",
    val crNo: String = "",
    val age: String = "",
    val sex: String = ""
)

data class LabResult(
    val id: String,
    val test: String,
    val parameter: String,
    val date: String,
    val valueText: String,
    val numericValue: Double? = null,
    val unit: String = "",
    val refRange: String = "",
    val abnormal: String = "",
    val sourceKey: String = "",
    val sentDate: String = "",
    val reportedDate: String = "",
    val labNumber: String = "",
    val orderDate: String = "",
    val sourceOrderId: String = "",
    val sampleNumber: String = "",
    val reportNumber: String = "",
    val reportStage: String = "",
    val documentReference: String = "",
    val packingListNumber: String = "",
    val sourceRow: String = "",
    val collectionDateTime: String = "",
    val receivedDateTime: String = "",
    val validationDateTime: String = "",
    val specimenType: String = "",
    val accessionNumber: String = "",
    val requisitionDateTime: String = "",
    val laboratory: String = ""
)

data class ReportRef(
    val id: String,
    val title: String,
    val date: String = "",
    val department: String = "",
    val url: String = "",
    val sentDate: String = "",
    val reportedDate: String = "",
    val labNumber: String = "",
    val orderDate: String = "",
    val sampleNumber: String = "",
    val reportNumber: String = "",
    val reportStage: String = "",
    val sourceOrderId: String = "",
    val documentReference: String = "",
    val packingListNumber: String = "",
    val sourceRow: String = ""
)

/** Raw source data, retained independently of populated cells and clinical filtering. */
data class RawInvestigationRow(
    val groupIndex: Int,
    val rowIndex: Int,
    val investigation: String,
    val sourceJson: String
)

data class ClinicalBundle(
    val patient: PatientInfo,
    val results: List<LabResult>,
    val reports: List<ReportRef>,
    val source: String = "nims_bulk_api",
    val retrievedAt: Long = System.currentTimeMillis(),
    val rawRows: List<RawInvestigationRow> = emptyList()
)

enum class SessionState { UNKNOWN, CHECKING, CONNECTED, LOGIN_REQUIRED, EXPIRED, ERROR }
enum class LoadStage { IDLE, CHECKING_SESSION, OPENING_TRENDS, LOADING_CR, CAPTURING_RESULTS, ENRICHING_REPORTS, READY, ERROR }
enum class ResultsTab { OVERVIEW, ALL, LABS, MICROBIOLOGY, RECENT, ID, TRENDS }
