package io.valroot.ui

/** A chart axis label in the device's language and time zone: the date over long spans, else the date and time. */
internal actual fun chartTime(
    millis: Long,
    spanMillis: Long,
): String {
    val pattern = if (spanMillis > 90L * 86_400_000L) "d MMM yyyy" else "d MMM, HH:mm"
    return java.time.format.DateTimeFormatter
        .ofPattern(pattern, java.util.Locale.getDefault())
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochMilli(millis))
}
