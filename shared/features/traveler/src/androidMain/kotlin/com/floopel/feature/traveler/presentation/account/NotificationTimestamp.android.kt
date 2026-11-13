package com.floopel.feature.traveler.presentation.account

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Instant

internal actual fun notificationTimestamp(instant: Instant, locale: String): String =
    SimpleDateFormat("d MMM yyyy '·' HH.mm", Locale.forLanguageTag(locale)).format(Date(instant.toEpochMilliseconds()))
