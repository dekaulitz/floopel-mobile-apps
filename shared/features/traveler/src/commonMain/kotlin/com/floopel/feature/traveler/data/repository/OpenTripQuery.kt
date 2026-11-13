package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.domain.model.OpenTripQuery
import com.floopel.feature.traveler.domain.model.visibleCharacterCount
import io.ktor.http.encodeURLParameter

/** Encodes each complete query value so punctuation cannot become a second server filter. */
internal fun OpenTripQuery.toWireQuery(): String {
    require(limit in 1..100 && visibleCharacterCount(query) <= 120)
    require(locationId == null || validNotificationId(locationId))
    require(spotId == null || validNotificationId(spotId))
    require(cursor == null || cursor.length <= 256)
    require((fromDate == null) == (toDate == null))
    require(fromDate == null || validTripDate(fromDate) && validTripDate(toDate!!) && fromDate <= toDate)
    val fields = listOf("q" to query.trim().takeIf { it.isNotEmpty() }, "location_id" to locationId, "spot_id" to spotId,
        "cursor" to cursor, "limit" to limit.toString(), "from_date" to fromDate, "to_date" to toDate,
        "can_apply_only" to "true".takeIf { canApplyOnly })
    return fields.filter { it.second != null }.joinToString("&", prefix = "?") { (name, value) -> "$name=${value!!.encodeURLParameter()}" }
}

/** Application cursors are UUIDs and remain scoped to the explicit owner/organizer endpoint. */
internal fun applicationWireQuery(tripId: String?, cursor: String?, limit: Int): String {
    require(limit in 1..100)
    require(tripId == null || validNotificationId(tripId))
    require(cursor == null || validNotificationId(cursor))
    val fields = listOf("trip_id" to tripId, "cursor" to cursor, "limit" to limit.toString())
    return fields.filter { it.second != null }.joinToString("&", prefix = "?") { (name, value) -> "$name=${value!!.encodeURLParameter()}" }
}
