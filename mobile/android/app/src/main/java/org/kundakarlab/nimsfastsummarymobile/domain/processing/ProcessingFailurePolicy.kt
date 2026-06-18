package org.kundakarlab.nimsfastsummarymobile.domain.processing

val ProcessingResult.Failure.isRemoteFallbackAllowed: Boolean
    get() = technicalCode in setOf("LOCAL_UNRECOGNIZED_REPORT", "LOCAL_PARSE_INCOMPLETE", "LOCAL_PARSE_ERROR")

fun <T> ProcessingResult<T>.withFallbackWarning(warning: String): ProcessingResult<T> = when (this) {
    is ProcessingResult.Success -> copy(warnings = warnings + warning)
    else -> this
}
