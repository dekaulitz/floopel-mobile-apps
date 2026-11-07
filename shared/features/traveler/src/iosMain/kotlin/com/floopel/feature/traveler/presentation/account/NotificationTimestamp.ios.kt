package com.floopel.feature.traveler.presentation.account

import kotlin.time.Instant
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.localTimeZone

internal actual fun notificationTimestamp(instant: Instant, locale: String): String = NSDateFormatter().run {
    this.locale = NSLocale(localeIdentifier = locale)
    timeZone = NSTimeZone.localTimeZone
    dateFormat = "d MMM yyyy '·' HH.mm"
    stringFromDate(NSDate.dateWithTimeIntervalSince1970(instant.toEpochMilliseconds() / 1000.0))
}
