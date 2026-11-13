package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.data.dto.TripApplicationDto
import com.floopel.feature.traveler.domain.model.TripApplication
import com.floopel.feature.traveler.domain.model.TripApplicationStatus
import com.floopel.feature.traveler.domain.model.TripStatus
import kotlin.time.Instant

/** Preserves server capabilities and history without treating approval as current membership. */
internal fun TripApplicationDto.toDomain(): TripApplication {
    require(validNotificationId(id) && validNotificationId(tripId) && profileId.isNotBlank())
    require(validTripDate(trip.startsOn) && validTripDate(trip.endsOn) && trip.startsOn <= trip.endsOn)
    require(trip.name.isNotBlank() && trip.timezone.isNotBlank())
    require(membershipId == null || validNotificationId(membershipId))
    require((trip.registrationOpensAt == null) == (trip.registrationClosesAt == null))
    val resolved = TripApplicationStatus.entries.firstOrNull { it.wire == status } ?: TripApplicationStatus.UNKNOWN
    require(!canApprove || resolved == TripApplicationStatus.PENDING && !admissionClosed && !currentMember && approvalUnavailableReason == null)
    require(!canWithdraw || resolved == TripApplicationStatus.PENDING)
    when (resolved) {
        TripApplicationStatus.PENDING -> require(decidedAt == null && membershipId == null && closedReason == null && !currentMember)
        TripApplicationStatus.APPROVED -> require(decidedAt != null && membershipId != null && closedReason == null)
        TripApplicationStatus.CLOSED -> require(decidedAt != null && membershipId != null && closedReason != null)
        TripApplicationStatus.REJECTED, TripApplicationStatus.WITHDRAWN -> require(decidedAt != null && membershipId == null && closedReason == null)
        TripApplicationStatus.UNKNOWN -> require(!canApprove && !canWithdraw)
    }
    val opens = trip.registrationOpensAt?.let(Instant::parse)
    val closes = trip.registrationClosesAt?.let(Instant::parse)
    require(opens == null || opens < closes!!)
    return TripApplication(id, tripId, profileId, applicant.username, applicant.displayName, trip.name,
        trip.startsOn, trip.endsOn, trip.timezone, TripStatus.entries.firstOrNull { it.wire == trip.status }, opens, closes,
        resolved, message, membershipId, closedReason, currentMember, canApprove, approvalUnavailableReason, canWithdraw,
        admissionClosed, Instant.parse(createdAt), Instant.parse(updatedAt), decidedAt?.let(Instant::parse))
}
