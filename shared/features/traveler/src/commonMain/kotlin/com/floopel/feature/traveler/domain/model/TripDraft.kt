package com.floopel.feature.traveler.domain.model

/** A creation intent asserts the first Spot's actual zone; no device or regional default is inferred. */
data class TripDraft(
    val name: String,
    val startsOn: String,
    val endsOn: String,
    val timezone: String = "",
    val visibility: TripVisibility = TripVisibility.PRIVATE,
    val registrationOpensAt: String? = null,
    val registrationClosesAt: String? = null,
    val approvedDescription: String? = null,
)
