package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.*

/** Trips and invitation operations reuse existing Apps contracts and native session. */
interface TravelerTripRepository {
    /** Reads public-safe cards using server filters and opaque continuation. */
    suspend fun openTrips(query: OpenTripQuery): CatalogResult<OpenTripPage> = CatalogResult.InvalidResponse
    /** A public detail read never grants membership or access to private child resources. */
    suspend fun openTrip(id: String): CatalogResult<OpenTrip> = CatalogResult.InvalidResponse
    /** Reads the public route separately, including rating and visible comment totals. */
    suspend fun openTripStops(id: String, cursor: String? = null): CatalogResult<OpenTripStopPage> = CatalogResult.InvalidResponse
    /** Reads the owner or current-organizer application projection with fresh authorization. */
    suspend fun tripApplication(id: String): CatalogResult<TripApplication> = CatalogResult.InvalidResponse
    /** Reads only this profile's applications; pending rows do not consume a slot. */
    suspend fun myTripApplications(tripId: String? = null, cursor: String? = null): CatalogResult<TripApplicationPage> = CatalogResult.InvalidResponse
    /** Reads organizer decisions through the Trip-scoped endpoint, without inferring role locally. */
    suspend fun tripApplications(tripId: String, cursor: String? = null): CatalogResult<TripApplicationPage> = CatalogResult.InvalidResponse
    /** Submits a confirmed draft using a durable key; ambiguous outcomes retain the same intent. */
    suspend fun applyToOpenTrip(tripId: String, message: String, key: String): CatalogResult<TripApplication> = CatalogResult.InvalidResponse
    /** Returns this profile's interrupted application message without replaying the write. */
    suspend fun pendingOpenTripApplication(tripId: String): CatalogResult<String?> = CatalogResult.Ready(null)
    /** Reads a frozen applicant/organizer decision intent without issuing or replacing the command. */
    suspend fun pendingTripApplicationDecision(id: String): CatalogResult<TripApplicationDecision?> = CatalogResult.Ready(null)
    /** Rechecks the actor and application on the server; approval history never auto-rejoins a member. */
    suspend fun decideTripApplication(id: String, decision: TripApplicationDecision, key: String): CatalogResult<TripApplication> = CatalogResult.InvalidResponse
    /** Reads only the recipient's historical outcome; this does not authorize the private Trip. */
    suspend fun ownedTripNotice(id: String): CatalogResult<TripOutcomeNotice> = CatalogResult.InvalidResponse
    suspend fun createWithBanner(itineraryId: String, draft: TripDraft, key: String): CatalogResult<TravelerTrip> = create(itineraryId, draft, key)
    suspend fun pendingCreatedTrip(itineraryId: String): CatalogResult<TravelerTrip?> = CatalogResult.Ready(null)
    suspend fun completeTripCreation(id: String): CatalogResult<Unit> = CatalogResult.Ready(Unit)
    suspend fun bannerStatus(id: String): CatalogResult<TripBannerStatus> = CatalogResult.HttpError(404)
    suspend fun uploadBanner(id: String, bytes: ByteArray, mimeType: String, key: String): CatalogResult<TripBannerStatus> = CatalogResult.InvalidResponse
    suspend fun pendingDraft(sourceId: String, creating: Boolean): CatalogResult<TripDraft?> = CatalogResult.Ready(null)
    suspend fun reviewPendingMutation(): CatalogResult<List<TravelerTrip>> = mine()
    suspend fun startNewAction(): CatalogResult<Unit> = CatalogResult.Ready(Unit)
    suspend fun mine(): CatalogResult<List<TravelerTrip>>
    suspend fun detail(id: String): CatalogResult<TravelerTrip>
    suspend fun members(id: String): CatalogResult<List<TripMember>>
    suspend fun workingPlan(id: String): CatalogResult<TripWorkingPlan>
    suspend fun create(itineraryId: String, draft: TripDraft, key: String): CatalogResult<TravelerTrip>
    suspend fun update(id: String, draft: TripDraft, key: String): CatalogResult<TravelerTrip>
    suspend fun lifecycle(id: String, action: TripLifecycleAction, key: String): CatalogResult<Unit>
    suspend fun removeMember(id: String, profileId: String, key: String): CatalogResult<Unit>
    suspend fun transferOrganizer(id: String, profileId: String, key: String): CatalogResult<Unit>
    suspend fun invitations(id: String): CatalogResult<List<TripInvitation>>
    suspend fun myInvitations(): CatalogResult<List<TripInvitation>>
    suspend fun invite(id: String, recipient: String, key: String): CatalogResult<Unit>
    suspend fun resend(id: String, invitationId: String, key: String): CatalogResult<Unit>
    suspend fun revoke(id: String, invitationId: String, key: String): CatalogResult<Unit>
    suspend fun decide(invitationId: String, decision: InvitationDecision, key: String): CatalogResult<Unit>
    suspend fun block(invitationId: String, key: String): CatalogResult<Unit>
    suspend fun report(invitationId: String, reason: InvitationReportReason, details: String, key: String): CatalogResult<Unit>
}

/** Encrypted platform intent storage; never contains credentials or response bodies. */
interface TripRecoveryStore {
    fun read(): String?
    fun write(record: String)
    fun clear()
}
