package org.kundakarlab.nimsfastsummarymobile.data.processing

/** Returns the text after [delimiter], with case-insensitive matching when requested. */
internal fun String.substringAfter(
    delimiter: String,
    missingDelimiterValue: String,
    ignoreCase: Boolean
): String {
    val index = indexOf(delimiter, ignoreCase = ignoreCase)
    return if (index < 0) missingDelimiterValue else substring(index + delimiter.length)
}
