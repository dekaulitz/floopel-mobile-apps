package com.floopel.feature.traveler.presentation.trip

/** Formats the server's local dates directly: device time zones must not shift a Trip's day. */
internal fun tripCatalogDate(start: String, end: String, months: List<String>, includeYear: Boolean = true): String {
    if (months.size != 12 || !validTripDates(start, end)) return if (start == end) start else "$start – $end"
    fun day(value: String) = value.takeLast(2).toInt()
    fun month(value: String) = months[value.substring(5, 7).toInt() - 1]
    fun full(value: String) = "${day(value)} ${month(value)}" +
        if (includeYear || start.take(4) != end.take(4)) " ${value.take(4)}" else ""
    return when {
        start == end -> full(start)
        start.take(7) == end.take(7) -> "${day(start)}–${full(end)}"
        start.take(4) == end.take(4) -> "${day(start)} ${month(start)}–${full(end)}"
        else -> "${full(start)}–${full(end)}"
    }
}
