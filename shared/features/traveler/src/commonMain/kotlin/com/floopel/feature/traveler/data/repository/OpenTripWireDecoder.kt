package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.data.dto.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Strict wire decoding precedes domain validation because tree serializers can coerce scalar strings. */
internal fun decodeOpenTrip(data: JsonObject): OpenTripDto = OpenTripDto(
    id = data.wireText("id"), name = data.wireText("name"), startsOn = data.wireText("starts_on"),
    endsOn = data.wireText("ends_on"), timezone = data.wireText("timezone"), scheduleState = data.wireText("schedule_state"),
    registrationOpensAt = data.tripNullableText("registration_opens_at"), registrationClosesAt = data.tripNullableText("registration_closes_at"),
    participantCount = data.tripInt("participant_count"), participantLimit = data.tripInt("participant_limit"),
    organizerCount = data.tripInt("organizer_count"), remainingParticipants = data.tripInt("remaining_participants"),
    organizer = decodeOpenTripOrganizer(data.getValue("organizer").jsonObject),
    locations = data.getValue("locations").jsonArray.map { decodeOpenTripLocation(it.jsonObject) },
    locationsTruncated = data.tripBoolean("locations_truncated"), bannerImage = data.tripNullableObject("banner_image"),
    previewSpot = data.tripNullableObject("preview_spot")?.let(::decodeOpenTripStop), viewerRole = data.wireText("viewer_role"),
    applicationId = data.tripNullableText("application_id"), applicationStatus = data.tripNullableText("application_status"),
    canApply = data.tripBoolean("can_apply"), applyUnavailableReason = data.tripNullableText("apply_unavailable_reason"),
    description = if ("description" in data) data.tripNullableText("description") else null,
    updatedAt = if ("updated_at" in data) data.wireText("updated_at") else null,
)

/** Public organizer text is not coerced from numeric or boolean JSON. */
private fun decodeOpenTripOrganizer(data: JsonObject) = OpenTripOrganizerDto(data.wireText("profile_id"), data.wireText("username"), data.wireText("display_name"))

/** Keeps the canonical administrative identity separate from Spot and Trip identifiers. */
private fun decodeOpenTripLocation(data: JsonObject) = LocationReferenceDto(data.wireText("id"), data.wireText("name"), data.wireText("slug"), data.wireText("level"))

/** Every public Spot includes the visible comment total; missing data never becomes zero. */
internal fun decodeOpenTripStop(data: JsonObject): OpenTripStopDto = OpenTripStopDto(
    spotId = data.wireText("spot_id"), name = data.wireText("name"), slug = data.wireText("slug"),
    location = decodeOpenTripLocation(data.getValue("location").jsonObject), day = data.tripInt("day"), position = data.tripInt("position"),
    latitude = data.tripNullableDouble("latitude"), longitude = data.tripNullableDouble("longitude"),
    referenceRating = data.tripNullableObject("reference_rating")?.let(::decodeOpenTripRating),
    commentTotal = data.tripLong("comment_total"), coverImage = data.tripNullableObject("cover_image"),
)

/** Preserves explicit suppression and nullable provenance without interpreting them as human ratings. */
private fun decodeOpenTripRating(data: JsonObject) = OpenTripRatingDto(data.tripNullableDouble("score"), data.tripNullableDouble("confidence"),
    data.tripNullableText("provided_at"), data.wireText("origin"), data.wireText("label"), data.tripBoolean("suppressed"))

/** Page metadata is required even for a successfully empty result. */
internal fun decodeOpenTripPage(data: JsonObject) = OpenTripPageDto(data.getValue("items").jsonArray.map { decodeOpenTrip(it.jsonObject) }, data.tripNullableText("next_cursor"))

/** Route pages keep their own continuation without changing the catalog cursor. */
internal fun decodeOpenTripStopPage(data: JsonObject) = OpenTripStopPageDto(data.getValue("items").jsonArray.map { decodeOpenTripStop(it.jsonObject) }, data.tripNullableText("next_cursor"))
