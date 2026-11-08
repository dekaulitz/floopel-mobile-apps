package com.floopel.feature.traveler.testing

import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant

/** All mutations are synthetic; recipient history deliberately exceeds the existing server page size. */
class TravelerTripInboxFixture {
    val base = TravelerFixture()
    val cursors = CopyOnWriteArrayList<String?>()
    val applicationReads = AtomicInteger()
    val applyCalls = AtomicInteger()
    val decisions = CopyOnWriteArrayList<TripApplicationDecision>()
    val invitationDecisions = CopyOnWriteArrayList<Pair<String, InvitationDecision>>()
    var beforeInvitations: suspend (String?) -> Unit = {}
    var beforeApplication: suspend () -> Unit = {}
    @Volatile var appendFailure: CatalogResult<TripInvitationPage>? = null
    @Volatile var applicationFailure: CatalogResult<TripApplication>? = null
    @Volatile var publicTrip = base.openTrip
    @Volatile var invitations = (1..21).map { index -> TripInvitation(
        "00000000-0000-4000-8000-${(1000 + index).toString().padStart(12, '0')}", base.tripId, "traveler_simulasi",
        if (index == 21) "pending" else "accepted", "2099-01-01T00:00:00Z", "Trip undangan $index", "arif_simulasi", "2026-11-14", "2026-11-16") }
    @Volatile var application = TripApplication(id = "00000000-0000-4000-8000-000000001201", tripId = base.tripId,
        profileId = "fixture-person", applicantUsername = "traveler_simulasi", applicantDisplayName = "Traveler Simulasi",
        tripName = base.openTrip.name, startsOn = "2026-11-14", endsOn = "2026-11-16", timezone = "Asia/Jakarta",
        tripStatus = TripStatus.ACTIVE, registrationOpensAt = null, registrationClosesAt = null,
        status = TripApplicationStatus.PENDING, message = "Catatan privat simulasi", membershipId = null, closedReason = null,
        currentMember = false, canApprove = false, approvalUnavailableReason = null, canWithdraw = true, admissionClosed = false,
        createdAt = Instant.parse("2026-11-08T00:00:00Z"), updatedAt = Instant.parse("2026-11-08T00:00:00Z"), decidedAt = null,
        viewer = TripApplicationViewer.APPLICANT)
    val trips = object : TravelerTripRepository by base.trips {
        override suspend fun myInvitations(cursor: String?, limit: Int): CatalogResult<TripInvitationPage> {
            cursors += cursor; beforeInvitations(cursor)
            if (cursor != null) appendFailure?.let { return it }
            val start = if (cursor == null) 0 else invitations.indexOfFirst { it.id == cursor }.takeIf { it >= 0 }?.plus(1) ?: invitations.size
            val page = invitations.drop(start).take(limit)
            return CatalogResult.Ready(TripInvitationPage(page, page.lastOrNull()?.id?.takeIf { page.size == limit }))
        }
        override suspend fun openTrip(id: String): CatalogResult<OpenTrip> = CatalogResult.Ready(publicTrip)
        override suspend fun tripApplication(id: String): CatalogResult<TripApplication> {
            applicationReads.incrementAndGet(); beforeApplication(); return applicationFailure ?: CatalogResult.Ready(application)
        }
        override suspend fun myTripApplications(tripId: String?, cursor: String?): CatalogResult<TripApplicationPage> =
            CatalogResult.Ready(TripApplicationPage(listOf(application), null))
        override suspend fun applyToOpenTrip(tripId: String, message: String, key: String): CatalogResult<TripApplication> {
            applyCalls.incrementAndGet(); publicTrip = publicTrip.copy(applicationId = application.id, canApply = false)
            return CatalogResult.Ready(application)
        }
        override suspend fun decideTripApplication(id: String, decision: TripApplicationDecision, key: String): CatalogResult<TripApplication> {
            decisions += decision
            application = application.copy(status = TripApplicationStatus.WITHDRAWN, canWithdraw = false)
            return CatalogResult.Ready(application)
        }
        override suspend fun decide(invitationId: String, decision: InvitationDecision, key: String): CatalogResult<Unit> {
            invitationDecisions += invitationId to decision; return CatalogResult.Ready(Unit)
        }
    }
    val runtime = base.runtime.let { r -> TravelerRuntime(r.identity, r.catalog, r.itineraries, r.distribution, trips,
        r.notifications, r.account, r.legal, r.registration, r.publicContent, r.savedSpots, r.questGroups, r.newOperationKey, r.startup) }
}
