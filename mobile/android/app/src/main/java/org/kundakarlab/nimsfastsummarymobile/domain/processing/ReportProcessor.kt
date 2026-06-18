package org.kundakarlab.nimsfastsummarymobile.domain.processing

import org.kundakarlab.nimsfastsummarymobile.domain.model.*

sealed interface ProcessingResult<out T> {
    data class Success<T>(val value: T, val processorName: String, val warnings: List<String> = emptyList()) : ProcessingResult<T>
    data class Unsupported(val reason: String) : ProcessingResult<Nothing>
    data class Failure(val userMessage: String, val technicalCode: String, val retryable: Boolean, val cause: Throwable? = null) : ProcessingResult<Nothing>
}

interface ReportProcessor {
    val name: String
    val capabilities: Set<ProcessingCapability>
    suspend fun parseReport(input: ReportInput): ProcessingResult<ParsedReport>
    suspend fun summarize(reports: List<ParsedReport>, mode: SummaryMode): ProcessingResult<ProcessingSummary>
}
