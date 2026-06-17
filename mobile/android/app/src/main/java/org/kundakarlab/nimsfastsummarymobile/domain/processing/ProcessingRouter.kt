package org.kundakarlab.nimsfastsummarymobile.domain.processing

import org.kundakarlab.nimsfastsummarymobile.domain.model.*

class ProcessingRouter(
    private val local: ReportProcessor,
    private val remote: ReportProcessor,
    private val modeProvider: () -> ProcessingMode
) {
    suspend fun parse(input: ReportInput): ProcessingResult<ParsedReport> = when (modeProvider()) {
        ProcessingMode.LOCAL_ONLY -> parseLocalOnly(input)
        ProcessingMode.REMOTE_ONLY -> remote.parseReport(input)
        ProcessingMode.AUTO -> parseAuto(input)
    }

    suspend fun summarize(reports: List<ParsedReport>, mode: SummaryMode): ProcessingResult<ProcessingSummary> = when (modeProvider()) {
        ProcessingMode.LOCAL_ONLY -> local.summarize(reports, mode)
        ProcessingMode.REMOTE_ONLY -> remote.summarize(reports, mode)
        ProcessingMode.AUTO -> local.summarize(reports, mode).let { result ->
            if (result is ProcessingResult.Unsupported || result is ProcessingResult.Failure) remote.summarize(reports, mode) else result
        }
    }

    private suspend fun parseLocalOnly(input: ReportInput): ProcessingResult<ParsedReport> {
        if (input.contentType.contains("pdf", true)) return ProcessingResult.Unsupported("This report format is not yet supported for on-device processing.")
        return local.parseReport(input)
    }

    private suspend fun parseAuto(input: ReportInput): ProcessingResult<ParsedReport> {
        if (input.contentType.contains("pdf", true)) return remote.parseReport(input)
        return when (val localResult = local.parseReport(input)) {
            is ProcessingResult.Success -> localResult
            is ProcessingResult.Unsupported -> addFallbackWarning(remote.parseReport(input), "On-device parser unsupported; Railway fallback used.")
            is ProcessingResult.Failure -> addFallbackWarning(remote.parseReport(input), "On-device parser failed safely; Railway fallback used.")
        }
    }

    private fun addFallbackWarning(result: ProcessingResult<ParsedReport>, warning: String): ProcessingResult<ParsedReport> =
        when (result) {
            is ProcessingResult.Success -> result.copy(warnings = result.warnings + warning)
            else -> result
        }
}
