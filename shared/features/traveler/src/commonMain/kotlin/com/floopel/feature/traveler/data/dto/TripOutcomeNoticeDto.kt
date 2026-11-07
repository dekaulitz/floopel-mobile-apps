package com.floopel.feature.traveler.data.dto

/** Required wire fields retain nullability and raw status values until Data mapping. */
internal data class TripOutcomeNoticeDto(
    val id: String,
    val tripId: String,
    val outcome: String,
    val tripName: String,
    val startsOn: String,
    val endsOn: String,
    val timezone: String,
    val occurredAt: String,
    val tripStatus: String,
    val membershipId: String?,
    val applicationId: String?,
    val membershipStatus: String?,
    val applicationStatus: String?,
    val currentMember: Boolean,
)
