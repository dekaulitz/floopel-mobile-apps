package com.floopel.feature.traveler.domain.model

import kotlin.time.Instant

/** Public discovery roles never imply access to the private working itinerary. */
enum class OpenTripViewer(val wire: String) {
    GUEST("guest"), VISITOR("visitor"), ORGANIZER("organizer"), PARTICIPANT("participant"), UNKNOWN("")
}

/** Schedule labels come from server time in the frozen Trip zone. */
enum class OpenTripSchedule(val wire: String) {
    UPCOMING("upcoming"), ONGOING("ongoing"), ENDED("ended"), UNAVAILABLE("unavailable"), UNKNOWN("")
}

/** Public organizer identity; contact information and roster are absent. */
data class OpenTripOrganizer(val profileId: String, val username: String, val displayName: String,
    val appearance: TravelerAvatarIdentity? = null)

/** One public route item keeps its ordering alongside the canonical Spot card projection. */
data class OpenTripStop(val spot: CatalogEntry, val location: LocationReference, val day: Int, val position: Int)

/** Read-only server capabilities; the Android device clock is not an admission authority. */
data class OpenTrip(
    val id: String,
    val name: String,
    val startsOn: String,
    val endsOn: String,
    val timezone: String,
    val schedule: OpenTripSchedule,
    val registrationOpensAt: Instant?,
    val registrationClosesAt: Instant?,
    val participantCount: Int,
    val participantLimit: Int,
    val organizerCount: Int,
    val remainingParticipants: Int,
    val organizer: OpenTripOrganizer,
    val locations: List<LocationReference>,
    val locationsTruncated: Boolean,
    val banner: CatalogPhoto?,
    val previewSpot: OpenTripStop?,
    val viewer: OpenTripViewer,
    val applicationId: String?,
    val applicationStatus: TripApplicationStatus?,
    val canApply: Boolean,
    val applyUnavailableReason: String?,
    val description: String? = null,
    val updatedAt: Instant? = null,
)

/** Filters and cursor are passed to the server before pagination. */
data class OpenTripQuery(val query: String = "", val locationId: String? = null, val spotId: String? = null,
    val cursor: String? = null, val limit: Int = 20, val fromDate: String? = null, val toDate: String? = null,
    val canApplyOnly: Boolean = false)

/** Opaque continuation is retained only after a successful page. */
data class OpenTripPage(val items: List<OpenTrip>, val nextCursor: String?)

/** Route pagination is independent from the public catalog page. */
data class OpenTripStopPage(val items: List<OpenTripStop>, val nextCursor: String?)
