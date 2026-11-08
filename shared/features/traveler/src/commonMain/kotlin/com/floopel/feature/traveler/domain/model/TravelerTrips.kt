package com.floopel.feature.traveler.domain.model

enum class TripRole(val wire: String) { ORGANIZER("organizer"), PARTICIPANT("participant") }
/** Caller access is separate from real membership; an invitee never becomes a TripMember. */
enum class TripCallerRole(val wire: String) { ORGANIZER("organizer"), PARTICIPANT("participant"), INVITEE("invitee") }
enum class TripStatus(val wire: String) { ACTIVE("active"), CANCELLED("cancelled"), COMPLETED("completed") }
enum class TripLifecycleAction(val wire: String) { LEAVE("leave"), CANCEL("cancel"), COMPLETE("complete") }
enum class InvitationDecision(val wire: String) { ACCEPT("accept"), DECLINE("decline") }
enum class InvitationReportReason(val wire: String) {
    UNWANTED("unwanted_invitation"), HARASSMENT("harassment"), SUSPICIOUS("suspicious_trip"), OTHER("other")
}
/** Geography is the current working itinerary coverage even for a completed Trip. */
data class TravelerTrip(val id: String, val workingItineraryId: String, val name: String, val startsOn: String, val endsOn: String,
    val timezone: String, val status: TripStatus, val role: TripCallerRole, val participantCount: Int,
    val bannerPhoto: CatalogPhoto? = null, val invitationId: String? = null, val locations: List<LocationReference> = emptyList(),
    val capabilities: TripMemberCapabilities? = null)
/** Traveler identity level is optional authoritative metadata; absent never implies level one. */
data class TripMember(val profileId: String, val username: String, val displayName: String, val role: TripRole, val active: Boolean, val level: Int? = null,
    val avatarUrl: String? = null)
data class InvitationItinerarySummary(val id: String, val title: String, val days: Int, val stops: Int)

/** Recipient invitation with optional public inviter level; decisions remain server-authorized. */
data class TripInvitation(val id: String, val tripId: String, val targetLabel: String, val status: String, val expiresAt: String,
    val tripName: String, val inviterUsername: String, val startsOn: String, val endsOn: String, val itinerary: InvitationItinerarySummary? = null, val inviterLevel: Int? = null,
    val inviterAvatarUrl: String? = null)
/** Recipient cursor is derived from the last row of a full server page; exact-size pages need an end read. */
data class TripInvitationPage(val items: List<TripInvitation>, val nextCursor: String?)
/** Authorized live plan with geography from its current working stops. */
data class TripWorkingPlan(val title: String, val stops: List<OwnedStop>, val coverPhoto: CatalogPhoto? = null,
    val locations: List<LocationReference> = emptyList())

/** Trip-owned image readiness, independent from the working itinerary cover. */
data class TripBannerStatus(val mediaId: String, val processing: CoverProcessing, val retryable: Boolean)
