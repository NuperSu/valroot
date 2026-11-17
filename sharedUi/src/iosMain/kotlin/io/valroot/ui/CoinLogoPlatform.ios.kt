package io.valroot.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

internal actual fun decodeLogo(bytes: ByteArray): ImageBitmap? =
    runCatching {
        org.jetbrains.skia.Image
            .makeFromEncoded(bytes)
            .toComposeImageBitmap()
    }.getOrNull()

/** iOS has no HTTP transport yet, so coin logos are not downloaded there; screens show the coin's initials. */
internal actual fun downloadLogo(
    url: String,
    maxBytes: Int,
): ByteArray? = null

/** A chart axis label in the device's language and time zone: the date over long spans, else the date and time. */
internal actual fun chartTime(
    millis: Long,
    spanMillis: Long,
): String =
    NSDateFormatter()
        .apply {
            locale = NSLocale.currentLocale
            timeZone = NSTimeZone.localTimeZone
            dateFormat = if (spanMillis > 90L * 86_400_000L) "d MMM yyyy" else "d MMM, HH:mm"
        }.stringFromDate(NSDate.dateWithTimeIntervalSince1970(millis / 1000.0))
