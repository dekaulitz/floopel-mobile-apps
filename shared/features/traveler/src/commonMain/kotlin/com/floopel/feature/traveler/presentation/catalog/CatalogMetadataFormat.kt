package com.floopel.feature.traveler.presentation.catalog

import kotlin.math.roundToLong

/** Display formatting only. Unknown/suppressed source values are handled before this boundary. */
internal fun catalogScore(value: Double, decimal: String): String {
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}$decimal${tenths % 10}"
}

internal fun catalogCount(value: Long, decimal: String): String {
    if (value < 1_000) return value.toString()
    if (value == 1_000L) return if (decimal == ",") "1.000" else "1,000"
    val (unit, suffix) = when {
        value >= 1_000_000_000 -> 1_000_000_000L to "B"
        value >= 1_000_000 -> 1_000_000L to "M"
        else -> 1_000L to "K"
    }
    return catalogScore(value.toDouble() / unit, decimal).removeSuffix(decimal + "0") + suffix
}
