package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The server trims and bounds the optional Unicode message before storing it encrypted. */
@Serializable
internal data class ApplyOpenTripDto(val message: String?)

/** Nullable fields are required on the wire; omission must not invent an empty application. */
@Serializable
internal data class TripApplicationDto(
    val id: String, @SerialName("trip_id") val tripId: String, @SerialName("profile_id") val profileId: String,
    val applicant: TripApplicationApplicantDto, val trip: TripApplicationTripDto, val status: String, val message: String?,
    @SerialName("membership_id") val membershipId: String?, @SerialName("closed_reason") val closedReason: String?,
    @SerialName("current_member") val currentMember: Boolean, @SerialName("can_approve") val canApprove: Boolean,
    @SerialName("approval_unavailable_reason") val approvalUnavailableReason: String?,
    @SerialName("can_withdraw") val canWithdraw: Boolean, @SerialName("admission_closed") val admissionClosed: Boolean,
    @SerialName("created_at") val createdAt: String, @SerialName("updated_at") val updatedAt: String,
    @SerialName("decided_at") val decidedAt: String?,
)

/** Profile identity is provided by the authorized application projection. */
@Serializable
internal data class TripApplicationApplicantDto(val username: String, @SerialName("display_name") val displayName: String)

/** Current Trip facts do not include the working itinerary or roster. */
@Serializable
internal data class TripApplicationTripDto(val name: String, @SerialName("starts_on") val startsOn: String,
    @SerialName("ends_on") val endsOn: String, val timezone: String, val status: String,
    @SerialName("registration_opens_at") val registrationOpensAt: String?,
    @SerialName("registration_closes_at") val registrationClosesAt: String?)

/** Own and organizer lists share the wire shape but retain separate authorized requests. */
@Serializable
internal data class TripApplicationPageDto(val items: List<TripApplicationDto>, @SerialName("next_cursor") val nextCursor: String?)
