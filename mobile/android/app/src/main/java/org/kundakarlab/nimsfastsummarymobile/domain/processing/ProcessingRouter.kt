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
        ProcessingMode.AUTO -> {
            val localResult = local.summarize(reports, mode)
            when (localResult) {
                is ProcessingResult.Success -> localResult
                is ProcessingResult.Unsupported -> remote.summarize(reports, mode).withFallbackWarning("On-device summary unsupported; Railway fallback used.")
                is ProcessingResult.Failure -> if (localResult.isRemoteFallbackAllowed) {
                    remote.summarize(reports, mode).withFallbackWarning("On-device summary was incomplete; Railway fallback used.")
                } else localResult
            }
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
            is ProcessingResult.Unsupported -> remote.parseReport(input).withFallbackWarning("On-device parser unsupported; Railway fallback used.")
            is ProcessingResult.Failure -> if (localResult.isRemoteFallbackAllowed) {
                remote.parseReport(input).withFallbackWarning("On-device parsing was incomplete; Railway fallback used.")
            } else localResult
        }
    }
}
