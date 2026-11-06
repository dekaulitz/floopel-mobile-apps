package com.floopel.feature.traveler.testing

import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant

/** Test-only domain doubles, shared by host and emulator suites. Never packaged in the application. */
class TravelerFixture {
    val planId = "00000000-0000-4000-8000-000000000021"
    val spotId = "00000000-0000-4000-8000-000000000031"
    val tripId = "00000000-0000-4000-8000-000000000041"
    val noticeId = "00000000-0000-4000-8000-000000000051"
    val inboxId = "00000000-0000-4000-8000-000000000061"
    val publicationCalls = AtomicInteger()
    val createCalls = AtomicInteger()
    val ackCalls = AtomicInteger()
    val stopCalls = AtomicInteger()
    val workingPlanCalls = AtomicInteger()
    val tripCreateCalls = AtomicInteger()
    val bannerCalls = AtomicInteger()
    @Volatile var pendingCreatedTrip: TravelerTrip? = null
    @Volatile var bannerResult: CatalogResult<TripBannerStatus> = CatalogResult.Ready(TripBannerStatus("banner", CoverProcessing.READY, false))
    @Volatile var coverProcessing = CoverProcessing.READY
    @Volatile var activityKind = QuestActivityKind.QUESTION
    @Volatile var gpsSubmissionPending = false
    @Volatile var lastAcknowledged: String? = null
    @Volatile var plan = OwnedItineraryDetail(OwnedItinerary(planId, "Rencana budaya simulasi", 1, 1, emptyList(),
        "Rencana untuk pengujian UI", isOwner = true, visibility = "private"),
        listOf(OwnedStop("00000000-0000-4000-8000-000000000071", spotId, "Spot simulasi", 1, 1)))
    val spot = CatalogEntry(spotId, "spot-simulasi", "Spot simulasi", "Magelang", CatalogKind.SPOT, null,
        detailLoaded = true, locationName = "Magelang", timezone = "Asia/Jakarta")
    val notice = TripOutcomeNotice(noticeId, tripId, TripNoticeOutcome.MEMBER_REMOVED, "Trip simulasi",
        "2026-11-14", "2026-11-15", "Asia/Jakarta", Instant.parse("2026-11-07T00:00:00Z"), TripStatus.ACTIVE,
        "00000000-0000-4000-8000-000000000081", null, TripNoticeMembership.REMOVED, null, false)
    @Volatile var noticeResult: CatalogResult<TripOutcomeNotice> = CatalogResult.Ready(notice)
    val identityState = MutableStateFlow(TravelerIdentityView(signedIn = true, generation = 1,
        passport = TravelerPassport(100, 1, "Penjelajah", emptyList(), emptyList()), executionConfigured = true))
    @Volatile var trip = TravelerTrip(tripId, planId, "Trip simulasi", "2026-11-14", "2026-11-15", "Asia/Jakarta",
        TripStatus.ACTIVE, TripCallerRole.ORGANIZER, 0)
    private val identities = AtomicInteger(100)
    private val quest = QuestReader(QuestCard("quest", "quest-simulasi", "Jejak budaya simulasi", 200, null, 1, emptyList(), "personal"),
        "Pelajari cerita perjalanan.", "Selesaikan aktivitas sesuai petunjuk.", null, 5, 10, "active", "available",
        listOf(QuestStep("activity", "Pelajari cerita Spot", "question", 1)), emptyList())
    val identity: TravelerIdentity = double { name, args -> when (name) {
        "getView" -> identityState
        "getGeneration" -> identityState.value.generation
        "quest" -> CatalogResult.Ready(quest)
        "takeQuest", "continueQuest" -> {
            identityState.value = identityState.value.copy(running = QuestRunning("participation", quest.card.title, "active", 0, 1, mapOf("activity" to "available")), ownerStatus = "active"); Unit
        }
        "activity" -> { identityState.value = identityState.value.copy(question = QuestQuestion(args[0] as String,
            "Pelajari cerita Spot", activityKind, "Pilih jawaban.", listOf("a" to "Jawaban A", "b" to "Jawaban B"), "choice"),
            gpsSubmissionPending = gpsSubmissionPending); Unit }
        "answer" -> { identityState.value = identityState.value.copy(answerOutcome = "correct", completed = true); Unit }
        "logout" -> { identityState.value = TravelerIdentityView(generation = 2); Unit }
        else -> Unit
    } }
    val catalog: TravelerCatalog = double { name, args -> when (name) {
        "browse", "searchSpotPage", "countryPage", "nearbySpots", "curatedItineraries" -> CatalogResult.Ready(CatalogPage(listOf(spot), 1, 1))
        "activeQuests", "locationQuests", "spotQuests", "untakenLocationQuests" -> CatalogResult.Ready(LocationQuestPage(
            listOf(LocationQuest("quest", "quest-simulasi", quest.card.title, 200)), 1, 1))
        "detail" -> CatalogResult.Ready(spot.copy(id = (args[0] as CatalogEntry).id))
        "topLocations", "regions", "collections", "stories", "childRegions" -> CatalogResult.Ready(emptyList<CatalogEntry>())
        "search", "spots", "discoverySpots" -> CatalogResult.Ready(listOf(spot))
        else -> CatalogResult.NetworkError
    } }
    val itineraries: TravelerItineraryRepository = double { name, args -> when (name) {
        "mine" -> CatalogResult.Ready(listOf(plan.itinerary))
        "detail" -> CatalogResult.Ready(plan)
        "create" -> {
            createCalls.incrementAndGet(); val draft = args[0] as ItineraryDraft
            plan = OwnedItineraryDetail(plan.itinerary.copy(title = draft.title, description = draft.description, days = 0, stops = 0), emptyList())
            ItineraryWriteResult.Completed(plan)
        }
        "addStop" -> {
            stopCalls.incrementAndGet(); val day = args[2] as Int
            plan = plan.copy(itinerary = plan.itinerary.copy(stops = plan.stops.size + 1, days = maxOf(plan.itinerary.days, day)),
                stops = plan.stops + OwnedStop("added-stop", args[1] as String, spot.title, day, 1))
            ItineraryWriteResult.Completed(plan)
        }
        "updateStop" -> { val stop = args[1] as OwnedStop; plan = plan.copy(stops = plan.stops.map { if (it.id == stop.id) stop else it }); CatalogResult.Ready(plan) }
        "update" -> { val draft = args[1] as ItineraryDraft; plan = plan.copy(itinerary = plan.itinerary.copy(title = draft.title, description = draft.description)); CatalogResult.Ready(plan) }
        "titleDay" -> { plan = plan.copy(dayTitles = plan.dayTitles + ((args[1] as Int) to (args[2] as String))); CatalogResult.Ready(plan) }
        "pendingDraft", "pendingStop" -> CatalogResult.Ready(null)
        else -> CatalogResult.Ready(Unit)
    } }
    val distribution: TravelerItineraryDistributionRepository = double { name, _ -> when (name) {
        "pendingPublication" -> CatalogResult.Ready(null)
        "publish" -> { publicationCalls.incrementAndGet(); CatalogResult.Ready(distributionValue("public")) }
        "read" -> CatalogResult.Ready(distributionValue("private"))
        "cover", "uploadCover" -> CatalogResult.Ready(ItineraryCoverStatus("cover", coverProcessing, false))
        "shareLink", "rotateShareLink" -> CatalogResult.Ready(ItineraryShareLink("link", true, 1, "hint", "https://fixture.invalid/i/share/synthetic"))
        else -> CatalogResult.NetworkError
    } }
    val trips: TravelerTripRepository = double { name, _ -> when (name) {
        "mine" -> CatalogResult.Ready(listOf(trip))
        "detail" -> CatalogResult.Ready(trip)
        "workingPlan" -> { workingPlanCalls.incrementAndGet(); CatalogResult.Ready(TripWorkingPlan(plan.itinerary.title, plan.stops)) }
        "pendingDraft" -> CatalogResult.Ready(null)
        "pendingCreatedTrip" -> CatalogResult.Ready(pendingCreatedTrip)
        "create" -> { tripCreateCalls.incrementAndGet(); CatalogResult.Ready(trip) }
        "completeTripCreation" -> { pendingCreatedTrip = null; CatalogResult.Ready(Unit) }
        "uploadBanner" -> { bannerCalls.incrementAndGet(); bannerResult }
        "bannerStatus" -> CatalogResult.HttpError(404)
        "pendingOpenTripApplication" -> CatalogResult.Ready(null)
        "ownedTripNotice" -> noticeResult
        "members" -> CatalogResult.Ready(emptyList<TripMember>())
        "openTrips" -> CatalogResult.Ready(OpenTripPage(emptyList(), null))
        else -> CatalogResult.NetworkError
    } }
    val notifications: TravelerNotificationRepository = double { name, args -> when (name) {
        "notifications" -> CatalogResult.Ready(NotificationPage(listOf(notification()), 1, 20, 1, 1))
        "notification" -> CatalogResult.Ready(notification())
        "unreadNotifications" -> CatalogResult.Ready(1)
        "markNotificationRead" -> { ackCalls.incrementAndGet(); lastAcknowledged = args[0] as String; CatalogResult.Ready(Instant.parse("2026-11-07T01:00:00Z")) }
        else -> CatalogResult.NetworkError
    } }
    private val account: TravelerAccountRepository = double { name, _ -> when (name) {
        "profile" -> CatalogResult.Ready(PersonalProfile("Traveler", "Simulasi", "traveler_simulasi", "ID"))
        else -> CatalogResult.NetworkError
    } }
    private val legal: TravelerLegalRepository = double { name, _ -> if (name == "pending") CatalogResult.Ready(emptyList<LegalDocument>()) else CatalogResult.Ready(Unit) }
    private val registration: TravelerRegistrationRepository = double { _, _ -> RegistrationResult.Failed(503) }
    private val publicContent: TravelerPublicRepository = double { _, _ -> CatalogResult.NetworkError }
    private val savedSpots: TravelerSavedSpotRepository = double { _, _ -> CatalogResult.NetworkError }
    private val questGroups: TravelerQuestGroupRepository = double { _, _ -> CatalogResult.NetworkError }
    val runtime = TravelerRuntime(identity, catalog, itineraries, distribution, trips, notifications, account, legal, registration, publicContent, savedSpots, questGroups,
        { "00000000-0000-4000-8000-${identities.incrementAndGet().toString().padStart(12, '0')}" })

    private fun distributionValue(visibility: String) = ItineraryDistribution(planId, plan.itinerary.title, plan.itinerary.description,
        visibility, true, "active", false, null, "2026-11-06T00:00:00Z", emptyList(), plan.stops.size)
    private fun notification() = TravelerNotification(inboxId, "trip.member_removed", NotificationClass.ACTIVITY,
        "Keanggotaan Trip diakhiri", "Periksa status pribadimu.", "/me/trip-notices/$noticeId",
        Instant.parse("2026-11-07T00:00:00Z"), Instant.parse("2026-11-07T00:00:00Z"), null)
}

/** JVM reflection is confined to test boundaries to keep unrelated repository operations explicit failures. */
private inline fun <reified T : Any> double(crossinline answer: (String, Array<out Any?>) -> Any?): T {
    val type = T::class.java
    return requireNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
        when (method.name) { "toString" -> "Synthetic ${type.simpleName}"; "hashCode" -> type.hashCode(); "equals" -> false
            else -> answer(method.name, args ?: emptyArray()) }
    }))
}
