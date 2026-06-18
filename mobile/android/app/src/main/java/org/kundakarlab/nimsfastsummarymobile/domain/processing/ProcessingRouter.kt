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

    private suspend fun parseLocalOnly(input: ReportInput): ProcessingResult<ParsedReport> {
        if (input.isPdf()) return ProcessingResult.Unsupported(LOCAL_PDF_UNSUPPORTED)
        return local.parseReport(input)
    }

    private suspend fun parseAuto(input: ReportInput): ProcessingResult<ParsedReport> {
        if (input.isPdf()) {
            if (!remoteConfigured()) return ProcessingResult.Failure(AUTO_PDF_HELPER_REQUIRED, "REMOTE_HELPER_REQUIRED", false)
            return remote.parseReport(input)
        }
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

private const val LOCAL_PDF_UNSUPPORTED = "PDF local parsing is not yet supported. Open the source report manually."
private const val AUTO_PDF_HELPER_REQUIRED = "PDF local parsing is not yet supported. Configure Railway fallback to process PDFs."

private fun ReportInput.isPdf(): Boolean = contentType.contains("pdf", true) || bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray()) || bytes.take(4).toByteArray().contentEquals("%PDF".toByteArray())
