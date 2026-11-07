package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Public wire fields deliberately exclude private plans, notes and roster. */
@Serializable
internal data class OpenTripDto(
    val id: String,
    val name: String,
    @SerialName("starts_on") val startsOn: String,
    @SerialName("ends_on") val endsOn: String,
    val timezone: String,
    @SerialName("schedule_state") val scheduleState: String,
    @SerialName("registration_opens_at") val registrationOpensAt: String?,
    @SerialName("registration_closes_at") val registrationClosesAt: String?,
    @SerialName("participant_count") val participantCount: Int,
    @SerialName("participant_limit") val participantLimit: Int,
    @SerialName("organizer_count") val organizerCount: Int,
    @SerialName("remaining_participants") val remainingParticipants: Int,
    val organizer: OpenTripOrganizerDto,
    val locations: List<LocationReferenceDto>,
    @SerialName("locations_truncated") val locationsTruncated: Boolean,
    @SerialName("banner_image") val bannerImage: JsonObject?,
    @SerialName("preview_spot") val previewSpot: OpenTripStopDto?,
    @SerialName("viewer_role") val viewerRole: String,
    @SerialName("application_id") val applicationId: String?,
    @SerialName("application_status") val applicationStatus: String?,
    @SerialName("can_apply") val canApply: Boolean,
    @SerialName("apply_unavailable_reason") val applyUnavailableReason: String?,
    val description: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** Identifier is the real profile subject, not an account identifier. */
@Serializable
internal data class OpenTripOrganizerDto(@SerialName("profile_id") val profileId: String, val username: String,
    @SerialName("display_name") val displayName: String)

/** Spot wire metadata retains explicit unavailable rating and coordinates. */
@Serializable
internal data class OpenTripStopDto(@SerialName("spot_id") val spotId: String, val name: String, val slug: String,
    val location: LocationReferenceDto, val day: Int, val position: Int, val latitude: Double?, val longitude: Double?,
    @SerialName("reference_rating") val referenceRating: OpenTripRatingDto?,
    @SerialName("comment_total") val commentTotal: Long, @SerialName("cover_image") val coverImage: JsonObject?)

/** Suppressed AI reference is different from a known score or an absent reference. */
@Serializable
internal data class OpenTripRatingDto(val score: Double?, val confidence: Double?,
    @SerialName("provided_at") val providedAt: String?, val origin: String, val label: String, val suppressed: Boolean)

/** Cursors remain opaque wire values and never become client-side page arithmetic. */
@Serializable
internal data class OpenTripPageDto(val items: List<OpenTripDto>, @SerialName("next_cursor") val nextCursor: String?)

/** Public route result is separate from catalog results. */
@Serializable
internal data class OpenTripStopPageDto(val items: List<OpenTripStopDto>, @SerialName("next_cursor") val nextCursor: String?)
