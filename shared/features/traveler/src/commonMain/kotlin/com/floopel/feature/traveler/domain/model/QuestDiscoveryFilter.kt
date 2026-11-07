package com.floopel.feature.traveler.domain.model

/** Discovery criteria; personal admission filtering requires native identity and never changes gameplay eligibility. */
data class QuestDiscoveryFilter(
    val minXP: Int? = null,
    val maxXP: Int? = null,
    val locationId: String? = null,
    val locationName: String? = null,
    val participationMode: String? = null,
    val excludeTaken: Boolean = false,
    val availability: String? = null,
) {
    init {
        require(participationMode == null || participationMode in setOf("personal", "group"))
        require(availability == null || availability in setOf("active", "upcoming", "ended"))
    }
    val activeCount: Int get() = (if (minXP != null || maxXP != null) 1 else 0) + (if (locationId != null) 1 else 0) +
        (if (excludeTaken) 1 else 0) + (if (availability != null) 1 else 0)
}
