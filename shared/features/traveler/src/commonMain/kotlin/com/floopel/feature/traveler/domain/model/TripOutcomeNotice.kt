package com.floopel.feature.traveler.domain.model

import kotlin.time.Instant

/** Unknown outcomes remain readable history and must not enable a business action. */
enum class TripNoticeOutcome(val wire: String) {
    CANCELLED("cancelled"), SCHEDULE_CHANGED("schedule_changed"), MEMBER_REMOVED("member_removed"), UNKNOWN("")
}

/** The ended episode is independent of current membership after an explicit rejoin. */
enum class TripNoticeMembership(val wire: String) {
    ACTIVE("active"), LEFT("left"), REMOVED("removed"), UNKNOWN("")
}

/** Own application history never grants access to the Trip's private working plan. */
enum class TripApplicationStatus(val wire: String) {
    PENDING("pending"), APPROVED("approved"), REJECTED("rejected"), WITHDRAWN("withdrawn"), CLOSED("closed"), UNKNOWN("")
}

/** Recipient-authorized snapshot; opening the full Trip still requires a fresh member read. */
data class TripOutcomeNotice(
    val id: String,
    val tripId: String,
    val outcome: TripNoticeOutcome,
    val tripName: String,
    val startsOn: String,
    val endsOn: String,
    val timezone: String,
    val occurredAt: Instant,
    val tripStatus: TripStatus?,
    val membershipId: String?,
    val applicationId: String?,
    val membershipStatus: TripNoticeMembership?,
    val applicationStatus: TripApplicationStatus?,
    val currentMember: Boolean,
)
