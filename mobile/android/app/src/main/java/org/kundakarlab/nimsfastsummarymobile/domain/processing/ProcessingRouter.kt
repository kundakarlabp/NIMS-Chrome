package org.kundakarlab.nimsfastsummarymobile.domain.processing

import org.kundakarlab.nimsfastsummarymobile.domain.model.*

class ProcessingRouter(
    private val local: ReportProcessor,
    private val remote: ReportProcessor,
    private val modeProvider: () -> ProcessingMode,
    private val remoteConfigured: () -> Boolean = { true }
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

    private suspend fun parseLocalOnly(input: ReportInput): ProcessingResult<ParsedReport> = local.parseReport(input)

    private suspend fun parseAuto(input: ReportInput): ProcessingResult<ParsedReport> {
        return when (val localResult = local.parseReport(input)) {
            is ProcessingResult.Success -> localResult
            is ProcessingResult.Unsupported -> {
                if (!remoteConfigured()) ProcessingResult.Failure("This report is not supported on-device. Configure Railway fallback to process it.", "REMOTE_HELPER_REQUIRED", false)
                else remote.parseReport(input).withFallbackWarning("On-device parser unsupported; Railway fallback used.")
            }
            is ProcessingResult.Failure -> if (localResult.isRemoteFallbackAllowed) {
                if (!remoteConfigured()) ProcessingResult.Failure("On-device parsing was incomplete. Configure Railway fallback to process this report.", "REMOTE_HELPER_REQUIRED", false) else remote.parseReport(input).withFallbackWarning("On-device parsing was incomplete; Railway fallback used.")
            } else localResult
        }
    }
}
