package com.floopel.feature.traveler.domain.model

import kotlin.time.Instant

/** Decisions are explicit commands; opening a reader never submits one. */
enum class TripApplicationDecision(val wire: String) { APPROVE("approve"), REJECT("reject"), WITHDRAW("withdraw") }

/** Reader identity is resolved from the authenticated profile, separately from the applicant's membership. */
enum class TripApplicationViewer { APPLICANT, ORGANIZER, UNKNOWN }

/** Own/current-organizer projection; message remains private and is never push copy. */
data class TripApplication(
    val id: String,
    val tripId: String,
    val profileId: String,
    val applicantUsername: String,
    val applicantDisplayName: String,
    val tripName: String,
    val startsOn: String,
    val endsOn: String,
    val timezone: String,
    val tripStatus: TripStatus?,
    val registrationOpensAt: Instant?,
    val registrationClosesAt: Instant?,
    val status: TripApplicationStatus,
    val message: String?,
    val membershipId: String?,
    val closedReason: String?,
    val currentMember: Boolean,
    val canApprove: Boolean,
    val approvalUnavailableReason: String?,
    val canWithdraw: Boolean,
    val admissionClosed: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val decidedAt: Instant?,
    val viewer: TripApplicationViewer = TripApplicationViewer.UNKNOWN,
)

/** Each page is authorized for either its owner or the Trip's current organizer. */
data class TripApplicationPage(val items: List<TripApplication>, val nextCursor: String?)
