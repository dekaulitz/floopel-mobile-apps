package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.data.dto.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Capability booleans and nullable history fields retain their exact JSON types. */
internal fun decodeTripApplication(data: JsonObject): TripApplicationDto = TripApplicationDto(
    id = data.wireText("id"), tripId = data.wireText("trip_id"), profileId = data.wireText("profile_id"),
    applicant = decodeApplicationApplicant(data.getValue("applicant").jsonObject), trip = decodeApplicationTrip(data.getValue("trip").jsonObject),
    status = data.wireText("status"), message = data.tripNullableText("message"), membershipId = data.tripNullableText("membership_id"),
    closedReason = data.tripNullableText("closed_reason"), currentMember = data.tripBoolean("current_member"), canApprove = data.tripBoolean("can_approve"),
    approvalUnavailableReason = data.tripNullableText("approval_unavailable_reason"), canWithdraw = data.tripBoolean("can_withdraw"),
    admissionClosed = data.tripBoolean("admission_closed"), createdAt = data.wireText("created_at"), updatedAt = data.wireText("updated_at"),
    decidedAt = data.tripNullableText("decided_at"),
)

/** Names belong to the profile supplied by the authorized server projection. */
private fun decodeApplicationApplicant(data: JsonObject) = TripApplicationApplicantDto(data.wireText("username"), data.wireText("display_name"))

/** Required Trip facts contain no private plan or roster fields. */
private fun decodeApplicationTrip(data: JsonObject) = TripApplicationTripDto(data.wireText("name"), data.wireText("starts_on"),
    data.wireText("ends_on"), data.wireText("timezone"), data.wireText("status"), data.tripNullableText("registration_opens_at"), data.tripNullableText("registration_closes_at"))

/** Lists are not coerced from null or string containers. */
internal fun decodeTripApplicationPage(data: JsonObject) = TripApplicationPageDto(data.getValue("items").jsonArray.map { decodeTripApplication(it.jsonObject) }, data.tripNullableText("next_cursor"))

/** Reads only server-produced member capabilities and their required source facts. */
internal fun decodeTripMemberCapabilities(data: JsonObject) = TripMemberCapabilitiesDto(data.wireText("visibility"),
    data.tripNullableText("registration_opens_at"), data.tripNullableText("registration_closes_at"), data.tripInt("participant_limit"),
    data.tripInt("remaining_participants"), data.tripBoolean("can_invite"), data.tripNullableText("invite_unavailable_reason"), data.tripBoolean("can_edit_registration"),
    data["schedule_state"]?.let { data.wireText("schedule_state") })
