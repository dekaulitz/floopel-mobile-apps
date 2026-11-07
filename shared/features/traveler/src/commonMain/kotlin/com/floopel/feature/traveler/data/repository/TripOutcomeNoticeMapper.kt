package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.data.dto.TripOutcomeNoticeDto
import com.floopel.feature.traveler.domain.model.TripApplicationStatus
import com.floopel.feature.traveler.domain.model.TripNoticeMembership
import com.floopel.feature.traveler.domain.model.TripNoticeOutcome
import com.floopel.feature.traveler.domain.model.TripOutcomeNotice
import com.floopel.feature.traveler.domain.model.TripStatus
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant

/** Missing nullable fields and quoted booleans are contract failures, not inferred defaults. */
internal fun decodeTripOutcomeNotice(data: JsonObject): TripOutcomeNoticeDto = TripOutcomeNoticeDto(
    id = data.wireText("id"), tripId = data.wireText("trip_id"), outcome = data.wireText("outcome"),
    tripName = data.wireText("trip_name"), startsOn = data.wireText("starts_on"), endsOn = data.wireText("ends_on"),
    timezone = data.wireText("timezone"), occurredAt = data.wireText("occurred_at"), tripStatus = data.wireText("trip_status"),
    membershipId = data.noticeNullableText("membership_id"), applicationId = data.noticeNullableText("application_id"),
    membershipStatus = data.noticeNullableText("membership_status"), applicationStatus = data.noticeNullableText("application_status"),
    currentMember = data.getValue("current_member").jsonPrimitive.also { require(!it.isString) }.boolean,
)

/** Keeps the exact historical episode without deriving current access from its status. */
internal fun TripOutcomeNoticeDto.toDomain(): TripOutcomeNotice {
    require(validNotificationId(id) && validNotificationId(tripId))
    require(validTripDate(startsOn) && validTripDate(endsOn) && startsOn <= endsOn)
    require(tripName.isNotBlank() && timezone.isNotBlank() && timezone.length <= 64)
    require((membershipId != null) xor (applicationId != null))
    require((membershipId == null) == (membershipStatus == null))
    require((applicationId == null) == (applicationStatus == null))
    require(membershipId == null || validNotificationId(membershipId))
    require(applicationId == null || validNotificationId(applicationId))
    val resolvedOutcome = TripNoticeOutcome.entries.firstOrNull { it.wire == outcome } ?: TripNoticeOutcome.UNKNOWN
    require(resolvedOutcome != TripNoticeOutcome.MEMBER_REMOVED || membershipId != null)
    return TripOutcomeNotice(id, tripId, resolvedOutcome, tripName, startsOn, endsOn, timezone,
        Instant.parse(occurredAt), TripStatus.entries.firstOrNull { it.wire == tripStatus }, membershipId, applicationId,
        membershipStatus?.let { value -> TripNoticeMembership.entries.firstOrNull { it.wire == value } ?: TripNoticeMembership.UNKNOWN },
        applicationStatus?.let { value -> TripApplicationStatus.entries.firstOrNull { it.wire == value } ?: TripApplicationStatus.UNKNOWN }, currentMember)
}

/** Null must be explicit in an authorized notice response. */
private fun JsonObject.noticeNullableText(key: String): String? =
    if (getValue(key) is JsonNull) null else wireText(key)
