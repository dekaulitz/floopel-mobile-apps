package com.floopel.feature.traveler.domain.model

import kotlin.time.Instant

/** Type is immutable for the Trip lifecycle; unknown values cannot enable registration editing. */
enum class TripVisibility(val wire: String) { PRIVATE("private"), PUBLIC("public"), UNKNOWN("") }

/** Read-only server snapshot; pending invitations do not reserve the remaining capacity. */
data class TripMemberCapabilities(
    val visibility: TripVisibility,
    val registrationOpensAt: Instant?,
    val registrationClosesAt: Instant?,
    val participantLimit: Int,
    val remainingParticipants: Int,
    val canInvite: Boolean,
    val inviteUnavailableReason: String?,
    val canEditRegistration: Boolean,
    val schedule: OpenTripSchedule = OpenTripSchedule.UNKNOWN,
)
