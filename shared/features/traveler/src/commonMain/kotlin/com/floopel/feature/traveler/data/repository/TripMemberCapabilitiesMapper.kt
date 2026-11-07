package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.data.dto.TripMemberCapabilitiesDto
import com.floopel.feature.traveler.domain.model.TripCallerRole
import com.floopel.feature.traveler.domain.model.TripMemberCapabilities
import com.floopel.feature.traveler.domain.model.TripStatus
import com.floopel.feature.traveler.domain.model.TripVisibility
import com.floopel.feature.traveler.domain.model.OpenTripSchedule
import kotlin.time.Instant

/** Validates coherent capabilities while preserving server time as their sole authority. */
internal fun TripMemberCapabilitiesDto.toDomain(count: Int, role: TripCallerRole, status: TripStatus): TripMemberCapabilities {
    require(participantLimit == 5 && count in 0..participantLimit && remainingParticipants == participantLimit - count)
    require((registrationOpensAt == null) == (registrationClosesAt == null))
    val visibility = TripVisibility.entries.firstOrNull { it.wire == this.visibility } ?: TripVisibility.UNKNOWN
    val opens = registrationOpensAt?.let(Instant::parse)
    val closes = registrationClosesAt?.let(Instant::parse)
    require(opens == null || opens < closes!!)
    require(visibility != TripVisibility.PUBLIC || opens != null)
    require(visibility != TripVisibility.PRIVATE || opens == null)
    require(!canInvite || role == TripCallerRole.ORGANIZER && status == TripStatus.ACTIVE && inviteUnavailableReason == null)
    require(!canEditRegistration || visibility == TripVisibility.PUBLIC && role == TripCallerRole.ORGANIZER && status == TripStatus.ACTIVE && opens != null)
    return TripMemberCapabilities(visibility, opens, closes, participantLimit, remainingParticipants,
        canInvite, inviteUnavailableReason, canEditRegistration,
        OpenTripSchedule.entries.firstOrNull { it.wire == scheduleState } ?: OpenTripSchedule.UNKNOWN)
}
