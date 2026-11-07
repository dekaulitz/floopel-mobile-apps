package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Admission affordances and the optional calendar phase come from backend time, never Android's device date. */
@Serializable
internal data class TripMemberCapabilitiesDto(
    val visibility: String,
    @SerialName("registration_opens_at") val registrationOpensAt: String?,
    @SerialName("registration_closes_at") val registrationClosesAt: String?,
    @SerialName("participant_limit") val participantLimit: Int,
    @SerialName("remaining_participants") val remainingParticipants: Int,
    @SerialName("can_invite") val canInvite: Boolean,
    @SerialName("invite_unavailable_reason") val inviteUnavailableReason: String?,
    @SerialName("can_edit_registration") val canEditRegistration: Boolean,
    @SerialName("schedule_state") val scheduleState: String? = null,
)
