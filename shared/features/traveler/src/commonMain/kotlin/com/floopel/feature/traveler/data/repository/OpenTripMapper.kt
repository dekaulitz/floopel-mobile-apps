package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.FloopelRestClient
import com.floopel.feature.traveler.data.dto.OpenTripDto
import com.floopel.feature.traveler.data.dto.OpenTripRatingDto
import com.floopel.feature.traveler.data.dto.OpenTripStopDto
import com.floopel.feature.traveler.domain.model.*
import kotlin.time.Instant

/** Maps only the public allowlist; admission remains the server's current capability. */
internal fun OpenTripDto.toDomain(rest: FloopelRestClient): OpenTrip {
    require(validNotificationId(id) && name.isNotBlank())
    require(validTripDate(startsOn) && validTripDate(endsOn) && startsOn <= endsOn && timezone.isNotBlank())
    require(participantLimit == 5 && organizerCount == 1 && participantCount in 0..participantLimit)
    require(remainingParticipants == participantLimit - participantCount)
    require(organizer.profileId.isNotBlank() && organizer.username.isNotBlank())
    require((registrationOpensAt == null) == (registrationClosesAt == null))
    val opens = registrationOpensAt?.let(Instant::parse)
    val closes = registrationClosesAt?.let(Instant::parse)
    require(opens == null || opens < closes!!)
    require(applicationId == null || validNotificationId(applicationId))
    require((applicationId == null) == (applicationStatus == null))
    val viewer = OpenTripViewer.entries.firstOrNull { it.wire == viewerRole } ?: OpenTripViewer.UNKNOWN
    val status = applicationStatus?.let { value -> TripApplicationStatus.entries.firstOrNull { it.wire == value } ?: TripApplicationStatus.UNKNOWN }
    require(!canApply || (viewer == OpenTripViewer.VISITOR && opens != null && applyUnavailableReason == null && status != TripApplicationStatus.PENDING))
    return OpenTrip(id, name, startsOn, endsOn, timezone,
        OpenTripSchedule.entries.firstOrNull { it.wire == scheduleState } ?: OpenTripSchedule.UNKNOWN,
        opens, closes, participantCount, participantLimit, organizerCount, remainingParticipants,
        OpenTripOrganizer(organizer.profileId, organizer.username, organizer.displayName), locations.map { it.toDomain() }, locationsTruncated,
        catalogMediaPhoto(bannerImage, rest, name), previewSpot?.toDomain(rest), viewer, applicationId, status, canApply, applyUnavailableReason,
        description, updatedAt?.let(Instant::parse))
}

/** Reuses the existing Spot renderer model while retaining every known rating/comment fact. */
internal fun OpenTripStopDto.toDomain(rest: FloopelRestClient): OpenTripStop {
    require(validNotificationId(spotId) && name.isNotBlank() && slug.isNotBlank())
    require(day >= 0 && position >= 0 && commentTotal >= 0)
    require((latitude == null) == (longitude == null))
    val point = latitude?.let { lat ->
        val lng = requireNotNull(longitude)
        require(lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0)
        GeoPoint(lat, lng)
    }
    val photo = catalogMediaPhoto(coverImage, rest, name)
    val place = location.toDomain()
    val spot = CatalogEntry(spotId, slug, name, place.name, CatalogKind.SPOT, photo?.url,
        geo = point, aiReference = referenceRating?.toDomain(), photos = listOfNotNull(photo),
        locationId = place.id, locationName = place.name, locationLevel = place.level, commentCount = commentTotal)
    return OpenTripStop(spot, place, day, position)
}

/** Suppressed AI values never become a zero score or a human rating. */
private fun OpenTripRatingDto.toDomain(): AiReferenceRating {
    require(origin == "ai")
    require(score == null || score.isFinite() && score in 0.0..5.0)
    require(confidence == null || confidence.isFinite() && confidence in 0.0..1.0)
    require(!suppressed || score == null)
    providedAt?.let(Instant::parse)
    return AiReferenceRating(score, suppressed)
}
