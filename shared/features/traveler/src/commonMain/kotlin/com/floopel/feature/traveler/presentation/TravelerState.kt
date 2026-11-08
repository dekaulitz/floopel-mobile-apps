package com.floopel.feature.traveler.presentation

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerIntroProgress
import kotlinx.serialization.Serializable
import com.floopel.feature.traveler.presentation.catalog.LocationBrowserPage
import com.floopel.feature.traveler.presentation.catalog.LocationContentState
import com.floopel.feature.traveler.presentation.catalog.SpotContentState
import com.floopel.feature.traveler.presentation.quest.QuestBrowseState
import com.floopel.feature.traveler.presentation.passport.PassportContentState

@Serializable
internal enum class Destination {
    HOME, COMMUNITY, EXPLORER, OPEN_TRIPS, PROFILE,
    PROFILE_SETTINGS, PROFILE_EDIT, PROFILE_INTERESTS, PROFILE_SECURITY, PROFILE_EMERGENCY, PROFILE_PREFERENCES,
    QUESTS, QUEST_FILTER, QUEST, QUEST_PROGRESS, QUEST_ACTIVITY, PASSPORT,
    ITINERARIES, ITINERARY, ITINERARY_FORM, ADD_SPOT, PICK_PLAN, STOP_FORM,
    SHARE, PUBLICATION, SHARE_LINK, TRIP_FORM, TRIPS, TRIP, MEMBERS, INVITATIONS,
    TRIP_APPLICATIONS, APPLICATIONS, APPLICATION, APPLY, NOTICE, NOTIFICATIONS,
    NOTIFICATION_DEVICE, NOTIFICATION_OPEN, NOTIFICATION_DETAIL, OWNER_MODERATION,
    CATALOG, COUNTRIES, DETAIL, LOGIN, REGISTER, VERIFY, FORGOT, LEGAL,
    PUBLIC_PLAN, PUBLIC_PROFILE, SAVED_SPOTS, QUEST_GROUPS, QUEST_GROUP, QUEST_GROUP_INVITATIONS, QUEST_GROUP_INVITATION, QUEST_GROUP_RECOVERY,
}

/** Only identifiers and non-sensitive navigation context are persisted. */
@Serializable
internal data class TravelerRoute(val destination: Destination, val id: String = "", val extra: String = "",
    val day: Int = 1, val inboxId: String? = null, val slug: String = "", val operationKey: String = "",
    val countryId: String = "", val locationContextId: String = "")

internal data class ReadState<T>(val value: T? = null, val loading: Boolean = false, val failure: ReadFailure? = null)
internal data class ReadFailure(val status: Int = 0, val code: String? = null, val retryAtMillis: Long = 0,
    val fields: List<CatalogFieldIssue> = emptyList())
internal data class HomeState(val spots: ReadState<List<CatalogEntry>> = ReadState(),
    val locations: ReadState<List<CatalogEntry>> = ReadState(), val collections: ReadState<List<CatalogEntry>> = ReadState(),
    val stories: ReadState<List<CatalogEntry>> = ReadState(), val quests: ReadState<LocationQuestPage> = ReadState())
internal data class SpotAdded(val itineraryId: String, val title: String, val day: Int)
internal data class TripCreationRecovery(val draft: TripDraft?, val trip: TravelerTrip?)
internal data class TripMutationRecoveryState(val required: Boolean = false, val review: ReadState<List<TravelerTrip>> = ReadState())
internal data class ItineraryMutationRecoveryState(val required: Boolean = false, val review: ReadState<List<OwnedItinerary>> = ReadState())
internal data class ItineraryDayTitleDraft(val itineraryId: String, val day: Int, val value: String)
internal const val QuestGroupManagementMode = "manage"
internal val TravelerRoute.isQuestGroupManagement get() = destination == Destination.QUEST_GROUP && extra == QuestGroupManagementMode
internal data class QuestGroupManagementDraft(val groupId: String = "", val username: String = "")
internal data class QuestGroupMutationRecoveryState(val required: Boolean = false, val title: String? = null,
    val review: ReadState<QuestGroupRecoveryReview> = ReadState())
internal enum class MutationJournal { TRIP, ITINERARY, QUEST_GROUP }

internal data class TravelerState(
    val tab: Destination = Destination.HOME,
    val stack: List<TravelerRoute> = listOf(TravelerRoute(Destination.HOME)),
    val identity: TravelerIdentityView = TravelerIdentityView(),
    val identityRestored: Boolean = false,
    val intro: TravelerIntroProgress? = null,
    val introSaving: Boolean = false,
    val startupFailure: ReadFailure? = null,
    val home: HomeState = HomeState(),
    val profile: ReadState<PersonalProfile> = ReadState(),
    val passportContent: PassportContentState = PassportContentState(),
    val nationalities: ReadState<List<NationalityOption>> = ReadState(),
    val interests: ReadState<TravelerInterests> = ReadState(),
    val security: ReadState<CredentialCapabilities> = ReadState(),
    val emergency: ReadState<EmergencyContact?> = ReadState(),
    val preferences: ReadState<TravelerSettings> = ReadState(),
    val passwordSetupRetryAt: Long = 0,
    val catalog: ReadState<CatalogPage> = ReadState(),
    val spotScopeLocation: ReadState<CatalogEntry> = ReadState(),
    val detail: ReadState<CatalogEntry> = ReadState(),
    val locationBrowser: ReadState<LocationBrowserPage> = ReadState(),
    val locationCountries: ReadState<List<CatalogEntry>> = ReadState(),
    val locationContent: LocationContentState = LocationContentState(),
    val spotContent: SpotContentState = SpotContentState(),
    val quests: ReadState<QuestCatalogPage> = ReadState(),
    val questBrowse: QuestBrowseState = QuestBrowseState(),
    val questSpots: Map<String, ReadState<CatalogEntry>> = emptyMap(),
    val quest: ReadState<QuestReader> = ReadState(),
    val itineraries: ReadState<List<OwnedItinerary>> = ReadState(),
    val itinerary: ReadState<OwnedItineraryDetail> = ReadState(),
    val itineraryDraft: ReadState<ItineraryDraft?> = ReadState(),
    val dayTitleDraft: ItineraryDayTitleDraft? = null,
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
    val notification: ReadState<TravelerNotification> = ReadState(),
    val ownerModeration: ReadState<List<OwnerModerationAction>> = ReadState(),
    val notificationAppending: Boolean = false,
    val legal: ReadState<List<LegalDocument>> = ReadState(),
    val references: ReadState<RegistrationReferences> = ReadState(),
    val busy: Boolean = false,
    val writeFailure: ReadFailure? = null,
    val unknownOutcome: Boolean = false,
    val tripMutationRecovery: TripMutationRecoveryState = TripMutationRecoveryState(),
    val itineraryMutationRecovery: ItineraryMutationRecoveryState = ItineraryMutationRecoveryState(),
    val questGroupMutationRecovery: QuestGroupMutationRecoveryState = QuestGroupMutationRecoveryState(),
    val writeFailureJournal: MutationJournal? = null,
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
    val publicBadges: ReadState<PublicAchievementPage<PublicProfileBadge>> = ReadState(),
    val publicPrivatePlans: ReadState<List<OwnedItinerary>> = ReadState(),
    val savedSpots: ReadState<SavedSpotPage> = ReadState(),
    val isSpotSaved: ReadState<Boolean> = ReadState(),
    val publicPage: Int = 1,
    val publicCanRequestNext: Boolean = true,
    val groups: ReadState<QuestGroupBatch<QuestGroup>> = ReadState(),
    val group: ReadState<QuestGroup> = ReadState(),
    val groupManagementDraft: QuestGroupManagementDraft = QuestGroupManagementDraft(),
    val groupMembers: ReadState<QuestGroupBatch<QuestGroupMember>> = ReadState(),
    val groupInvitations: ReadState<QuestGroupBatch<QuestGroupInvitation>> = ReadState(),
    val groupInvitationsAppending: Boolean = false,
    val groupInvitationsAppendFailure: ReadFailure? = null,
    val groupInvitationsScrollIndex: Int = 0,
    val groupInvitationsScrollOffset: Int = 0,
    val groupInvitation: ReadState<QuestGroupInvitation> = ReadState(),
) {
    val route get() = stack.last()
    val ownerModerationVisible get() = route.destination == Destination.OWNER_MODERATION &&
        ownerModeration.value?.isNotEmpty() == true && !ownerModeration.loading && ownerModeration.failure == null
    val managesCurrentQuestGroup get() = identity.signedIn && route.destination == Destination.QUEST_GROUP &&
        group.value?.let { it.id == route.id && it.available && !group.loading && group.failure == null &&
            !profile.loading && profile.failure == null && profile.value?.profileId == it.managerProfileId } == true
    val showIntro get() = identityRestored && intro?.completed == false && !identity.signedIn
    val canGoBack get() = stack.size > 1 || identity.signedIn && tab != Destination.HOME
    val ownsPublicProfile get() = route.destination == Destination.PUBLIC_PROFILE && identity.signedIn &&
        profile.failure == null && !profile.loading && profile.value?.username == route.id &&
        publicProfile.failure == null && !publicProfile.loading && publicProfile.value?.username == route.id
    /** Unknown/loading/failed content is distinct from an authoritative, empty itinerary. */
    val publicationHasSpots: Boolean? get() = itinerary.value
        ?.takeIf { !itinerary.loading && itinerary.failure == null && it.stopsLoaded &&
            it.itinerary.id == route.id && it.itinerary.id == distribution.value?.id }
        ?.stops?.isNotEmpty()
}

internal fun <T> ReadState<T>.result(result: CatalogResult<T>, now: Long): ReadState<T> = when (result) {
    is CatalogResult.Ready -> ReadState(result.value)
    is CatalogResult.HttpError -> copy(value = value.takeUnless { result.status in setOf(401, 403, 404) }, loading = false, failure = ReadFailure(result.status, result.code,
        now + (result.retryAfterSeconds ?: 0) * 1000, result.fieldIssues))
    is CatalogResult.Maintenance -> copy(loading = false, failure = ReadFailure(503, retryAtMillis = now + (result.retryAfterSeconds ?: 0) * 1000))
    CatalogResult.InvalidResponse -> copy(loading = false, failure = ReadFailure(502))
    CatalogResult.NetworkError -> copy(loading = false, failure = ReadFailure())
}
