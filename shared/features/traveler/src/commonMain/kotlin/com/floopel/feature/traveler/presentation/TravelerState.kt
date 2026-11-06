package com.floopel.feature.traveler.presentation

import com.floopel.feature.traveler.domain.model.*
import kotlinx.serialization.Serializable

@Serializable
internal enum class Destination {
    HOME, COMMUNITY, EXPLORER, OPEN_TRIPS, PROFILE,
    QUESTS, QUEST, QUEST_PROGRESS, QUEST_ACTIVITY, PASSPORT,
    ITINERARIES, ITINERARY, ITINERARY_FORM, ADD_SPOT, PICK_PLAN, STOP_FORM,
    SHARE, PUBLICATION, SHARE_LINK, TRIP_FORM, TRIPS, TRIP, MEMBERS, INVITATIONS,
    TRIP_APPLICATIONS, APPLICATIONS, APPLICATION, APPLY, NOTICE, NOTIFICATIONS,
    CATALOG, DETAIL, LOGIN, REGISTER, VERIFY, FORGOT, LEGAL,
    PUBLIC_PLAN, PUBLIC_PROFILE, SAVED_SPOTS, QUEST_GROUPS, QUEST_GROUP, QUEST_GROUP_INVITATIONS, QUEST_GROUP_INVITATION,
}

/** Only identifiers and non-sensitive navigation context are persisted. */
@Serializable
internal data class TravelerRoute(val destination: Destination, val id: String = "", val extra: String = "",
    val day: Int = 1, val inboxId: String? = null, val slug: String = "")

internal data class ReadState<T>(val value: T? = null, val loading: Boolean = false, val failure: ReadFailure? = null)
internal data class ReadFailure(val status: Int = 0, val code: String? = null, val retryAtMillis: Long = 0,
    val fields: List<CatalogFieldIssue> = emptyList())
internal data class HomeState(val spots: ReadState<List<CatalogEntry>> = ReadState(),
    val locations: ReadState<List<CatalogEntry>> = ReadState(), val collections: ReadState<List<CatalogEntry>> = ReadState(),
    val stories: ReadState<List<CatalogEntry>> = ReadState(), val quests: ReadState<LocationQuestPage> = ReadState())
internal data class SpotAdded(val itineraryId: String, val title: String, val day: Int)
internal data class TripCreationRecovery(val draft: TripDraft?, val trip: TravelerTrip?)

internal data class TravelerState(
    val tab: Destination = Destination.HOME,
    val stack: List<TravelerRoute> = listOf(TravelerRoute(Destination.HOME)),
    val identity: TravelerIdentityView = TravelerIdentityView(),
    val home: HomeState = HomeState(),
    val profile: ReadState<PersonalProfile> = ReadState(),
    val catalog: ReadState<CatalogPage> = ReadState(),
    val detail: ReadState<CatalogEntry> = ReadState(),
    val quests: ReadState<LocationQuestPage> = ReadState(),
    val quest: ReadState<QuestReader> = ReadState(),
    val itineraries: ReadState<List<OwnedItinerary>> = ReadState(),
    val itinerary: ReadState<OwnedItineraryDetail> = ReadState(),
    val itineraryDraft: ReadState<ItineraryDraft?> = ReadState(),
    val pendingStop: ReadState<PendingItineraryStop?> = ReadState(),
    val distribution: ReadState<ItineraryDistribution> = ReadState(),
    val shareLink: ReadState<ItineraryShareLink> = ReadState(),
    val trips: ReadState<List<TravelerTrip>> = ReadState(),
    val trip: ReadState<TravelerTrip> = ReadState(),
    val plan: ReadState<TripWorkingPlan> = ReadState(),
    val members: ReadState<List<TripMember>> = ReadState(),
    val invitations: ReadState<List<TripInvitation>> = ReadState(),
    val openTrips: ReadState<OpenTripPage> = ReadState(),
    val openTrip: ReadState<OpenTrip> = ReadState(),
    val openTripStops: ReadState<OpenTripStopPage> = ReadState(),
    val applications: ReadState<TripApplicationPage> = ReadState(),
    val application: ReadState<TripApplication> = ReadState(),
    val applicationDraft: ReadState<String?> = ReadState(),
    val notice: ReadState<TripOutcomeNotice> = ReadState(),
    val notifications: ReadState<NotificationPage> = ReadState(),
    val legal: ReadState<List<LegalDocument>> = ReadState(),
    val references: ReadState<RegistrationReferences> = ReadState(),
    val busy: Boolean = false,
    val writeFailure: ReadFailure? = null,
    val unknownOutcome: Boolean = false,
    val feedback: SpotAdded? = null,
    val saved: Boolean = false,
    val query: String = "",
    val tripTimezone: String? = null,
    val tripRecovery: ReadState<TripCreationRecovery> = ReadState(),
    val media: ReadState<CoverProcessing?> = ReadState(),
    val mediaPartial: Boolean = false,
    val stopCards: Map<String, CatalogEntry> = emptyMap(),
    val registrationStep: Int = 1,
    val verificationEmail: String = "",
    val unread: ReadState<Int> = ReadState(),
    val notificationClass: NotificationClass? = null,
    val pendingPublication: ReadState<ItineraryPublicationReview?> = ReadState(),
    val communityTab: Int = 0,
    val publicPlans: ReadState<List<PublicItinerarySummary>> = ReadState(),
    val publicPlan: ReadState<PublicItinerarySnapshot> = ReadState(),
    val publicProfile: ReadState<TravelerPublicProfile> = ReadState(),
    val publicProgress: ReadState<PublicProfileProgress> = ReadState(),
    val savedSpots: ReadState<SavedSpotPage> = ReadState(),
    val isSpotSaved: ReadState<Boolean> = ReadState(),
    val publicPage: Int = 1,
    val publicCanRequestNext: Boolean = true,
    val groups: ReadState<QuestGroupBatch<QuestGroup>> = ReadState(),
    val group: ReadState<QuestGroup> = ReadState(),
    val groupMembers: ReadState<QuestGroupBatch<QuestGroupMember>> = ReadState(),
    val groupInvitations: ReadState<QuestGroupBatch<QuestGroupInvitation>> = ReadState(),
    val groupInvitation: ReadState<QuestGroupInvitation> = ReadState(),
) {
    val route get() = stack.last()
    val canGoBack get() = stack.size > 1 || tab != Destination.HOME
}

internal fun <T> ReadState<T>.result(result: CatalogResult<T>, now: Long): ReadState<T> = when (result) {
    is CatalogResult.Ready -> ReadState(result.value)
    is CatalogResult.HttpError -> copy(value = value.takeUnless { result.status in setOf(401, 403, 404) }, loading = false, failure = ReadFailure(result.status, result.code,
        now + (result.retryAfterSeconds ?: 0) * 1000, result.fieldIssues))
    is CatalogResult.Maintenance -> copy(loading = false, failure = ReadFailure(503, retryAtMillis = now + (result.retryAfterSeconds ?: 0) * 1000))
    CatalogResult.InvalidResponse -> copy(loading = false, failure = ReadFailure(502))
    CatalogResult.NetworkError -> copy(loading = false, failure = ReadFailure())
}
