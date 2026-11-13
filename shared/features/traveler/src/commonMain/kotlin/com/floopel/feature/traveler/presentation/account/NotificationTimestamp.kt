package com.floopel.feature.traveler.presentation.account

import kotlin.time.Instant

/** Display an instant in the device time zone and UI locale, never as a raw transport timestamp. */
internal expect fun notificationTimestamp(instant: Instant, locale: String): String
