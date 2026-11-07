package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerIntroProgress
import com.floopel.feature.traveler.domain.repository.TravelerTripRepository
import com.floopel.feature.traveler.domain.repository.TravelerItineraryRepository
import com.floopel.feature.traveler.domain.repository.TravelerQuestGroupRepository
import com.floopel.feature.traveler.presentation.catalog.LocationBrowserPage
import com.floopel.feature.traveler.presentation.catalog.LocationContentState
import com.floopel.feature.traveler.presentation.catalog.SpotContentState
import com.floopel.feature.traveler.presentation.catalog.SpotReportState
import com.floopel.feature.traveler.presentation.quest.*
import com.floopel.feature.traveler.presentation.passport.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/** One retained screen state owner; every remote operation crosses a domain repository boundary. */
internal class TravelerViewModel(
    private val runtime: TravelerRuntime,
    restoredStack: String = "",
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    autoStart: Boolean = true,
) : ViewModel() {
    private val restored = runCatching { Json.decodeFromString<List<TravelerRoute>>(restoredStack) }
        .getOrNull()?.takeIf { routes -> routes.isNotEmpty() && routes.size <= 40 && routes.all {
            it.id.length <= 200 && it.extra.length <= 200 && it.operationKey.length <= 128 && it.countryId.length <= 200 && it.locationContextId.length <= 200
        } }?.map { route ->
            // A pre-key saved route must recover to the same identity on every restoration.
            if (route.destination == Destination.TRIP_FORM && route.operationKey.isBlank())
                route.copy(operationKey = "restored-trip-create:${route.id}") else route
        }
    private val mutable = MutableStateFlow(TravelerState(tab = restored?.firstOrNull()?.destination?.takeIf { it in rootTabs } ?: Destination.HOME,
        stack = restored ?: listOf(TravelerRoute(Destination.HOME)),
        questGroupMutationRecovery = QuestGroupMutationRecoveryState(required = restored?.lastOrNull()?.destination == Destination.QUEST_GROUP_RECOVERY),
        identityRestored = !autoStart))
    val state = mutable.asStateFlow()
    private val reads = mutableMapOf<String, Job>()
    private val acknowledgements = mutableSetOf<Pair<Long, String>>()
    private var writeJob: Job? = null
    private var registrationKey: String? = null
    private var startupJob: Job? = null
    private val locationQueries = mutableMapOf<TravelerRoute, String>()
    private val spotQueries = mutableMapOf<TravelerRoute, String>()
    private var groupInvitationsRoute: TravelerRoute? = null

    private var language = "id"
    private val spotReports = mutableMapOf<Pair<Long, String>, SpotReportState>()

    init {
        if (autoStart) {
            viewModelScope.launch {
                runtime.identity.view.collect { identity ->
                    if (state.value.identity.generation != identity.generation || state.value.identity.signedIn != identity.signedIn) {
                        reads.values.forEach(Job::cancel); reads.clear()
                        writeJob?.cancel()
                        acknowledgements.clear()
                        locationQueries.clear(); spotQueries.clear(); spotReports.clear()
                        groupInvitationsRoute = null
                    }
                    mutable.update { current ->
                        if (current.identity.generation != identity.generation) {
                            val routeStack = if (current.identity.signedIn && !identity.signedIn)
                                listOf(TravelerRoute(Destination.LOGIN)) else current.stack
                            TravelerState(tab = rootTab(routeStack), stack = routeStack, identity = identity, identityRestored = current.identityRestored,
                                intro = current.intro, introSaving = current.introSaving, startupFailure = current.startupFailure)
                        } else if (current.identityRestored && !identity.signedIn && !current.route.destination.isAuthenticationScreen())
                            TravelerState(stack = listOf(TravelerRoute(Destination.LOGIN)), identity = identity, identityRestored = true,
                                intro = current.intro, introSaving = current.introSaving, startupFailure = current.startupFailure)
                        else current.copy(identity = identity)
                    }
                    if (state.value.identityRestored && !identity.signedIn) requireLogin()
                }
            }
            startupJob = viewModelScope.launch {
                try { withContext(worker) { runtime.identity.restore() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutable.update { it.copy(writeFailure = ReadFailure()) } }
                val identity = runtime.identity.view.first()
                mutable.update { current ->
                    val stack = if (identity.signedIn) signedInStack(current.stack) else authenticationStack(current.stack)
                    current.copy(identity = identity, identityRestored = true, stack = stack, tab = rootTab(stack),
                        questGroupMutationRecovery = if (identity.signedIn && stack.last().destination == Destination.QUEST_GROUP_RECOVERY)
                            QuestGroupMutationRecoveryState(required = true) else current.questGroupMutationRecovery)
                }
                loadIntro()
            }
        }
    }

    suspend fun changeLanguage(value: String) {
        require(value in setOf("id", "en"))
        groupInvitationsRoute = null
        withContext(worker) { runtime.identity.setLanguage(value) }
        if (language == value) return
        language = value
        reads.values.forEach(Job::cancel); reads.clear()
        mutable.update { it.copy(home = HomeState(), nationalities = ReadState(), interests = ReadState(),
            catalog = ReadState(), spotScopeLocation = ReadState(), detail = ReadState(), locationBrowser = ReadState(),
            locationCountries = ReadState(), locationContent = LocationContentState(), spotContent = SpotContentState(),
            quests = ReadState(), quest = ReadState(), questSpots = emptyMap(), publicBadges = ReadState(),
            notifications = ReadState(), notification = ReadState(), ownerModeration = ReadState(), legal = ReadState(), references = ReadState(),
            group = ReadState(), groupInvitations = ReadState(), groupInvitation = ReadState()) }
        refresh()
    }

    // Report drafts are memory-only and actor-bound. An uncertain catalog POST is never repeated.
    fun openSpotReport() {
        val id = state.value.route.id
        val prior = spotReports[runtime.identity.generation to id] ?: SpotReportState()
        mutable.update { it.copy(spotContent = it.spotContent.copy(report = prior.copy(open = true))) }
    }
    fun closeSpotReport() {
        if (state.value.spotContent.report.result.loading) return
        mutable.update { it.copy(spotContent = it.spotContent.copy(report = it.spotContent.report.copy(open = false))) }
    }
    fun spotReportReason(value: CatalogReportReason) {
        val current = state.value.spotContent.report
        if (!current.uncertain && !current.result.loading && current.result.value == null)
            mutable.update { it.copy(spotContent = it.spotContent.copy(report = current.copy(reason = value))) }
    }
    fun spotReportDetails(value: String) {
        val current = state.value.spotContent.report
        if (!current.uncertain && !current.result.loading && current.result.value == null)
            mutable.update { it.copy(spotContent = it.spotContent.copy(report = current.copy(details = value))) }
    }
    fun submitSpotReport() {
        val current = state.value.spotContent.report
        val reason = current.reason ?: return
        if (current.result.loading || current.result.value != null || current.uncertain || visibleCharacterCount(current.details.trim()) !in 3..1000) return
        val epoch = runtime.identity.generation
        val id = state.value.route.id
        val key = runtime.newOperationKey()
        mutable.update { it.copy(spotContent = it.spotContent.copy(report = current.copy(result = ReadState(loading = true)))) }
        viewModelScope.launch {
            val result = try { withContext(worker) { runtime.moderation?.catalogReport(CatalogReportTarget.SPOT, id, reason, current.details, key) ?: CatalogResult.HttpError(503) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { CatalogResult.NetworkError }
            ensureActive()
            if (epoch != runtime.identity.generation) return@launch
            val updated = current.copy(result = ReadState<ReportReceipt>().result(result, now()),
                uncertain = result is CatalogResult.NetworkError || result is CatalogResult.InvalidResponse || result is CatalogResult.Maintenance || result is CatalogResult.HttpError && result.status >= 500)
            spotReports[epoch to id] = updated
            if (state.value.spotContent.id == id) mutable.update { it.copy(spotContent = it.spotContent.copy(report = updated)) }
        }
    }

    fun savedStack(): String = Json.encodeToString(state.value.stack)
    private suspend fun loadIntro() {
        mutable.update { it.copy(startupFailure = null) }
        try {
            var progress = withContext(worker) { runtime.startup.read() }
            if (!progress.completed && (state.value.identity.signedIn || state.value.intro?.completed == true)) {
                // An authenticated installation is already returning. A local write failure must
                // not turn logout into first-install onboarding during the same session.
                progress = TravelerIntroProgress(completed = true, step = 3)
                try { withContext(worker) { runtime.startup.complete() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutable.update { it.copy(startupFailure = ReadFailure(code = "STARTUP_STORAGE")) } }
            }
            mutable.update { it.copy(intro = progress) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(startupFailure = ReadFailure(code = "STARTUP_STORAGE")) } }
        refresh()
    }

    fun retryStartup() {
        if (startupJob?.isActive == true) return
        startupJob = viewModelScope.launch {
            mutable.update { it.copy(introSaving = true) }
            try { loadIntro() } finally { mutable.update { it.copy(introSaving = false) } }
        }
    }

    fun nextIntro() = moveIntro(1)
    fun previousIntro() = moveIntro(-1)
    private fun moveIntro(direction: Int) {
        val current = state.value
        val progress = current.intro ?: return
        if (!current.showIntro || current.introSaving || startupJob?.isActive == true || direction < 0 && progress.step == 0) return
        val next = if (direction > 0 && progress.step == 3) TravelerIntroProgress(completed = true, step = 3)
            else progress.copy(step = (progress.step + direction).coerceIn(0, 3))
        mutable.update { it.copy(introSaving = true, startupFailure = null) }
        startupJob = viewModelScope.launch {
            try {
                withContext(worker) { if (next.completed) runtime.startup.complete() else runtime.startup.saveStep(next.step) }
                mutable.update { it.copy(intro = next) }
                if (next.completed) { requireLogin(); refresh() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(startupFailure = ReadFailure(code = "STARTUP_STORAGE")) } }
            finally { mutable.update { it.copy(introSaving = false) } }
        }
    }

    private fun requireLogin() {
        if (!state.value.identity.signedIn && !state.value.route.destination.isAuthenticationScreen()) {
            reads.values.forEach(Job::cancel); reads.clear()
            mutable.update { TravelerState(stack = listOf(TravelerRoute(Destination.LOGIN)), identity = it.identity,
                identityRestored = it.identityRestored, intro = it.intro, introSaving = it.introSaving, startupFailure = it.startupFailure) }
        } else if (!state.value.identity.signedIn && state.value.identityRestored) {
            mutable.update { it.copy(tab = Destination.HOME, stack = authenticationStack(it.stack)) }
        }
    }
    fun openEntry(entry: CatalogEntry) = open(TravelerRoute(Destination.DETAIL, entry.id, entry.kind.name, slug = entry.slug))
    private fun creationRoute(route: TravelerRoute): TravelerRoute =
        if (route.destination == Destination.TRIP_FORM && route.operationKey.isBlank()) route.copy(operationKey = runtime.newOperationKey()) else route
    fun open(requested: TravelerRoute) {
        if (!state.value.identityRestored || state.value.intro == null || state.value.showIntro) return
        if (!state.value.identity.signedIn && !requested.destination.isAuthenticationScreen()) { requireLogin(); return }
        if (state.value.busy || state.value.route == requested ||
            requested.destination == Destination.TRIP_FORM && state.value.route.destination == Destination.TRIP_FORM && state.value.route.id == requested.id) return
        val route = creationRoute(if (requested.destination == Destination.CATALOG && requested.extra == CatalogKind.SPOT.name &&
            requested.id.isNotBlank() && requested.locationContextId.isBlank()) requested.copy(locationContextId = requested.id) else requested)
        if (route.destination in rootTabs && route.id.isEmpty() && route.extra.isEmpty()) { tab(route.destination); return }
        reads.values.forEach(Job::cancel); reads.clear()
        mutable.update { it.copy(stack = it.stack + route, writeFailure = null, saved = false,
            query = spotQueries[route].orEmpty(), feedback = null, detail = ReadState(), spotScopeLocation = ReadState(), quest = if (route.destination == Destination.QUEST) ReadState() else it.quest,
            questSpots = if (route.destination == Destination.QUEST) emptyMap() else it.questSpots, itinerary = ReadState(),
            distribution = ReadState(), shareLink = ReadState(), trip = ReadState(), plan = ReadState(),
            openTrip = ReadState(), openTripStops = ReadState(), application = ReadState(), notice = ReadState(), notification = ReadState(), ownerModeration = ReadState(), tripTimezone = null,
            tripRecovery = ReadState(), media = ReadState(), mediaPartial = false,
            itineraryDraft = ReadState(), pendingStop = ReadState(), applicationDraft = ReadState(),
            pendingPublication = ReadState(), isSpotSaved = ReadState(), publicPlan = ReadState(), publicProfile = ReadState(),
            publicProgress = ReadState(), publicPlans = if (route.destination == Destination.PUBLIC_PROFILE) ReadState() else it.publicPlans,
            publicBadges = ReadState(), publicPrivatePlans = ReadState(),
            group = ReadState(), groupMembers = ReadState(), groupInvitation = ReadState(),
            groupManagementDraft = if (route.isQuestGroupManagement && it.groupManagementDraft.groupId != route.id) QuestGroupManagementDraft(route.id) else it.groupManagementDraft,
            groupInvitations = if (route.destination in setOf(Destination.QUEST_GROUP_INVITATION, Destination.QUEST_GROUP_RECOVERY)) it.groupInvitations else ReadState(),
            groupInvitationsAppending = false, groupInvitationsAppendFailure = null,
            locationBrowser = ReadState(), locationContent = LocationContentState()) }
        refresh()
    }
    fun tab(destination: Destination) {
        if (!state.value.identityRestored || state.value.intro == null || state.value.showIntro || !state.value.identity.signedIn) { requireLogin(); return }
        if (state.value.busy) return
        reads.values.forEach(Job::cancel); reads.clear()
        mutable.update { it.copy(tab = destination, stack = listOf(TravelerRoute(destination)), query = spotQueries[TravelerRoute(destination)].orEmpty(),
            writeFailure = null, saved = false, feedback = null) }
        refresh()
    }
    fun back() {
        if (state.value.route.destination == Destination.PASSPORT &&
            (state.value.passportContent.expandedQr || state.value.passportContent.badgeId != null)) {
            closePassportDetail(); return
        }
        if (state.value.spotContent.report.open) { closeSpotReport(); return }
        if (state.value.questBrowse.pickerOpen) { closeQuestLocationPicker(); return }
        if (state.value.spotContent.comments.open && !state.value.busy) { closeSpotComments(); return }
        if (state.value.busy) return
        val current = state.value
        if (current.route.destination == Destination.REGISTER && current.registrationStep > 1) {
            mutable.update { it.copy(registrationStep = it.registrationStep - 1) }; return
        }
        val previous = current.stack.dropLast(1).lastOrNull()
        if (current.route.destination == Destination.ITINERARY && previous?.destination != Destination.OWNER_MODERATION &&
            !(previous?.destination == Destination.DETAIL && previous.extra == CatalogKind.SPOT.name)) {
            mutable.update { it.copy(stack = listOf(TravelerRoute(it.tab), TravelerRoute(Destination.ITINERARIES)),
                writeFailure = null, saved = false, feedback = null) }
        } else if (current.stack.size > 1) mutable.update { it.copy(stack = it.stack.dropLast(1), writeFailure = null, saved = false, feedback = null) }
        else if (current.tab != Destination.HOME) tab(Destination.HOME)
        if (state.value.route.retainsSpotQuery) mutable.update { it.copy(query = spotQueries[it.route].orEmpty()) }
        else if (state.value.route.destination == Destination.COUNTRIES ||
            state.value.route.destination == Destination.CATALOG && state.value.route.extra == CatalogKind.LOCATION.name)
            mutable.update { it.copy(query = locationQueries[it.route].orEmpty()) }
        val returningToInvitations = current.route.destination == Destination.QUEST_GROUP_INVITATION &&
            state.value.route.destination == Destination.QUEST_GROUP_INVITATIONS &&
            state.value.route == groupInvitationsRoute && state.value.groupInvitations.value != null
        if (!returningToInvitations) refresh()
    }
    fun background() { viewModelScope.launch(worker) { runtime.identity.background() } }
    fun clearFeedback() { mutable.update { it.copy(feedback = null, saved = false) } }

    fun passportTab(tab: PassportTab) { mutable.update { it.copy(passportContent = it.passportContent.copy(tab = tab, expandedQr = false, badgeId = null)) } }
    fun passportCompleted(value: Boolean) { mutable.update { it.copy(passportContent = it.passportContent.copy(completed = value)) } }
    fun expandPassportQr() {
        val current = state.value
        if (current.profile.failure == null && !current.profile.loading && !current.identity.busy &&
            current.profile.value?.username?.isNotBlank() == true && current.identity.passport != null)
            mutable.update { it.copy(passportContent = it.passportContent.copy(expandedQr = true, badgeId = null)) }
    }
    fun focusPassportBadge(id: String) {
        if (state.value.identity.passport?.badges?.any { it.id == id } == true)
            mutable.update { it.copy(passportContent = it.passportContent.copy(badgeId = id, expandedQr = false)) }
    }
    fun closePassportDetail() { mutable.update { it.copy(passportContent = it.passportContent.copy(expandedQr = false, badgeId = null)) } }
    fun refreshPassportIdentity() = fetch("passport-profile", { it.profile }, { s, r -> s.copy(profile = r) }) { runtime.account.profile() }

    fun refresh() {
        if (!state.value.identityRestored || state.value.intro == null || state.value.showIntro) return
        if (!state.value.identity.signedIn && !state.value.route.destination.isAuthenticationScreen()) { requireLogin(); return }
        val route = state.value.route
        when (route.destination) {
            Destination.HOME -> home()
            Destination.PROFILE -> fetch("profile", { it.profile }, { s, r -> s.copy(profile = r) }) { runtime.account.profile() }
            Destination.PROFILE_SETTINGS -> fetch("profile", { it.profile }, { s, r -> s.copy(profile = r) }) { runtime.account.profile() }
            Destination.PROFILE_EDIT -> {
                fetch("profile", { it.profile }, { s, r -> s.copy(profile = r) }) { runtime.account.profile() }
                fetch("nationalities", { it.nationalities }, { s, r -> s.copy(nationalities = r) }) { runtime.account.nationalities(language) }
            }
            Destination.PROFILE_INTERESTS -> fetch("interests", { it.interests }, { s, r -> s.copy(interests = r) }) { runtime.account.interests(language) }
            Destination.PROFILE_SECURITY -> fetch("security", { it.security }, { s, r -> s.copy(security = r) }) { runtime.account.security() }
            Destination.PROFILE_EMERGENCY -> {
                fetch("emergency", { it.emergency }, { s, r -> s.copy(emergency = r) }) { runtime.account.emergencyContact() }
                fetch("nationalities", { it.nationalities }, { s, r -> s.copy(nationalities = r) }) { runtime.account.nationalities(language) }
            }
            Destination.PROFILE_PREFERENCES -> fetch("preferences", { it.preferences }, { s, r -> s.copy(preferences = r) }) { runtime.settings?.load() ?: CatalogResult.HttpError(503) }
            Destination.COMMUNITY -> when (state.value.communityTab) { 0 -> publicPlans(); 1 -> loadOpenTrips(); else -> catalog() }
            Destination.EXPLORER, Destination.ADD_SPOT -> catalog()
            Destination.CATALOG -> if (route.extra == CatalogKind.LOCATION.name) loadLocationBrowser() else {
                catalog()
                if (route.extra == CatalogKind.SPOT.name && route.locationContextId.isNotBlank())
                    fetch("spot-scope", { it.spotScopeLocation }, { s, r -> s.copy(spotScopeLocation = r) }) {
                        runtime.catalog.detail(CatalogEntry(route.locationContextId, route.locationContextId, "", null, CatalogKind.LOCATION, null), language)
                    }
            }
            Destination.COUNTRIES -> fetch("locationCountries", { it.locationCountries }, { s, r -> s.copy(locationCountries = r) }) { locationCountries() }
            Destination.DETAIL -> {
                fetch("detail", { it.detail }, { s, r -> s.copy(detail = r) }, ready = { if (it.kind == CatalogKind.SPOT) loadSpotContent(it) }) {
                runtime.catalog.detail(CatalogEntry(route.id, route.slug.ifBlank { route.id }, "", null,
                    CatalogKind.entries.firstOrNull { it.name == route.extra } ?: CatalogKind.SPOT, null), language) }
                if (route.extra == CatalogKind.SPOT.name && state.value.identity.signedIn)
                    fetch("isSaved", { it.isSpotSaved }, { s, r -> s.copy(isSpotSaved = r) }) { runtime.savedSpots.isSaved(route.id) }
                if (route.extra == CatalogKind.LOCATION.name) loadLocationContent(route.id)
            }
            Destination.QUESTS -> { initializeQuestCatalog(); loadQuestCatalog(); loadQuestFilterLocation() }
            Destination.QUEST_FILTER -> loadQuestFilterLocation()
            Destination.QUEST -> fetch("quest", { it.quest }, { s, r -> s.copy(quest = r) }, ready = { quest ->
                quest.steps.mapNotNull { it.spotId }.distinct().forEach(::loadQuestSpot)
            }) { runtime.identity.quest(route.id) }
            Destination.PASSPORT -> {
                refreshPassportIdentity()
                viewModelScope.launch(worker) { runtime.identity.passport() }
            }
            Destination.QUEST_PROGRESS -> identityAction { runtime.identity.continueQuest(route.id) }
            Destination.QUEST_ACTIVITY -> if (route.extra.isNotBlank()) identityAction {
                if (state.value.identity.running?.id != route.extra) runtime.identity.continueQuest(route.extra)
                runtime.identity.activity(route.id)
            }
            Destination.ITINERARIES, Destination.PICK_PLAN -> {
                fetch("itineraries", { it.itineraries }, { s, r -> s.copy(itineraries = r) }) { runtime.itineraries.mine() }
                fetch("pendingStop", { it.pendingStop }, { s, r -> s.copy(pendingStop = r) }) { runtime.itineraries.pendingStop() }
            }
            Destination.ITINERARY, Destination.STOP_FORM -> loadItinerary(route.id)
            Destination.ITINERARY_FORM -> if (route.id.isNotBlank()) { loadItinerary(route.id); loadMedia(false) }
                else fetch("itineraryDraft", { it.itineraryDraft }, { s, r -> s.copy(itineraryDraft = r) }) { runtime.itineraries.pendingDraft() }
            Destination.SHARE, Destination.PUBLICATION -> {
                loadItinerary(route.id)
                if (route.destination == Destination.PUBLICATION) loadMedia(false)
                fetch("distribution", { it.distribution }, { s, r -> s.copy(distribution = r) }) { runtime.distribution.read(route.id) }
                fetch("pendingPublication", { it.pendingPublication }, { s, r -> s.copy(pendingPublication = r) }) { runtime.distribution.pendingPublication(route.id) }
            }
            Destination.SHARE_LINK -> fetch("link", { it.shareLink }, { s, r -> s.copy(shareLink = r) }) { runtime.distribution.shareLink(route.id) }
            Destination.TRIPS -> fetch("trips", { it.trips }, { s, r -> s.copy(trips = r) }) { runtime.trips.mine() }
            Destination.TRIP -> {
                fetch("trip", { it.trip }, { s, r -> s.copy(trip = r) }, ready = { trip ->
                    if (trip.role != TripCallerRole.INVITEE)
                        fetch("plan", { it.plan }, { s, r -> s.copy(plan = r) }) {
                            runtime.trips.workingPlan(route.id).also { if (it is CatalogResult.Ready) hydrate(it.value.stops) }
                        }
                    else mutable.update { it.copy(plan = ReadState()) }
                    if (trip.role == TripCallerRole.ORGANIZER) loadMedia(true)
                }) { runtime.trips.detail(route.id) }
            }
            Destination.TRIP_FORM -> {
                loadItinerary(route.id)
                fetch("tripRecovery", { it.tripRecovery }, { s, r -> s.copy(tripRecovery = r) }) {
                    val draft = runtime.trips.pendingDraft(route.id, true)
                    if (draft !is CatalogResult.Ready) draft.failure()
                    else when (val trip = createdTrip(route)) {
                        is CatalogResult.Ready -> CatalogResult.Ready(TripCreationRecovery(draft.value.takeIf { trip.value == null }, trip.value))
                        else -> trip.failure()
                    }
                }
                viewModelScope.launch(worker) {
                    val result = runtime.itineraries.detail(route.id)
                    val spot = (result as? CatalogResult.Ready)?.value?.stops?.firstOrNull()
                    val detail = spot?.let { runtime.catalog.detail(CatalogEntry(it.spotId, it.spotId, it.label,
                        null, CatalogKind.SPOT, null), language) }
                    val timezone = (detail as? CatalogResult.Ready)?.value?.timezone
                    if (state.value.route == route) mutable.update { it.copy(tripTimezone = timezone) }
                }
            }
            Destination.MEMBERS -> {
                fetch("members", { it.members }, { s, r -> s.copy(members = r) }) { runtime.trips.members(route.id) }
                fetch("trip", { it.trip }, { s, r -> s.copy(trip = r) }) { runtime.trips.detail(route.id) }
            }
            Destination.INVITATIONS -> {
                fetch("invitations", { it.invitations }, { s, r -> s.copy(invitations = r) }) {
                    if (route.id.isBlank()) runtime.trips.myInvitations() else runtime.trips.invitations(route.id) }
                if (route.id.isNotBlank()) fetch("trip", { it.trip }, { s, r -> s.copy(trip = r) }) { runtime.trips.detail(route.id) }
            }
            Destination.OPEN_TRIPS -> loadOpenTrips()
            Destination.APPLY -> if (route.extra == "form" && state.value.identity.signedIn)
                fetch("applicationDraft", { it.applicationDraft }, { s, r -> s.copy(applicationDraft = r) }) { runtime.trips.pendingOpenTripApplication(route.id) }
            Destination.APPLICATIONS, Destination.TRIP_APPLICATIONS -> fetch("applications", { it.applications }, { s, r -> s.copy(applications = r) }) {
                if (route.destination == Destination.APPLICATIONS) runtime.trips.myTripApplications() else runtime.trips.tripApplications(route.id) }
            Destination.APPLICATION -> fetch("application", { it.application }, { s, r -> s.copy(application = r) }) { runtime.trips.tripApplication(route.id) }
            Destination.NOTICE -> fetch("notice", { it.notice }, { s, r -> s.copy(notice = r) }) { runtime.trips.ownedTripNotice(route.id) }
            Destination.NOTIFICATIONS -> {
                loadNotifications()
                fetch("unread", { it.unread }, { s, r -> s.copy(unread = r) }) { runtime.notifications.unreadNotifications() }
            }
            Destination.NOTIFICATION_OPEN -> fetch("notification-open", { it.notification }, { s, r -> s.copy(notification = r) }, ready = ready@ { notice ->
                if (notice.id != route.id) {
                    mutable.update { it.copy(notification = ReadState(failure = ReadFailure(502))) }
                    return@ready
                }
                val target = notificationRoute(notice.targetUrl)
                if (target != null) replace(target.copy(inboxId = route.id))
                // A generic inbox preview is not proof that an authorized destination rendered.
                else if (notice.targetUrl == null) replace(TravelerRoute(Destination.NOTIFICATION_DETAIL, route.id))
                else mutable.update { it.copy(notification = ReadState(failure = ReadFailure(404, "TARGET_UNAVAILABLE"))) }
            }) { runtime.notifications.notification(route.id, language) }
            Destination.NOTIFICATION_DETAIL -> fetch("notification-detail", { it.notification }, { s, r -> s.copy(notification = r) }) {
                runtime.notifications.notification(route.id, language)
            }
            Destination.OWNER_MODERATION -> loadOwnerModeration()
            Destination.PUBLIC_PLAN -> fetch("publicPlan", { it.publicPlan }, { s, r -> s.copy(publicPlan = r) }) { runtime.publicContent.itinerary(route.id) }
            Destination.PUBLIC_PROFILE -> {
                fetch("publicProfile", { it.publicProfile }, { s, r -> s.copy(publicProfile = r) }, settled = { loadPublicProfileContent() }, routeKey = { it.copy(extra = "") }) { runtime.publicContent.profile(route.id) }
                fetch("profile", { it.profile }, { s, r -> s.copy(profile = r) }, settled = { loadPublicProfileContent() }, routeKey = { it.copy(extra = "") }) { runtime.account.profile() }
                fetch("publicProgress", { it.publicProgress }, { s, r -> s.copy(publicProgress = r) }, routeKey = { it.copy(extra = "") }) { runtime.publicContent.progress(route.id) }
            }
            Destination.SAVED_SPOTS -> loadSaved()
            Destination.QUEST_GROUPS -> loadGroups()
            Destination.QUEST_GROUP -> {
                fetch("group", { it.group }, { s, r -> s.copy(group = r) }) { runtime.questGroups.group(route.id, language) }
                fetch("groupMembers", { it.groupMembers }, { s, r -> s.copy(groupMembers = r) }) { runtime.questGroups.members(route.id, 1) }
                fetch("profile", { it.profile }, { s, r -> s.copy(profile = r) }) { runtime.account.profile() }
            }
            Destination.QUEST_GROUP_INVITATIONS -> loadGroupInvitations()
            Destination.QUEST_GROUP_RECOVERY -> Unit // Review is explicit; entering/refreshing never releases or replays.
            Destination.QUEST_GROUP_INVITATION -> {
                fetch("groupInvitation", { it.groupInvitation }, { s, r -> s.copy(groupInvitation = r) }, ready = ::reconcileGroupInvitation) { runtime.questGroups.invitation(route.id, language) }
                fetch("profile", { it.profile }, { s, r -> s.copy(profile = r) }) { runtime.account.profile() }
            }
            Destination.REGISTER -> fetch("references", { it.references }, { s, r -> s.copy(references = r) }) {
                when (val r = runtime.registration.references(language)) {
                    is RegistrationResult.Ready -> CatalogResult.Ready(r.value)
                    is RegistrationResult.Failed -> CatalogResult.HttpError(r.status, r.retryAfterSeconds, r.code)
                }
            }
            else -> Unit
        }
        if (route.destination == Destination.APPLY) loadOpenTrip(route.id)
    }

    fun openTrip(id: String) {
        open(TravelerRoute(Destination.APPLY, id, "reader"))
    }
    private fun loadOpenTrip(id: String) {
        fetch("openTrip", { it.openTrip }, { s, r -> s.copy(openTrip = r) }) { runtime.trips.openTrip(id) }
        fetch("openTripStops", { it.openTripStops }, { s, r -> s.copy(openTripStops = r) }) { runtime.trips.openTripStops(id) }
    }
    private fun loadItinerary(id: String) = fetch("itinerary", { it.itinerary }, { s, r -> s.copy(itinerary = r) }) {
        runtime.itineraries.detail(id).also { if (it is CatalogResult.Ready) hydrate(it.value.stops) }
    }
    private fun hydrate(stops: List<OwnedStop>) {
        val route = state.value.route; val epoch = runtime.identity.generation
        viewModelScope.launch(worker) {
            stops.distinctBy { it.spotId }.chunked(4).forEach { group ->
                coroutineScope { group.map { stop -> async {
                    val result = runtime.catalog.detail(CatalogEntry(stop.spotId, stop.spotId, stop.label,
                        null, CatalogKind.SPOT, null), language)
                    if (result is CatalogResult.Ready && state.value.route == route && runtime.identity.generation == epoch)
                        mutable.update { it.copy(stopCards = it.stopCards + (stop.spotId to result.value)) }
                } }.awaitAll() }
            }
        }
    }
    private fun home() {
        fetch("homeSpots", { it.home.spots }, { s, r -> s.copy(home = s.home.copy(spots = r)) }) { runtime.catalog.discoverySpots(language) }
        fetch("homeLocations", { it.home.locations }, { s, r -> s.copy(home = s.home.copy(locations = r)) }) { runtime.catalog.topLocations(language) }
        fetch("homeCollections", { it.home.collections }, { s, r -> s.copy(home = s.home.copy(collections = r)) }) { runtime.catalog.collections(language, 8) }
        fetch("homeStories", { it.home.stories }, { s, r -> s.copy(home = s.home.copy(stories = r)) }) { runtime.catalog.stories(language, perPage = com.floopel.feature.traveler.presentation.home.HOME_ARTICLE_LIMIT) }
        fetch("homeQuests", { it.home.quests }, { s, r -> s.copy(home = s.home.copy(quests = r)) }) { runtime.catalog.activeQuests(language, 1) }
        if (state.value.identity.signedIn) {
            fetch("profile", { it.profile }, { s, r -> s.copy(profile = r) }) { runtime.account.profile() }
            fetch("trips", { it.trips }, { s, r -> s.copy(trips = r) }) { runtime.trips.mine() }
            fetch("unread", { it.unread }, { s, r -> s.copy(unread = r) }) { runtime.notifications.unreadNotifications() }
        }
    }
    fun query(value: String) {
        mutable.update { it.copy(query = value) }
        if (state.value.route.retainsSpotQuery) spotQueries[state.value.route] = value
        if (state.value.route.destination == Destination.COUNTRIES ||
            state.value.route.destination == Destination.CATALOG && state.value.route.extra == CatalogKind.LOCATION.name)
            locationQueries[state.value.route] = value
        if (state.value.route.destination == Destination.CATALOG && state.value.route.extra == CatalogKind.LOCATION.name)
            reads.remove("locationBrowser")?.cancel()
        reads.remove("searchDelay")?.cancel()
        if (state.value.route.destination == Destination.COUNTRIES) return
        reads["searchDelay"] = viewModelScope.launch { delay(350)
            when {
                state.value.route.destination == Destination.OPEN_TRIPS || state.value.route.destination == Destination.COMMUNITY && state.value.communityTab == 1 -> loadOpenTrips()
                state.value.route.destination == Destination.COMMUNITY && state.value.communityTab == 0 -> publicPlans()
                state.value.route.destination == Destination.CATALOG && state.value.route.extra == CatalogKind.LOCATION.name -> loadLocationBrowser()
                else -> catalog()
            }
        }
    }
    private fun catalog(page: Int = 1) {
        val route = state.value.route; val query = state.value.query
        val locationId = route.id.takeIf { route.destination == Destination.CATALOG && it.isNotBlank() }
        val kind = if (route.destination == Destination.COMMUNITY) CatalogKind.ITINERARY
            else CatalogKind.entries.firstOrNull { it.name == route.extra } ?: CatalogKind.SPOT
        fetch("catalog", { if (page == 1) ReadState() else it.catalog }, { s, r ->
            s.copy(catalog = if (page > 1 && r.value != null && s.catalog.value != null)
                r.copy(value = r.value.copy(items = (s.catalog.value.items + r.value.items).distinctBy { it.id })) else r) }) {
            if (kind == CatalogKind.ITINERARY) runtime.catalog.curatedItineraries(query, null, language, page)
            else if (query.isNotBlank() && kind == CatalogKind.SPOT && route.destination != Destination.EXPLORER)
                runtime.catalog.searchSpotPage(query, locationId, language, page)
            else if (query.isNotBlank()) when (val r = runtime.catalog.search(query, language)) {
                is CatalogResult.Ready -> CatalogResult.Ready(CatalogPage(r.value, 1, 1))
                else -> r.failure()
            } else runtime.catalog.browse(kind, language, page, storyType = if (kind == CatalogKind.STORY && locationId != null) StoryType.ARTICLE else null, locationId = locationId)
        }
    }

    fun spotScope(scoped: Boolean) {
        val current = state.value
        if (current.busy || current.route.destination != Destination.CATALOG || current.route.extra != CatalogKind.SPOT.name ||
            scoped && current.route.locationContextId.isBlank()) return
        val id = if (scoped) current.route.locationContextId else ""
        if (current.route.id == id) return
        reads.values.forEach(Job::cancel); reads.clear()
        val route = current.route.copy(id = id)
        spotQueries[route] = current.query
        mutable.update { it.copy(stack = it.stack.dropLast(1) + route, catalog = ReadState()) }
        refresh()
    }

    fun publicProfileTab(selection: String) {
        val s = state.value
        if (s.busy || s.route.destination != Destination.PUBLIC_PROFILE || selection !in setOf("", "private", "saved", "badges") ||
            selection in setOf("private", "saved") && !s.ownsPublicProfile || s.route.extra == selection) return
        reads.remove("public-profile-content")?.cancel()
        mutable.update { it.copy(stack = it.stack.dropLast(1) + it.route.copy(extra = selection)) }
        loadPublicProfileContent()
    }

    fun refreshPublicProfileTab() = loadPublicProfileContent()

    fun removeProfileSavedSpot(id: String) {
        if (!state.value.ownsPublicProfile || state.value.route.extra != "saved") return
        write({ runtime.savedSpots.setSaved(id, false) }) { removed ->
            if (!removed) mutable.update { s -> s.copy(savedSpots = s.savedSpots.value?.let { page ->
                ReadState(page.copy(spots = page.spots.filterNot { it.id == id })) } ?: ReadState()) }
        }
    }

    fun saveProfile(changed: PersonalProfile) {
        val previous = state.value.profile.value ?: return
        if (state.value.route.destination != Destination.PROFILE_EDIT) return
        write({ runtime.account.updateProfile(previous, changed) }) { confirmed ->
            mutable.update { s -> s.copy(profile = ReadState(confirmed), stack = s.stack.map { route ->
                if (route.destination == Destination.PUBLIC_PROFILE && route.id == previous.username) route.copy(id = confirmed.username) else route
            }) }
            back()
        }
    }
    fun saveProfileInterests(selected: Set<String>) {
        val known = state.value.interests.value ?: return
        if (state.value.route.destination != Destination.PROFILE_INTERESTS || selected.size < 3 || selected.any { id -> known.options.none { it.id == id } }) return
        write({ runtime.account.saveInterests(selected) }) { confirmed -> mutable.update { it.copy(interests = ReadState(known.copy(selected = confirmed)), saved = true) } }
    }
    fun saveEmergency(contact: EmergencyContact) {
        if (state.value.route.destination != Destination.PROFILE_EMERGENCY) return
        write({ runtime.account.saveEmergencyContact(contact) }) { confirmed -> mutable.update { it.copy(emergency = ReadState(confirmed), saved = true) } }
    }
    fun saveProfilePreferences(changed: TravelerSettings) {
        val repository = runtime.settings ?: return
        if (state.value.route.destination != Destination.PROFILE_PREFERENCES) return
        val previous = state.value.preferences.value ?: return
        val known = changed.copy(tripUpdates = changed.tripUpdates.takeIf { previous.tripUpdates != null }, marketing = changed.marketing.takeIf { previous.marketing != null },
            product = changed.product.takeIf { previous.product != null }, profileDiscoverable = changed.profileDiscoverable.takeIf { previous.profileDiscoverable != null })
        write({ repository.save(known) }) { confirmed -> mutable.update { it.copy(preferences = ReadState(confirmed), saved = true) } }
    }
    fun changeProfilePassword(current: String, replacement: String) {
        if (state.value.route.destination != Destination.PROFILE_SECURITY || state.value.security.value?.hasPassword != true || !validTravelerPassword(replacement)) return
        write({ runtime.account.changePassword(current, replacement) }) { }
    }
    fun setupProfilePassword() {
        if (state.value.route.destination != Destination.PROFILE_SECURITY || state.value.security.value?.hasPassword != false || state.value.passwordSetupRetryAt > now()) return
        write({ runtime.account.requestPasswordSetup() }) { seconds -> mutable.update { it.copy(saved = true, passwordSetupRetryAt = now() + seconds * 1000L) } }
    }

    private fun loadPublicProfileContent(page: Int = 1) {
        val s = state.value
        if (s.route.destination != Destination.PUBLIC_PROFILE || s.publicProfile.value?.username != s.route.id ||
            s.publicProfile.loading || s.profile.loading) return
        val username = s.route.id
        when {
            s.route.extra == "badges" -> fetch("public-profile-content", { it.publicBadges }, { old, r ->
                old.copy(publicBadges = if (page > 1 && r.value != null) r.copy(value = r.value.copy(
                    items = (old.publicBadges.value?.items.orEmpty() + r.value.items).distinctBy { it.id })) else r)
            }) { runtime.publicContent.badges(username, language, page) }
            s.route.extra == "saved" && s.ownsPublicProfile -> fetch("public-profile-content", { it.savedSpots }, { old, r ->
                old.copy(savedSpots = if (page > 1 && r.value != null) r.copy(value = r.value.copy(
                    spots = (old.savedSpots.value?.spots.orEmpty() + r.value.spots).distinctBy { it.id })) else r)
            }) { runtime.savedSpots.savedSpots(page) }
            s.route.extra == "private" && s.ownsPublicProfile -> fetch("public-profile-content", { it.publicPrivatePlans }, { old, r -> old.copy(publicPrivatePlans = r) }) {
                when (val result = runtime.itineraries.mine()) {
                    is CatalogResult.Ready -> CatalogResult.Ready(result.value.filter { it.isOwner && it.visibility in setOf("private", "unlisted") })
                    else -> result.failure()
                }
            }
            else -> fetch("public-profile-content", { it.publicPlans }, { old, r -> old.copy(publicPlans = r) }) { runtime.publicContent.profileItineraries(username) }
        }
    }

    private suspend fun locationCountries(): CatalogResult<List<CatalogEntry>> {
        val countries = mutableListOf<CatalogEntry>()
        var page = 1
        while (true) {
            val result = runtime.catalog.countryPage(language, page)
            if (result !is CatalogResult.Ready) return result.failure()
            if (result.value.items.any { it.kind != CatalogKind.LOCATION || it.locationLevel != "country" }) return CatalogResult.InvalidResponse
            countries += result.value.items
            if (page >= result.value.totalPages) return CatalogResult.Ready(countries.distinctBy { it.id })
            // Defensive bound on a malformed server page sequence; never manufacture countries.
            if (page >= 128 || result.value.items.isEmpty()) return CatalogResult.InvalidResponse
            page++
        }
    }

    private fun loadLocationBrowser(page: Int = 1) {
        val route = state.value.route; val query = state.value.query.trim()
        fetch("locationBrowser", { it.locationBrowser }, { s, r ->
            s.copy(locationBrowser = if (page > 1 && r.value != null && s.locationBrowser.value?.query == query)
                r.copy(value = r.value.copy(entries = (s.locationBrowser.value.entries + r.value.entries).distinctBy { it.id })) else r)
        }) {
            val choices = locationCountries()
            if (choices !is CatalogResult.Ready) return@fetch choices.failure()
            val region = if (route.id.isNotBlank()) {
                val result = runtime.catalog.detail(CatalogEntry(route.id, route.id, "", null, CatalogKind.LOCATION, null), language)
                if (result !is CatalogResult.Ready) return@fetch result.failure()
                if (result.value.locationLevel != "region") return@fetch CatalogResult.InvalidResponse
                result.value
            } else null
            val selectedId = route.countryId.takeIf(String::isNotBlank) ?: region?.ancestors?.lastOrNull { it.level == "country" }?.id
            val country = if (selectedId != null) choices.value.firstOrNull { it.id == selectedId }
                ?: return@fetch CatalogResult.HttpError(404)
            else choices.value.firstOrNull { it.slug == "indonesia" } ?: choices.value.firstOrNull()
            if (region != null && region.parentLocationId != country?.id) return@fetch CatalogResult.InvalidResponse
            val result = when {
                query.isNotBlank() -> when (val search = runtime.catalog.searchLocations(query, language)) {
                    is CatalogResult.Ready -> CatalogResult.Ready(CatalogPage(search.value, 1, 1))
                    else -> search.failure()
                }
                country == null -> CatalogResult.Ready(CatalogPage(emptyList(), 1, 0))
                else -> runtime.catalog.browse(CatalogKind.LOCATION, language, page, parentId = region?.id ?: country.id)
            }
            if (result !is CatalogResult.Ready) result.failure()
            else if (result.value.items.any { it.kind != CatalogKind.LOCATION }) CatalogResult.InvalidResponse
            else CatalogResult.Ready(LocationBrowserPage(choices.value, country, region, result.value.items, query,
                result.value.page, result.value.totalPages))
        }
    }

    fun moreLocations() { state.value.locationBrowser.value?.takeIf { it.page < it.totalPages && !state.value.locationBrowser.loading }
        ?.let { loadLocationBrowser(it.page + 1) } }
    fun chooseLocationCountry() = open(TravelerRoute(Destination.COUNTRIES,
        countryId = state.value.locationBrowser.value?.country?.id.orEmpty()))
    fun selectLocationCountry(country: CatalogEntry) {
        if (country.kind != CatalogKind.LOCATION || country.locationLevel != "country") return
        if (state.value.route.destination != Destination.COUNTRIES) {
            open(TravelerRoute(Destination.CATALOG, extra = CatalogKind.LOCATION.name, countryId = country.id)); return
        }
        reads.values.forEach(Job::cancel); reads.clear()
        val parent = state.value.stack.dropLast(1)
        val index = parent.indexOfLast { it.destination == Destination.CATALOG && it.extra == CatalogKind.LOCATION.name }
        val route = TravelerRoute(Destination.CATALOG, extra = CatalogKind.LOCATION.name, countryId = country.id)
        locationQueries[route] = ""
        mutable.update { it.copy(stack = if (index >= 0) parent.take(index) + route else listOf(TravelerRoute(it.tab), route),
            query = "", locationBrowser = ReadState()) }
        refresh()
    }
    fun browseLocation(entry: CatalogEntry) {
        when (entry.locationLevel) {
            "country" -> selectLocationCountry(entry)
            "region" -> open(TravelerRoute(Destination.CATALOG, entry.id, CatalogKind.LOCATION.name,
                countryId = if (state.value.query.isBlank()) state.value.locationBrowser.value?.country?.id.orEmpty()
                    else entry.ancestors.lastOrNull { it.level == "country" }?.id.orEmpty()))
            else -> openEntry(entry)
        }
    }
    fun locationCountriesRoot() {
        val country = state.value.locationBrowser.value?.country ?: return
        val index = state.value.stack.indexOfLast { it.destination == Destination.CATALOG && it.extra == CatalogKind.LOCATION.name &&
            it.id.isBlank() }
        if (index < 0) { open(TravelerRoute(Destination.CATALOG, extra = CatalogKind.LOCATION.name, countryId = country.id)); return }
        reads.values.forEach(Job::cancel); reads.clear()
        // A blank root may have resolved a different default country before a global search.
        // Bind the breadcrumb target explicitly instead of resolving that default again.
        val root = state.value.stack[index].copy(countryId = country.id)
        val stack = state.value.stack.take(index) + root
        locationQueries[stack.last()] = ""
        mutable.update { it.copy(stack = stack, query = "", locationBrowser = ReadState()) }
        refresh()
    }
    private fun loadLocationContent(id: String) {
        if (state.value.locationContent.id != id) mutable.update { it.copy(locationContent = LocationContentState(id)) }
        fetch("locationSpots", { it.locationContent.spots }, { s, r -> s.copy(locationContent = s.locationContent.copy(spots = r)) }) {
            runtime.catalog.browse(CatalogKind.SPOT, language, 1, locationId = id)
        }
        fetch("locationQuests", { it.locationContent.quests }, { s, r -> s.copy(locationContent = s.locationContent.copy(quests = r)) }) {
            runtime.catalog.untakenLocationQuests(id, language, 1)
        }
        fetch("locationTrips", { it.locationContent.trips }, { s, r -> s.copy(locationContent = s.locationContent.copy(trips = r)) }) {
            runtime.trips.openTrips(OpenTripQuery(locationId = id, limit = 8))
        }
        fetch("locationArticles", { it.locationContent.articles }, { s, r -> s.copy(locationContent = s.locationContent.copy(articles = r)) }) {
            runtime.catalog.browse(CatalogKind.STORY, language, 1, storyType = StoryType.ARTICLE, locationId = id)
        }
    }

    private fun loadSpotContent(entry: CatalogEntry) {
        if (state.value.spotContent.id != entry.id) mutable.update { it.copy(spotContent = SpotContentState(entry.id)) }
        entry.locationId?.let { id -> fetch("spot-location", { it.spotContent.location }, { s, r -> s.copy(spotContent = s.spotContent.copy(location = r)) }) {
            runtime.catalog.detail(CatalogEntry(id, id, "", null, CatalogKind.LOCATION, null), language)
        } }
        fetch("spot-quests", { it.spotContent.quests }, { s, r -> s.copy(spotContent = s.spotContent.copy(quests = r)) }) { runtime.catalog.spotQuests(entry.id, language, 1) }
        fetch("spot-trips", { it.spotContent.trips }, { s, r -> s.copy(spotContent = s.spotContent.copy(trips = r)) }) { runtime.trips.openTrips(OpenTripQuery(spotId = entry.id)) }
        fetch("spot-articles", { it.spotContent.articles }, { s, r -> s.copy(spotContent = s.spotContent.copy(articles = r)) }) { runtime.catalog.spotArticles(entry.id, language, 1) }
    }

    fun moreSpotTrips() {
        val s = state.value; val cursor = s.spotContent.trips.value?.nextCursor ?: return
        if (s.route.destination != Destination.DETAIL || s.route.id != s.spotContent.id || s.spotContent.trips.loading) return
        fetch("spot-trips", { it.spotContent.trips }, { old, r -> old.copy(spotContent = old.spotContent.copy(trips = if (r.value != null)
            r.copy(value = r.value.copy(items = (old.spotContent.trips.value?.items.orEmpty() + r.value.items).distinctBy { it.id })) else r)) }) {
            runtime.trips.openTrips(OpenTripQuery(spotId = s.route.id, cursor = cursor))
        }
    }

    fun openSpotComments() {
        if (runtime.comments == null) return
        val s = state.value
        if (s.route.destination != Destination.DETAIL || s.route.extra != CatalogKind.SPOT.name || s.spotContent.id != s.route.id) return
        mutable.update { it.copy(spotContent = it.spotContent.copy(comments = it.spotContent.comments.copy(open = true))) }
        loadSpotComments()
    }
    fun closeSpotComments() {
        if (!state.value.busy) mutable.update { it.copy(spotContent = it.spotContent.copy(comments = it.spotContent.comments.copy(open = false))) }
    }
    fun spotCommentDraft(value: String) { mutable.update { it.copy(spotContent = it.spotContent.copy(comments = it.spotContent.comments.copy(draft = value))) } }
    fun replyToSpotComment(comment: TravelerComment?) { mutable.update { it.copy(spotContent = it.spotContent.copy(comments = it.spotContent.comments.copy(replyTo = comment))) } }
    fun loadSpotComments(more: Boolean = false) {
        val repository = runtime.comments ?: return
        val s = state.value
        if (s.route.destination != Destination.DETAIL || s.route.id != s.spotContent.id || !s.spotContent.comments.open) return
        val cursor = if (more) s.spotContent.comments.page.value?.nextCursor ?: return else null
        if (more && s.spotContent.comments.page.loading) return
        fetch("spot-comments", { it.spotContent.comments.page }, { old, r -> old.copy(spotContent = old.spotContent.copy(comments = old.spotContent.comments.copy(
            page = if (cursor != null && r.value != null) r.copy(value = r.value.copy(items = (old.spotContent.comments.page.value?.items.orEmpty() + r.value.items).distinctBy { it.id })) else r))) }) {
            repository.page(CommentTarget.SPOT, s.route.id, cursor)
        }
    }
    fun sendSpotComment() {
        val repository = runtime.comments ?: return
        val s = state.value; val comments = s.spotContent.comments
        val parent = comments.replyTo?.let { it.parentId ?: it.id }; val body = comments.draft.trim()
        if (s.route.destination != Destination.DETAIL || s.route.id != s.spotContent.id || !comments.open ||
            visibleCharacterCount(body) !in 1..100 || comments.uncertainDraft == (parent to body)) return
        write({ (try { repository.create(CommentTarget.SPOT, s.route.id, body, parent) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { CatalogResult.NetworkError }).also { result ->
            if (result !is CatalogResult.Ready) mutable.update { current ->
                if (current.spotContent.id == s.route.id && runtime.identity.generation == s.identity.generation)
                    current.copy(spotContent = current.spotContent.copy(comments = current.spotContent.comments.copy(writeFailure = ReadState<Unit>().result(result.failure(), now()).failure))) else current
            }
            if (result == CatalogResult.NetworkError || result == CatalogResult.InvalidResponse || result is CatalogResult.Maintenance ||
                result is CatalogResult.HttpError && result.status >= 500) mutable.update { current ->
                if (current.spotContent.id == s.route.id && runtime.identity.generation == s.identity.generation)
                    current.copy(spotContent = current.spotContent.copy(comments = current.spotContent.comments.copy(uncertainDraft = parent to body))) else current
            }
        } }) {
            mutable.update { it.copy(spotContent = it.spotContent.copy(comments = it.spotContent.comments.copy(draft = "", replyTo = null, writeFailure = null))) }
            loadSpotComments()
            fetch("spot-comment-total", { it.detail }, { old, r -> old.copy(detail = r) }) {
                when (val total = repository.total(CommentTarget.SPOT, s.route.id)) {
                    is CatalogResult.Ready -> CatalogResult.Ready((state.value.detail.value ?: return@fetch CatalogResult.InvalidResponse).copy(commentCount = total.value))
                    else -> total.failure()
                }
            }
        }
    }
    fun likeSpotComment(comment: TravelerComment) {
        val repository = runtime.comments ?: return
        if (!state.value.spotContent.comments.open) return
        val key = runtime.newOperationKey()
        write({ repository.like(comment.id, !comment.liked, key) }) { confirmed ->
            if (confirmed.commentId != comment.id) { mutable.update { it.copy(writeFailure = ReadFailure(502)) }; return@write }
            fun TravelerComment.updated(): TravelerComment = copy(liked = if (id == confirmed.commentId) confirmed.liked else liked,
                likeCount = if (id == confirmed.commentId) confirmed.count else likeCount,
                replies = replies.map { if (it.id == confirmed.commentId) it.copy(liked = confirmed.liked, likeCount = confirmed.count) else it })
            fun ReadState<CommentPage>.updated(): ReadState<CommentPage> = copy(value = value?.let { it.copy(items = it.items.map { row -> row.updated() }) })
            mutable.update { s -> s.copy(spotContent = s.spotContent.copy(comments = s.spotContent.comments.copy(
                page = s.spotContent.comments.page.updated(), replyPages = s.spotContent.comments.replyPages.mapValues { it.value.updated() }))) }
        }
    }

    fun spotCommentReplies(parentId: String, more: Boolean = false) {
        val repository = runtime.comments ?: return
        val s = state.value
        if (!s.spotContent.comments.open || s.route.id != s.spotContent.id) return
        val previous = s.spotContent.comments.replyPages[parentId]
        if (previous?.loading == true) return
        val cursor = if (more) previous?.value?.nextCursor ?: return else null
        fetch("spot-replies-$parentId", { it.spotContent.comments.replyPages[parentId] ?: ReadState() }, { old, r ->
            val combined = if (cursor != null && r.value != null) r.copy(value = r.value.copy(items = (previous?.value?.items.orEmpty() + r.value.items).distinctBy { it.id })) else r
            old.copy(spotContent = old.spotContent.copy(comments = old.spotContent.comments.copy(replyPages = old.spotContent.comments.replyPages + (parentId to combined))))
        }) { repository.page(CommentTarget.SPOT, s.route.id, cursor, parentId) }
    }
    fun communityTab(index: Int) {
        mutable.update { it.copy(communityTab = index.coerceIn(0, 2), query = "", publicPlans = ReadState(), publicPage = 1, publicCanRequestNext = true) }
        refresh()
    }
    private fun publicPlans(page: Int = 1) {
        val query = state.value.query
        fetch("publicPlans", { if (page == 1) ReadState() else it.publicPlans }, { s, r ->
            val values = r.value
            val combined = if (page > 1 && values != null) (s.publicPlans.value.orEmpty() + values).distinctBy { it.id } else values
            s.copy(publicPlans = r.copy(value = combined), publicPage = if (values != null) page else s.publicPage,
                publicCanRequestNext = values != null && values.isNotEmpty() && (page == 1 || combined?.size != s.publicPlans.value?.size))
        }) { runtime.publicContent.searchItineraries(query, null, page) }
    }
    private fun loadSaved(page: Int = 1) = fetch("savedSpots", { it.savedSpots }, { s, r ->
        s.copy(savedSpots = if (page > 1 && r.value != null) r.copy(value = r.value.copy(spots = (s.savedSpots.value?.spots.orEmpty() + r.value.spots).distinctBy { it.id })) else r)
    }) { runtime.savedSpots.savedSpots(page) }
    fun setSaved() {
        val saved = state.value.isSpotSaved.value ?: return
        write({ runtime.savedSpots.setSaved(state.value.route.id, !saved) }) { value -> mutable.update { it.copy(isSpotSaved = ReadState(value)) } }
    }
    fun clonePlan() {
        val plan = state.value.publicPlan.value ?: return
        copyPublishedPlan(plan.id, plan.allowClone)
    }
    fun clonePublicPlan(plan: PublicItinerarySummary) = copyPublishedPlan(plan.id, plan.allowClone == true)
    fun cloneCuratedPlan(plan: CatalogEntry) {
        if (plan.kind == CatalogKind.ITINERARY) copyPublishedPlan(plan.id, plan.allowClone == true)
    }
    private fun copyPublishedPlan(id: String, allowed: Boolean) {
        if (!allowed) return
        if (!state.value.identity.signedIn) { open(TravelerRoute(Destination.LOGIN)); return }
        itineraryWrite({ runtime.itineraries.clone(id).catalog() }) { replace(TravelerRoute(Destination.ITINERARY, it.itinerary.id)) }
    }
    private fun loadGroups(page: Int = 1) = fetch("groups", { it.groups }, { s, r -> s.copy(groups =
        if (page > 1 && r.value != null) r.copy(value = r.value.copy(items = (s.groups.value?.items.orEmpty() + r.value.items).distinctBy { it.id })) else r) }) {
        runtime.questGroups.groups(page, language, state.value.route.id.takeIf(String::isNotBlank)) }
    fun createGroup() {
        val questId = state.value.route.id
        if (questId.isBlank()) return
        groupWrite({ runtime.questGroups.createGroup(questId, language, runtime.newOperationKey()) }) { replace(TravelerRoute(Destination.QUEST_GROUP, it.id)) }
    }
    fun inviteToGroup(username: String) = groupWrite({ runtime.questGroups.invite(state.value.route.id, username, language, runtime.newOperationKey()) }) {
        mutable.update { it.copy(saved = true, groupManagementDraft = it.groupManagementDraft.copy(username = "")) }
    }
    fun manageQuestGroup() {
        val s = state.value
        if (s.managesCurrentQuestGroup && s.identity.generation == runtime.identity.generation)
            open(s.route.copy(extra = QuestGroupManagementMode, inboxId = null))
    }
    fun groupInviteUsername(value: String) {
        if (state.value.route.isQuestGroupManagement && !state.value.busy)
            mutable.update { it.copy(groupManagementDraft = QuestGroupManagementDraft(it.route.id, value)) }
    }
    fun decideGroupInvitation(accept: Boolean) = groupWrite({ runtime.questGroups.decideInvitation(state.value.route.id, accept, language, runtime.newOperationKey()) }) {
        reconcileGroupInvitation(it)
        if (accept) replace(TravelerRoute(Destination.QUEST_GROUP, it.groupId)) else back()
    }
    /** Only validated server acknowledgements/authorized reads reach here; preserve paging and order. */
    private fun reconcileGroupInvitation(invitation: QuestGroupInvitation) {
        mutable.update { s -> s.copy(groupInvitations = s.groupInvitations.copy(value = s.groupInvitations.value?.let { batch ->
            batch.copy(items = batch.items.map { if (it.id == invitation.id) invitation else it })
        })) }
    }
    fun leaveGroup(disband: Boolean) = groupWrite({ if (disband) runtime.questGroups.disbandGroup(state.value.route.id, runtime.newOperationKey())
        else runtime.questGroups.leaveGroup(state.value.route.id, runtime.newOperationKey()) }) {
        val id = state.value.route.id
        mutable.update { s -> s.copy(stack = s.stack.dropLastWhile { it.destination == Destination.QUEST_GROUP && it.id == id }
            .ifEmpty { listOf(TravelerRoute(Destination.QUEST_GROUPS)) }, groupManagementDraft = QuestGroupManagementDraft(), saved = true) }
        refresh()
    }
    fun transferGroup(profileId: String) = groupWrite({ runtime.questGroups.transferManager(state.value.route.id, profileId, language, runtime.newOperationKey()) }) { refresh() }
    private fun <T> groupWrite(call: suspend () -> CatalogResult<T>, success: suspend (T) -> Unit) {
        if (state.value.identity.generation != runtime.identity.generation) return
        write(call, journal = MutationJournal.QUEST_GROUP, success = success)
    }

    private fun loadGroupInvitations(page: Int = 1) {
        val route = state.value.route
        val append = page > 1
        if (route.destination == Destination.QUEST_GROUP_INVITATIONS && !append && groupInvitationsRoute != route) {
            reads.remove("groupInvitations")?.cancel()
            mutable.update { it.copy(groupInvitations = ReadState(), groupInvitationsAppending = false, groupInvitationsAppendFailure = null) }
        }
        val current = state.value
        if (route.destination != Destination.QUEST_GROUP_INVITATIONS || current.groupInvitations.loading || current.groupInvitationsAppending ||
            append && (current.groupInvitationsAppendFailure?.retryAtMillis ?: 0) > now()) return
        if (!append) groupInvitationsRoute = route
        fetch("groupInvitations", { if (append) it.groupInvitations.copy(failure = it.groupInvitationsAppendFailure) else it.groupInvitations }, { s, r ->
            if (append && r.failure?.status in setOf(401,403,404))
                s.copy(groupInvitations = r.copy(value = null), groupInvitationsAppending = false, groupInvitationsAppendFailure = null)
            else if (append) s.copy(groupInvitationsAppending = r.loading, groupInvitationsAppendFailure = r.failure,
                groupInvitations = if (!r.loading && r.failure == null && r.value != null)
                    r.copy(value = r.value.copy(items = (s.groupInvitations.value?.items.orEmpty() + r.value.items).distinctBy { it.id })) else s.groupInvitations)
            else s.copy(groupInvitations = r, groupInvitationsAppending = false, groupInvitationsAppendFailure = null,
                groupInvitationsScrollIndex = 0, groupInvitationsScrollOffset = 0)
        }) { runtime.questGroups.invitations(page, language, route.id.takeIf(String::isNotBlank)) }
    }
    fun openGroupInvitation(id: String, index: Int, offset: Int) {
        mutable.update { it.copy(groupInvitationsScrollIndex = index, groupInvitationsScrollOffset = offset) }
        open(TravelerRoute(Destination.QUEST_GROUP_INVITATION, id))
    }
    private fun loadOpenTrips(cursor: String? = null) {
        val query = state.value.query
        val locationId = state.value.route.takeIf { it.destination == Destination.OPEN_TRIPS }?.id?.takeIf(String::isNotBlank)
        fetch("openTrips", { if (cursor == null) ReadState() else it.openTrips }, { s, r ->
            s.copy(openTrips = if (cursor != null && r.value != null && s.openTrips.value != null)
                r.copy(value = r.value.copy(items = (s.openTrips.value.items + r.value.items).distinctBy { it.id })) else r) }) {
            runtime.trips.openTrips(OpenTripQuery(query = query, locationId = locationId, cursor = cursor)) }
    }
    private fun moreApplications(cursor: String) {
        val route = state.value.route
        fetch("applications", { it.applications }, { s, r -> s.copy(applications = if (r.value != null)
            r.copy(value = r.value.copy(items = (s.applications.value?.items.orEmpty() + r.value.items).distinctBy { it.id })) else r) }) {
            if (route.destination == Destination.APPLICATIONS) runtime.trips.myTripApplications(cursor = cursor)
            else runtime.trips.tripApplications(route.id, cursor)
        }
    }
    fun more() {
        val s = state.value
        when (s.route.destination) {
            Destination.QUEST_GROUPS -> s.groups.value?.takeIf { it.page < it.totalPages }?.let { loadGroups(it.page + 1) }
            Destination.QUEST_GROUP_INVITATIONS -> s.groupInvitations.value?.takeIf { !s.groupInvitations.loading && !s.groupInvitationsAppending && it.page < it.totalPages }?.let { loadGroupInvitations(it.page + 1) }
            Destination.APPLICATIONS, Destination.TRIP_APPLICATIONS -> if (!s.applications.loading) s.applications.value?.nextCursor?.let(::moreApplications)
            Destination.APPLY -> if (!s.openTripStops.loading) s.openTripStops.value?.nextCursor?.let { cursor ->
                fetch("openTripStops", { it.openTripStops }, { old, r -> old.copy(openTripStops = if (r.value != null)
                    r.copy(value = r.value.copy(items = (old.openTripStops.value?.items.orEmpty() + r.value.items).distinctBy { Triple(it.spot.id, it.day, it.position) })) else r) }) {
                    runtime.trips.openTripStops(s.route.id, cursor)
                }
            }
            Destination.COMMUNITY -> when (s.communityTab) { 0 -> if (s.publicCanRequestNext && !s.publicPlans.loading) publicPlans(s.publicPage + 1)
                1 -> s.openTrips.value?.nextCursor?.let(::loadOpenTrips); else -> s.catalog.value?.takeIf { it.page < it.totalPages }?.let { catalog(it.page + 1) } }
            Destination.SAVED_SPOTS -> s.savedSpots.value?.takeIf { it.page < it.totalPages }?.let { loadSaved(it.page + 1) }
            Destination.OPEN_TRIPS -> s.openTrips.value?.nextCursor?.let(::loadOpenTrips)
            Destination.CATALOG, Destination.EXPLORER, Destination.ADD_SPOT ->
                s.catalog.value?.takeIf { it.page < it.totalPages }?.let { catalog(it.page + 1) }
            Destination.QUESTS -> if (!s.questBrowse.taken) s.quests.value?.takeIf { !s.quests.loading && s.quests.failure == null && it.page < it.totalPages }?.let { loadQuestCatalog(it.page + 1) }
            Destination.NOTIFICATIONS -> s.notifications.value?.takeIf { !s.notifications.loading && it.page < it.totalPages }?.let { loadNotifications(it.page + 1) }
            Destination.PUBLIC_PROFILE -> when {
                s.route.extra == "badges" -> s.publicBadges.value?.takeIf { !s.publicBadges.loading && it.page < it.totalPages }?.let { loadPublicProfileContent(it.page + 1) }
                s.route.extra == "saved" && s.ownsPublicProfile -> s.savedSpots.value?.takeIf { !s.savedSpots.loading && it.page < it.totalPages }?.let { loadPublicProfileContent(it.page + 1) }
                else -> Unit
            }
            else -> Unit
        }
    }

    fun login(email: String, password: String) = auth { runtime.identity.login(email, password) }
    fun loginGoogle(idToken: String) = auth { runtime.identity.loginGoogle(idToken) }
    fun googleUnavailable() { mutable.update { it.copy(writeFailure = ReadFailure(0, "GOOGLE_UNAVAILABLE")) } }
    fun imageUnavailable() { mutable.update { it.copy(writeFailure = ReadFailure(422, "IMAGE_UNAVAILABLE")) } }
    fun verify(email: String, code: String) = auth { runtime.identity.verifyEmail(email, code) }
    private fun auth(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutable.update { it.copy(busy = true, writeFailure = null) }
        viewModelScope.launch {
            try {
                withContext(worker) { action() }
                val identity = runtime.identity.view.first()
                mutable.update { it.copy(identity = identity, busy = false) }
                if (identity.signedIn) {
                    mutable.update { current ->
                        val stack = signedInStack(current.stack)
                        current.copy(registrationStep = 1, stack = stack, tab = rootTab(stack))
                    }
                    refresh()
                }
                else if (identity.googleOnboardingRequired) { open(TravelerRoute(Destination.REGISTER)); registrationStep(2) }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private fun initializeQuestCatalog() {
        val route = state.value.route
        if (state.value.questBrowse.scope == route.id) return
        mutable.update { it.copy(questBrowse = QuestBrowseState(scope = route.id,
            filter = QuestDiscoveryFilter(locationId = route.id.takeIf(String::isNotBlank), excludeTaken = route.id.isNotBlank(),
                availability = "active".takeIf { route.id.isNotBlank() })), quests = ReadState()) }
    }
    private fun loadQuestCatalog(page: Int = 1) {
        val browse = state.value.questBrowse
        if (browse.taken) {
            fetch("taken-quests", { it.questBrowse.takenRows }, { old, read ->
                if (old.questBrowse.revision == browse.revision) old.copy(questBrowse = old.questBrowse.copy(takenRows = read)) else old
            }) { runtime.identity.takenQuests(browse.filter.participationMode) }
        } else fetch("quests", { it.quests }, { old, read ->
            if (old.questBrowse.revision != browse.revision) old else old.copy(questBrowse = old.questBrowse.copy(requestedPage = page), quests = if (page > 1 && read.value != null)
                read.copy(value = read.value.copy(items = (old.quests.value?.items.orEmpty() + read.value.items).distinctBy { it.id })) else read)
        }) { runtime.identity.questCatalog(browse.filter, page) }
    }
    fun retryQuestCatalog() {
        val current = state.value
        if (current.route.destination == Destination.QUESTS && !current.questBrowse.taken &&
            !current.quests.loading && current.quests.failure != null) loadQuestCatalog(current.questBrowse.requestedPage)
    }
    fun questCollection(taken: Boolean) {
        val current = state.value
        if (current.busy || current.route.destination != Destination.QUESTS || current.questBrowse.taken == taken) return
        reads.remove("quests")?.cancel(); reads.remove("taken-quests")?.cancel()
        mutable.update { it.copy(quests = ReadState(), questBrowse = it.questBrowse.copy(taken = taken,
            revision = it.questBrowse.revision + 1, takenRows = ReadState())) }
        loadQuestCatalog()
    }
    fun questMode(mode: String?) {
        val current = state.value
        if (current.busy || current.route.destination != Destination.QUESTS || mode != null && mode !in setOf("personal", "group") ||
            current.questBrowse.filter.participationMode == mode) return
        reads.remove("quests")?.cancel(); reads.remove("taken-quests")?.cancel()
        mutable.update { it.copy(quests = ReadState(), questBrowse = it.questBrowse.copy(
            filter = it.questBrowse.filter.copy(participationMode = mode), revision = it.questBrowse.revision + 1, takenRows = ReadState())) }
        loadQuestCatalog()
    }
    fun openQuestFilter() {
        if (state.value.route.destination != Destination.QUESTS || state.value.questBrowse.taken) return
        mutable.update { old -> old.copy(questBrowse = old.questBrowse.copy(draft = old.questBrowse.draft ?: old.questBrowse.filter.let { filter ->
            QuestFilterDraft(filter.minXP?.toString().orEmpty(), filter.maxXP?.toString().orEmpty(), filter.locationId?.let { LocationReference(it, filter.locationName.orEmpty(), "", "") })
        })) }
        open(TravelerRoute(Destination.QUEST_FILTER))
    }
    fun questXpDraft(minimum: String? = null, maximum: String? = null) {
        if (state.value.route.destination != Destination.QUEST_FILTER) return
        mutable.update { old -> val draft = old.questBrowse.draft ?: QuestFilterDraft()
            old.copy(questBrowse = old.questBrowse.copy(draft = draft.copy(minimum = minimum ?: draft.minimum, maximum = maximum ?: draft.maximum))) }
    }
    fun resetQuestFilterDraft() {
        if (state.value.route.destination == Destination.QUEST_FILTER) mutable.update { it.copy(questBrowse = it.questBrowse.copy(draft = QuestFilterDraft())) }
    }
    fun applyQuestFilter() {
        val current = state.value; val draft = current.questBrowse.draft ?: return
        if (current.busy || current.route.destination != Destination.QUEST_FILTER || !draft.valid) return
        mutable.update { it.copy(quests = ReadState(), questBrowse = it.questBrowse.copy(revision = it.questBrowse.revision + 1,
            filter = it.questBrowse.filter.copy(minXP = draft.minimum.toIntOrNull(), maxXP = draft.maximum.toIntOrNull(),
                locationId = draft.location?.id, locationName = draft.location?.name), pickerOpen = false)) }
        back()
    }
    fun clearQuestFilters() {
        if (state.value.route.destination != Destination.QUESTS) return
        reads.remove("quests")?.cancel()
        mutable.update { it.copy(quests = ReadState(), questBrowse = it.questBrowse.copy(revision = it.questBrowse.revision + 1,
            filter = it.questBrowse.filter.copy(minXP = null, maxXP = null, locationId = null, locationName = null), draft = null)) }
        loadQuestCatalog()
    }
    private fun loadQuestFilterLocation() {
        val filter = state.value.questBrowse.filter
        val id = filter.locationId?.takeIf { filter.locationName.isNullOrBlank() } ?: return
        fetch("quest-filter-location", { ReadState<CatalogEntry>() }, { old, _ -> old }, ready = { entry ->
            if (entry.id == id) mutable.update { old ->
                if (old.questBrowse.filter.locationId != id) old else old.copy(questBrowse = old.questBrowse.copy(
                    filter = old.questBrowse.filter.copy(locationName = entry.title), draft = old.questBrowse.draft?.let { draft ->
                        if (draft.location?.id == id) draft.copy(location = draft.location.copy(name = entry.title)) else draft }))
            }
        }) { runtime.catalog.detail(CatalogEntry(id, id, "", null, CatalogKind.LOCATION, null), language) }
    }
    fun openQuestLocationPicker() {
        if (state.value.route.destination != Destination.QUEST_FILTER) return
        mutable.update { it.copy(questBrowse = it.questBrowse.copy(pickerOpen = true, pickerQuery = "")) }
        loadQuestPickerLocations()
    }
    fun closeQuestLocationPicker() {
        reads.remove("quest-picker")?.cancel()
        mutable.update { it.copy(questBrowse = it.questBrowse.copy(pickerOpen = false)) }
    }
    fun questLocationQuery(query: String) {
        if (!state.value.questBrowse.pickerOpen) return
        mutable.update { it.copy(questBrowse = it.questBrowse.copy(pickerQuery = query, pickerRows = ReadState())) }
        loadQuestPickerLocations()
    }
    fun loadQuestPickerLocations() {
        val query = state.value.questBrowse.pickerQuery
        if (!state.value.questBrowse.pickerOpen) return
        fetch("quest-picker", { it.questBrowse.pickerRows }, { old, r ->
            if (old.questBrowse.pickerOpen && old.questBrowse.pickerQuery == query) old.copy(questBrowse = old.questBrowse.copy(pickerRows = r)) else old
        }) {
            if (query.isBlank()) runtime.catalog.topLocations(language, 20)
            else if (query.trim().length < 3) CatalogResult.Ready(emptyList())
            else { delay(300); runtime.catalog.searchLocations(query.trim(), language) }
        }
    }
    fun selectQuestFilterLocation(entry: CatalogEntry?) {
        if (!state.value.questBrowse.pickerOpen || entry != null && entry.kind != CatalogKind.LOCATION) return
        mutable.update { old -> old.copy(questBrowse = old.questBrowse.copy(draft = (old.questBrowse.draft ?: QuestFilterDraft()).copy(
            location = entry?.let { LocationReference(it.id, it.title, it.slug, it.locationLevel.orEmpty()) }), pickerOpen = false)) }
        reads.remove("quest-picker")?.cancel()
    }
    fun loadQuestSpot(id: String) {
        if (state.value.route.destination != Destination.QUEST || state.value.quest.value?.steps?.none { it.spotId == id } != false) return
        fetch("quest-spot-$id", { it.questSpots[id] ?: ReadState() }, { old, r -> old.copy(questSpots = old.questSpots + (id to r)) }) {
            when (val result = runtime.catalog.detail(CatalogEntry(id, id, "", null, CatalogKind.SPOT, null), language)) {
                is CatalogResult.Ready -> if (result.value.id == id && result.value.kind == CatalogKind.SPOT) result else CatalogResult.InvalidResponse
                else -> result
            }
        }
    }
    fun logout() = auth { runtime.identity.logout() }
    fun takeQuest() {
        val current = state.value; val quest = current.quest.value ?: return
        if (current.route.destination != Destination.QUEST || current.quest.loading || current.quest.failure != null || !quest.canTake(current.identity)) return
        progressAction { runtime.identity.takeQuest() }
    }
    fun continueQuest() {
        val current = state.value; val owner = current.quest.value?.boundOwner() ?: return
        if (current.route.destination != Destination.QUEST || current.quest.loading || current.quest.failure != null || owner.status != "active" || owner.participation == null) return
        progressAction { runtime.identity.continueQuest() }
    }
    fun continueQuest(id: String) = progressAction { runtime.identity.continueQuest(id) }
    private fun progressAction(action: suspend () -> Unit) {
        val initial = state.value
        if (initial.busy || initial.identity.busy || !initial.identity.signedIn || initial.identity.generation != runtime.identity.generation) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                withContext(worker) { if (runtime.identity.generation == initial.identity.generation) action() }
                val current = runtime.identity.view.first()
                if (state.value.route == initial.route && runtime.identity.generation == initial.identity.generation) {
                    mutable.update { it.copy(identity = current, busy = false) }
                    if (current.failure == null && current.running != null) open(TravelerRoute(Destination.QUEST_PROGRESS, current.running.id))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (runtime.identity.generation == initial.identity.generation) mutable.update { it.copy(writeFailure = ReadFailure()) } }
            finally { if (state.value.route == initial.route && runtime.identity.generation == initial.identity.generation) mutable.update { it.copy(busy = false) } }
        }
    }
    fun activity(id: String) {
        open(TravelerRoute(Destination.QUEST_ACTIVITY, id, state.value.identity.running?.id.orEmpty()))
    }
    fun answer(value: String) = identityAction { runtime.identity.answer(value) }
    fun gps(submit: Boolean = false) = identityAction { if (submit) runtime.identity.submitGps() else runtime.identity.collectGps() }
    private fun identityAction(action: suspend () -> Unit) {
        if (state.value.identity.busy) return
        viewModelScope.launch(worker) { action() }
    }

    fun saveItinerary(draft: ItineraryDraft) {
        val route = state.value.route
        write({ if (route.id.isBlank()) runtime.itineraries.create(draft).catalog() else runtime.itineraries.update(route.id, draft) }, journal = if (route.id.isBlank()) MutationJournal.ITINERARY else null) { result ->
            replace(TravelerRoute(Destination.ITINERARY, result.itinerary.id))
        }
    }
    fun addSpot(itineraryId: String, spotId: String, day: Int) = itineraryWrite({ runtime.itineraries.addStop(itineraryId, spotId, day).catalog() }) { result ->
        if (state.value.route.destination == Destination.PICK_PLAN) back() else replace(TravelerRoute(Destination.ITINERARY, itineraryId))
        mutable.update { it.copy(feedback = SpotAdded(itineraryId, result.itinerary.title, day)) }
    }
    fun saveStop(stop: OwnedStop) = write({ runtime.itineraries.updateStop(state.value.route.id, stop) }) { back() }
    fun dayTitle(day: Int, title: String) = write({ runtime.itineraries.titleDay(state.value.route.id, day, title) }) { refresh() }
    fun removeStop(stopId: String) {
        val route = state.value.route
        write({ runtime.itineraries.removeStop(route.id, stopId) }) {
            if (route.destination == Destination.STOP_FORM && route.extra == stopId) back() else refresh()
        }
    }

    fun publish(allowClone: Boolean, notes: Boolean, pii: Boolean) {
        val route = state.value.route
        val id = route.id
        val revision = state.value.distribution.value?.updatedAt ?: return
        val review = state.value.pendingPublication.value ?: ItineraryPublicationReview(revision, allowClone, notes, pii)
        val key = runtime.newOperationKey()
        write({
            val fresh = runtime.itineraries.detail(id)
            if (fresh !is CatalogResult.Ready) fresh.failure()
            else if (fresh.value.itinerary.id != id) CatalogResult.InvalidResponse
            else if (fresh.value.stops.isEmpty() || !fresh.value.stopsLoaded) CatalogResult.HttpError(422, code = "EMPTY_ITINERARY")
            else runtime.distribution.publish(id, review, key)
        }) { result ->
            if (state.value.route != route) return@write
            mutable.update { it.copy(distribution = ReadState(result), pendingPublication = ReadState(), saved = true) }
            reloadPublication(id)
        }
    }
    fun shareLink(create: Boolean) = write({
        val id = state.value.route.id
        if (create) runtime.distribution.rotateShareLink(id, runtime.newOperationKey()) else runtime.distribution.shareLink(id)
    }) { link -> mutable.update { it.copy(shareLink = ReadState(link), saved = true) } }
    fun unpublish() {
        val route = state.value.route
        write({ runtime.distribution.unpublish(route.id, runtime.newOperationKey()) }) { result ->
            if (state.value.route != route) return@write
            mutable.update { it.copy(distribution = ReadState(result), saved = true) }
            reloadPublication(route.id)
        }
    }
    private fun reloadPublication(id: String) {
        loadItinerary(id)
        fetch("distribution", { it.distribution }, { s, r -> s.copy(distribution = r) }) { runtime.distribution.read(id) }
    }
    fun revokeLink() = write({ runtime.distribution.revokeShareLink(state.value.route.id, runtime.newOperationKey()) }) { refresh() }
    fun createTrip(draft: TripDraft, image: TravelerImage? = null) {
        val route = state.value.route
        if (route.destination != Destination.TRIP_FORM || route.operationKey.isBlank()) return
        write({
            val recovered = createdTrip(route)
            val result = when (recovered) {
                is CatalogResult.Ready -> recovered.value?.let { CatalogResult.Ready(it) }
                    ?: runtime.trips.create(route.id, draft, route.operationKey)
                else -> recovered.failure()
            }
            if (result !is CatalogResult.Ready) result.failure()
            else {
                val binding = runtime.trips.rememberTripCreationRoute(route.operationKey, result.value.id)
                if (binding !is CatalogResult.Ready) return@write binding.failure()
                // Once confirmed, a banner failure is a partial result, never a failed Trip create.
                runtime.trips.completeTripCreation(result.value.id)
                val banner = image?.let { runtime.trips.uploadBanner(result.value.id, it.bytes(), it.mimeType, runtime.newOperationKey()) }
                CatalogResult.Ready(result.value to banner)
            }
        }, journal = MutationJournal.TRIP) { (trip, banner) ->
            replace(TravelerRoute(Destination.TRIP, trip.id))
            if (banner != null) {
                mutable.update { it.copy(media = ReadState<CoverProcessing?>().result(banner.bannerProcessing(), now()), mediaPartial = banner !is CatalogResult.Ready) }
                if (banner is CatalogResult.Ready) watchMedia(true, banner.value.processing)
            }
        }
    }
    private suspend fun createdTrip(route: TravelerRoute): CatalogResult<TravelerTrip?> =
        when (val exact = runtime.trips.createdTripForOperation(route.operationKey)) {
            is CatalogResult.Ready -> if (exact.value != null) exact else runtime.trips.pendingCreatedTrip(route.id)
            else -> exact
        }
    fun uploadImage(image: TravelerImage, banner: Boolean = false) {
        val id = state.value.route.id
        write({
            val status = if (banner) runtime.trips.bannerStatus(id).bannerProcessing() else runtime.distribution.cover(id).coverProcessing()
            when {
                status is CatalogResult.Ready && status.value in setOf(CoverProcessing.UPLOADED, CoverProcessing.PROCESSING) -> CatalogResult.HttpError(409, code = "MEDIA_PROCESSING")
                status !is CatalogResult.Ready && !(status is CatalogResult.HttpError && status.status == 404) -> status.failure()
                banner -> runtime.trips.uploadBanner(id, image.bytes(), image.mimeType, runtime.newOperationKey()).bannerProcessing()
                else -> runtime.distribution.uploadCover(id, image.bytes(), image.mimeType, runtime.newOperationKey()).coverProcessing()
            }
        }) { status ->
            mutable.update { it.copy(media = ReadState<CoverProcessing?>(status), mediaPartial = false) }
            watchMedia(banner, status)
            if (status == CoverProcessing.READY) refreshMediaOwner(banner)
        }
    }
    private fun loadMedia(banner: Boolean) = fetch("media", { it.media }, { s, r -> s.copy(media = r) }, ready = { status ->
        if (status != null) watchMedia(banner, status)
    }) { mediaStatus(state.value.route.id, banner) }
    private suspend fun mediaStatus(id: String, banner: Boolean): CatalogResult<CoverProcessing?> {
        val result = if (banner) runtime.trips.bannerStatus(id).bannerProcessing() else runtime.distribution.cover(id).coverProcessing()
        return if (result is CatalogResult.HttpError && result.status == 404) CatalogResult.Ready(null) else result
    }
    private fun watchMedia(banner: Boolean, status: CoverProcessing) {
        if (status !in setOf(CoverProcessing.UPLOADED, CoverProcessing.PROCESSING) || reads["media-watch"]?.isActive == true) return
        val route = state.value.route; val epoch = runtime.identity.generation
        reads["media-watch"] = viewModelScope.launch {
            repeat(15) {
                delay(2_000)
                val result = withContext(worker) { mediaStatus(route.id, banner) }
                if (state.value.route != route || runtime.identity.generation != epoch) return@launch
                mutable.update { it.copy(media = it.media.result(result, now())) }
                if (result !is CatalogResult.Ready || result.value !in setOf(CoverProcessing.UPLOADED, CoverProcessing.PROCESSING)) {
                    if (result is CatalogResult.Ready && result.value == CoverProcessing.READY) refreshMediaOwner(banner)
                    return@launch
                }
            }
        }
    }
    private fun refreshMediaOwner(banner: Boolean) {
        val id = state.value.route.id
        if (banner) fetch("trip", { it.trip }, { s, r -> s.copy(trip = r) }) { runtime.trips.detail(id) }
        else {
            loadItinerary(id)
            if (state.value.route.destination == Destination.PUBLICATION)
                fetch("distribution", { it.distribution }, { s, r -> s.copy(distribution = r) }) { runtime.distribution.read(id) }
        }
    }
    fun apply(message: String) = tripWrite({ runtime.trips.applyToOpenTrip(state.value.route.id, message, runtime.newOperationKey()) }) { application ->
        replace(TravelerRoute(Destination.APPLICATION, application.id))
    }
    fun decision(decision: TripApplicationDecision) = tripWrite({ runtime.trips.decideTripApplication(state.value.route.id, decision, runtime.newOperationKey()) }) { result ->
        mutable.update { it.copy(application = ReadState(result), saved = true) }
    }
    fun lifecycle(action: TripLifecycleAction) = tripWrite({ runtime.trips.lifecycle(state.value.route.id, action, runtime.newOperationKey()) }) { back() }
    fun invite(recipient: String) = tripWrite({ runtime.trips.invite(state.value.route.id, recipient, runtime.newOperationKey()) }) { refresh() }
    fun invitationDecision(id: String, decision: InvitationDecision) = tripWrite({ runtime.trips.decide(id, decision, runtime.newOperationKey()) }) { refresh() }
    fun removeMember(id: String) = tripWrite({ runtime.trips.removeMember(state.value.route.id, id, runtime.newOperationKey()) }) { refresh() }

    /** Reviewing only reads; neither navigation nor Refresh can release or replay the saved write. */
    fun reviewTripMutation() = recoverTripMutation(false)
    fun startNewTripAction() = recoverTripMutation(true)
    fun closeTripMutationReview() {
        if (!state.value.busy) mutable.update { it.copy(tripMutationRecovery = it.tripMutationRecovery.copy(review = ReadState())) }
    }
    private fun recoverTripMutation(startNew: Boolean) {
        val initial = state.value
        if (!initial.tripMutationRecovery.required || !initial.identity.signedIn || initial.busy || writeJob?.isActive == true ||
            initial.identity.generation != runtime.identity.generation ||
            startNew && (initial.tripMutationRecovery.review.value == null || initial.tripMutationRecovery.review.failure != null)) return
        val epoch = runtime.identity.generation
        if ((initial.tripMutationRecovery.review.failure?.retryAtMillis ?: 0) > now()) return
        mutable.update { it.copy(busy = true, tripMutationRecovery = it.tripMutationRecovery.copy(
            review = it.tripMutationRecovery.review.copy(loading = true, failure = null)),
            itineraryMutationRecovery = it.itineraryMutationRecovery.copy(review = ReadState()),
            questGroupMutationRecovery = it.questGroupMutationRecovery.copy(review = ReadState())) }
        writeJob = viewModelScope.launch {
            try {
                if (startNew) {
                    val result = withContext(worker) { runtime.trips.startNewAction() }
                    if (runtime.identity.generation != epoch) return@launch
                    if (result is CatalogResult.Ready) {
                        mutable.update { it.copy(busy = false,
                            unknownOutcome = if (it.writeFailureJournal == MutationJournal.TRIP) false else it.unknownOutcome,
                            writeFailure = if (it.writeFailureJournal == MutationJournal.TRIP) null else it.writeFailure,
                            writeFailureJournal = if (it.writeFailureJournal == MutationJournal.TRIP) null else it.writeFailureJournal,
                            tripMutationRecovery = TripMutationRecoveryState()) }
                        refresh()
                    } else mutable.update { it.copy(tripMutationRecovery = TripMutationRecoveryState(true,
                        ReadState<List<TravelerTrip>>().result(result.failure(), now()))) }
                } else {
                    val result = withContext(worker) { runtime.trips.reviewPendingMutation() }
                    if (runtime.identity.generation != epoch) return@launch
                    mutable.update { it.copy(tripMutationRecovery = TripMutationRecoveryState(true,
                        ReadState<List<TravelerTrip>>().result(result, now()))) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (runtime.identity.generation == epoch) mutable.update { it.copy(tripMutationRecovery = TripMutationRecoveryState(true,
                    ReadState(failure = ReadFailure()))) }
            } finally {
                if (runtime.identity.generation == epoch) mutable.update { it.copy(busy = false,
                    tripMutationRecovery = it.tripMutationRecovery.copy(review = it.tripMutationRecovery.review.copy(loading = false))) }
            }
        }
    }

    private fun <T> tripWrite(call: suspend () -> CatalogResult<T>, success: suspend (T) -> Unit) =
        write(call, journal = MutationJournal.TRIP, success = success)

    fun reviewItineraryMutation() = recoverItineraryMutation(false)
    fun startNewItineraryAction() = recoverItineraryMutation(true)
    fun closeItineraryMutationReview() {
        if (!state.value.busy) mutable.update {
            it.copy(itineraryMutationRecovery = it.itineraryMutationRecovery.copy(review = ReadState()))
        }
    }
    private fun recoverItineraryMutation(startNew: Boolean) {
        val initial = state.value
        val review = initial.itineraryMutationRecovery.review
        if (!initial.itineraryMutationRecovery.required || !initial.identity.signedIn || initial.busy || writeJob?.isActive == true ||
            initial.identity.generation != runtime.identity.generation ||
            startNew && (review.value == null || review.failure != null) || (review.failure?.retryAtMillis ?: 0) > now()) return
        val epoch = runtime.identity.generation
        mutable.update {
            it.copy(busy = true, itineraryMutationRecovery = it.itineraryMutationRecovery.copy(
                review = review.copy(loading = true, failure = null)),
                tripMutationRecovery = it.tripMutationRecovery.copy(review = ReadState()),
                questGroupMutationRecovery = it.questGroupMutationRecovery.copy(review = ReadState()))
        }
        writeJob = viewModelScope.launch {
            try {
                if (startNew) {
                    val result = withContext(worker) { runtime.itineraries.startNewAction() }
                    if (runtime.identity.generation != epoch) return@launch
                    if (result is CatalogResult.Ready) {
                        mutable.update {
                            it.copy(busy = false,
                                unknownOutcome = if (it.writeFailureJournal == MutationJournal.ITINERARY) false else it.unknownOutcome,
                                writeFailure = if (it.writeFailureJournal == MutationJournal.ITINERARY) null else it.writeFailure,
                                writeFailureJournal = if (it.writeFailureJournal == MutationJournal.ITINERARY) null else it.writeFailureJournal,
                                itineraryMutationRecovery = ItineraryMutationRecoveryState())
                        }
                        refresh()
                    } else mutable.update {
                        it.copy(itineraryMutationRecovery = ItineraryMutationRecoveryState(true,
                            ReadState<List<OwnedItinerary>>().result(result.failure(), now())))
                    }
                } else {
                    val result = withContext(worker) { runtime.itineraries.reviewPendingMutation() }
                    if (runtime.identity.generation != epoch) return@launch
                    mutable.update {
                        it.copy(itineraryMutationRecovery = ItineraryMutationRecoveryState(true,
                            ReadState<List<OwnedItinerary>>().result(result, now())))
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (runtime.identity.generation == epoch) mutable.update {
                    it.copy(itineraryMutationRecovery = ItineraryMutationRecoveryState(true, ReadState(failure = ReadFailure())))
                }
            } finally {
                if (runtime.identity.generation == epoch) mutable.update {
                    it.copy(busy = false, itineraryMutationRecovery = it.itineraryMutationRecovery.copy(
                        review = it.itineraryMutationRecovery.review.copy(loading = false)))
                }
            }
        }
    }
    private fun <T> itineraryWrite(call: suspend () -> CatalogResult<T>, success: suspend (T) -> Unit) =
        write(call, journal = MutationJournal.ITINERARY, success = success)

    fun showQuestGroupRecovery() {
        if (state.value.questGroupMutationRecovery.required) open(TravelerRoute(Destination.QUEST_GROUP_RECOVERY))
    }
    fun reviewQuestGroupMutation() = recoverQuestGroupMutation(false)
    fun startNewQuestGroupAction() = recoverQuestGroupMutation(true)
    fun closeQuestGroupMutationReview() {
        if (!state.value.busy) mutable.update { it.copy(questGroupMutationRecovery = it.questGroupMutationRecovery.copy(review = ReadState())) }
    }
    private fun recoverQuestGroupMutation(startNew: Boolean) {
        val initial = state.value
        val review = initial.questGroupMutationRecovery.review
        if (!initial.questGroupMutationRecovery.required || !initial.identity.signedIn || initial.busy || writeJob?.isActive == true ||
            initial.identity.generation != runtime.identity.generation ||
            startNew && (review.value == null || review.failure != null) || (review.failure?.retryAtMillis ?: 0) > now()) return
        val epoch = runtime.identity.generation
        mutable.update { it.copy(busy = true, questGroupMutationRecovery = it.questGroupMutationRecovery.copy(
            review = review.copy(loading = true, failure = null)), tripMutationRecovery = it.tripMutationRecovery.copy(review = ReadState()),
            itineraryMutationRecovery = it.itineraryMutationRecovery.copy(review = ReadState())) }
        writeJob = viewModelScope.launch {
            try {
                if (startNew) {
                    val result = withContext(worker) { runtime.questGroups.startNewAction() }
                    if (runtime.identity.generation != epoch) return@launch
                    if (result is CatalogResult.Ready) {
                        mutable.update { it.copy(busy = false,
                            unknownOutcome = if (it.writeFailureJournal == MutationJournal.QUEST_GROUP) false else it.unknownOutcome,
                            writeFailure = if (it.writeFailureJournal == MutationJournal.QUEST_GROUP) null else it.writeFailure,
                            writeFailureJournal = if (it.writeFailureJournal == MutationJournal.QUEST_GROUP) null else it.writeFailureJournal,
                            questGroupMutationRecovery = QuestGroupMutationRecoveryState()) }
                        if (state.value.route.destination == Destination.QUEST_GROUP_RECOVERY) back() else refresh()
                    } else mutable.update { it.copy(questGroupMutationRecovery = it.questGroupMutationRecovery.copy(
                        review = ReadState<QuestGroupRecoveryReview>().result(result.failure(), now()))) }
                } else {
                    val result = withContext(worker) { runtime.questGroups.reviewPendingMutation(language) }
                    if (runtime.identity.generation != epoch) return@launch
                    mutable.update { it.copy(questGroupMutationRecovery = it.questGroupMutationRecovery.copy(
                        review = ReadState<QuestGroupRecoveryReview>().result(result, now()))) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (runtime.identity.generation == epoch) mutable.update { it.copy(questGroupMutationRecovery = it.questGroupMutationRecovery.copy(
                    review = ReadState(failure = ReadFailure()))) }
            } finally {
                if (runtime.identity.generation == epoch) mutable.update { it.copy(busy = false,
                    questGroupMutationRecovery = it.questGroupMutationRecovery.copy(review = it.questGroupMutationRecovery.review.copy(loading = false))) }
            }
        }
    }

    private fun loadNotifications(page: Int = 1) {
        val filter = state.value.notificationClass
        val previous = state.value.notifications.value
        mutable.update { it.copy(notificationAppending = page > 1) }
        fetch("notifications", { it.notifications }, { s, r -> s.copy(notifications = if (page > 1 && r.value != null)
            r.copy(value = r.value.copy(items = (previous?.items.orEmpty() + r.value.items).distinctBy { it.id })) else r) }) {
            runtime.notifications.notifications(page, filter, language)
        }
    }
    fun openNotification(id: String) = open(TravelerRoute(Destination.NOTIFICATION_OPEN, id))
    /** A restored static destination must revalidate its source; another owner action is not proof of this notification's target. */
    private fun loadOwnerModeration() {
        if ((state.value.ownerModeration.failure?.retryAtMillis ?: 0) > now()) return
        fun actions() = fetch("owner-moderation", { it.ownerModeration }, { s, r -> s.copy(ownerModeration = r) }) {
            runtime.moderation?.actions() ?: CatalogResult.HttpError(503)
        }
        val inbox = state.value.route.inboxId
        if (inbox == null) { actions(); return }
        fetch("moderation-notification", { it.notification }, { s, r ->
            s.copy(notification = r, ownerModeration = if (r.loading || r.failure != null)
                ReadState(loading = r.loading, failure = r.failure) else s.ownerModeration)
        }, ready = { notification ->
            if (notification.id == inbox && notification.targetUrl == "/me/itinerary-moderation") actions()
            else mutable.update { it.copy(ownerModeration = ReadState(failure = ReadFailure(404, "TARGET_UNAVAILABLE"))) }
        }) { runtime.notifications.notification(inbox, language) }
    }
    fun returnToNotifications() {
        if (state.value.busy) return
        reads.values.forEach(Job::cancel); reads.clear()
        mutable.update { s ->
            val index = s.stack.indexOfLast { it.destination == Destination.NOTIFICATIONS }
            s.copy(stack = if (index >= 0) s.stack.take(index + 1) else s.stack.take(1) + TravelerRoute(Destination.NOTIFICATIONS),
                writeFailure = null, notification = ReadState())
        }
        refresh()
    }
    /** Called after the authorized target has rendered a frame, never by list fetch or target GET alone. */
    fun targetVisible(route: TravelerRoute) {
        val s = state.value
        val inbox = route.inboxId ?: return
        if (route != s.route || !s.identity.signedIn || s.identity.generation != runtime.identity.generation) return
        val visible = when (route.destination) {
            Destination.TRIP -> s.trip.value?.id == route.id && s.trip.failure == null && !s.trip.loading
            Destination.APPLICATION -> s.application.value?.id == route.id && s.application.failure == null && !s.application.loading
            Destination.NOTICE -> s.notice.value?.id == route.id && s.notice.failure == null && !s.notice.loading
            Destination.QUEST_GROUP -> s.group.value?.id == route.id && s.group.failure == null && !s.group.loading
            Destination.QUEST_GROUP_INVITATION -> s.groupInvitation.value?.id == route.id && s.groupInvitation.failure == null && !s.groupInvitation.loading
            Destination.OWNER_MODERATION -> s.ownerModerationVisible
            else -> false
        }
        val identity = runtime.identity.generation
        if (!visible || !acknowledgements.add(identity to inbox)) return
        viewModelScope.launch {
            val result = withContext(worker) { runtime.notifications.markNotificationRead(inbox) }
            if (result !is CatalogResult.Ready) acknowledgements.remove(identity to inbox)
        }
    }

    fun register(draft: RegistrationDraft) {
        if (state.value.identity.googleOnboardingRequired) { auth { runtime.identity.completeGoogle(draft) }; return }
        val key = registrationKey ?: runtime.newOperationKey().also { registrationKey = it }
        write(checkConsent = false, call = { runtime.registration.register(draft, key).catalog() }) {
            registrationKey = null; mutable.update { it.copy(verificationEmail = draft.email) }; replace(TravelerRoute(Destination.VERIFY))
        }
    }
    fun registrationStep(step: Int) { mutable.update { it.copy(registrationStep = step.coerceIn(1, 4)) } }
    fun notificationFilter(filter: NotificationClass?) { mutable.update { it.copy(notificationClass = filter, notifications = ReadState()) }; refresh() }
    fun forgot(email: String) = write(checkConsent = false, call = { runtime.registration.forgotPassword(email).catalog() }) {
        mutable.update { it.copy(saved = true) }
    }

    private fun replace(route: TravelerRoute) {
        mutable.update { it.copy(busy = false, stack = it.stack.dropLast(1) + route, writeFailure = null, saved = true) }
        refresh()
    }
    private fun <T> fetch(key: String, current: (TravelerState) -> ReadState<T>,
        update: (TravelerState, ReadState<T>) -> TravelerState, ready: (T) -> Unit = {}, settled: () -> Unit = {},
        routeKey: (TravelerRoute) -> TravelerRoute = { it }, call: suspend () -> CatalogResult<T>) {
        val initial = current(state.value)
        if ((initial.failure?.retryAtMillis ?: 0) > now()) return
        reads.remove(key)?.cancel()
        val route = routeKey(state.value.route); val generation = runtime.identity.generation
        mutable.update { update(it, initial.copy(loading = true, failure = null)) }
        reads[key] = viewModelScope.launch {
            val result = try { withContext(worker) { call() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { CatalogResult.NetworkError }
            ensureActive()
            if (routeKey(state.value.route) == route && runtime.identity.generation == generation) {
                mutable.update { update(it, current(it).result(result, now())) }
                if (result is CatalogResult.Ready) ready(result.value)
                if (routeKey(state.value.route) == route && runtime.identity.generation == generation) settled()
            }
        }
    }
    private fun <T> write(call: suspend () -> CatalogResult<T>, checkConsent: Boolean = true, journal: MutationJournal? = null, success: suspend (T) -> Unit) {
        if (checkConsent && !state.value.identity.signedIn) { requireLogin(); return }
        if (state.value.busy || writeJob?.isActive == true || (state.value.writeFailure?.retryAtMillis ?: 0) > now()) return
        val epoch = runtime.identity.generation
        mutable.update { it.copy(busy = true, writeFailure = null, writeFailureJournal = journal, saved = false,
            tripMutationRecovery = if (journal == MutationJournal.TRIP) it.tripMutationRecovery.copy(review = ReadState()) else it.tripMutationRecovery,
            itineraryMutationRecovery = if (journal == MutationJournal.ITINERARY) it.itineraryMutationRecovery.copy(review = ReadState()) else it.itineraryMutationRecovery,
            questGroupMutationRecovery = if (journal == MutationJournal.QUEST_GROUP) it.questGroupMutationRecovery.copy(review = ReadState(),
                title = it.group.value?.questTitle ?: it.groupInvitation.value?.questTitle ?: it.quest.value?.card?.title ?: it.questGroupMutationRecovery.title) else it.questGroupMutationRecovery) }
        writeJob = viewModelScope.launch {
            try {
                if (checkConsent) {
                    val pending = withContext(worker) { runtime.legal.pending(language) }
                    if (pending !is CatalogResult.Ready) {
                        mutable.update { it.copy(writeFailure = ReadState<Unit>().result(pending.failure(), now()).failure) }; return@launch
                    }
                    if (pending.value.isNotEmpty()) {
                        mutable.update { it.copy(busy = false, legal = ReadState(pending.value), stack = it.stack + TravelerRoute(Destination.LEGAL)) }
                        return@launch
                    }
                }
                val result = withContext(worker) { call() }
                if (runtime.identity.generation != epoch) return@launch
                mutable.update { it.copy(busy = false) }
                if (result is CatalogResult.Ready) { mutable.update { it.copy(unknownOutcome = false, writeFailureJournal = null,
                    tripMutationRecovery = if (journal == MutationJournal.TRIP) TripMutationRecoveryState() else it.tripMutationRecovery,
                    itineraryMutationRecovery = if (journal == MutationJournal.ITINERARY) ItineraryMutationRecoveryState() else it.itineraryMutationRecovery,
                    questGroupMutationRecovery = if (journal == MutationJournal.QUEST_GROUP) QuestGroupMutationRecoveryState() else it.questGroupMutationRecovery) }; success(result.value) }
                else {
                    mutable.update { it.copy(writeFailure = ReadState<T>().result(result, now()).failure,
                        unknownOutcome = result.uncertainWriteResult(),
                        tripMutationRecovery = if (journal == MutationJournal.TRIP && (result.uncertainWriteResult() || result is CatalogResult.HttpError && result.code == TravelerTripRepository.PENDING_MUTATION_CODE))
                            TripMutationRecoveryState(true) else it.tripMutationRecovery,
                        itineraryMutationRecovery = if (journal == MutationJournal.ITINERARY && (result.uncertainWriteResult() || result is CatalogResult.HttpError && result.code in setOf(
                            TravelerItineraryRepository.PENDING_MUTATION_CODE, TravelerItineraryRepository.EXPIRED_MUTATION_CODE)))
                            ItineraryMutationRecoveryState(true) else it.itineraryMutationRecovery,
                        questGroupMutationRecovery = if (journal == MutationJournal.QUEST_GROUP && (result.uncertainWriteResult() || result is CatalogResult.HttpError && result.code in setOf(
                            TravelerQuestGroupRepository.PENDING_MUTATION_CODE, TravelerQuestGroupRepository.EXPIRED_MUTATION_CODE)))
                            it.questGroupMutationRecovery.copy(required = true) else it.questGroupMutationRecovery) }
                    if (journal == MutationJournal.QUEST_GROUP && state.value.questGroupMutationRecovery.required) showQuestGroupRecovery()
                    else if (state.value.unknownOutcome) refresh() // Read recovery only. Never replay a write.
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (runtime.identity.generation == epoch) mutable.update { it.copy(writeFailure = ReadFailure(), unknownOutcome = true,
                tripMutationRecovery = if (journal == MutationJournal.TRIP) TripMutationRecoveryState(true) else it.tripMutationRecovery,
                itineraryMutationRecovery = if (journal == MutationJournal.ITINERARY) ItineraryMutationRecoveryState(true) else it.itineraryMutationRecovery,
                questGroupMutationRecovery = if (journal == MutationJournal.QUEST_GROUP) it.questGroupMutationRecovery.copy(required = true) else it.questGroupMutationRecovery) } }
            finally { if (runtime.identity.generation == epoch) mutable.update { it.copy(busy = false) } }
        }
    }
}

private fun CatalogResult<*>.uncertainWriteResult() = this is CatalogResult.NetworkError || this is CatalogResult.InvalidResponse ||
    this is CatalogResult.Maintenance || this is CatalogResult.HttpError && status >= 500

private val rootTabs = setOf(Destination.HOME, Destination.COMMUNITY, Destination.EXPLORER, Destination.OPEN_TRIPS, Destination.PROFILE)

internal fun Destination.isAuthenticationScreen() = this in setOf(Destination.LOGIN, Destination.REGISTER, Destination.VERIFY, Destination.FORGOT)

private fun rootTab(stack: List<TravelerRoute>) = stack.first().destination.takeIf { it in rootTabs } ?: Destination.HOME
private val TravelerRoute.retainsSpotQuery get() = destination == Destination.EXPLORER ||
    destination == Destination.CATALOG && extra == CatalogKind.SPOT.name

private fun signedInStack(stack: List<TravelerRoute>) = stack.dropLastWhile { it.destination.isAuthenticationScreen() }
    .ifEmpty { listOf(TravelerRoute(Destination.HOME)) }
private fun authenticationStack(stack: List<TravelerRoute>): List<TravelerRoute> {
    val children = stack.takeLastWhile { it.destination.isAuthenticationScreen() }
    return if (children.firstOrNull()?.destination == Destination.LOGIN) children else listOf(TravelerRoute(Destination.LOGIN)) + children
}

private fun CatalogResult<ItineraryCoverStatus>.coverProcessing(): CatalogResult<CoverProcessing> = when (this) {
    is CatalogResult.Ready -> CatalogResult.Ready(value.processing)
    else -> failure()
}
private fun CatalogResult<TripBannerStatus>.bannerProcessing(): CatalogResult<CoverProcessing> = when (this) {
    is CatalogResult.Ready -> CatalogResult.Ready(value.processing)
    else -> failure()
}

private fun <T> CatalogResult<T>.failure(): CatalogResult<Nothing> = when (this) {
    is CatalogResult.HttpError -> this
    is CatalogResult.Maintenance -> this
    CatalogResult.InvalidResponse -> CatalogResult.InvalidResponse
    CatalogResult.NetworkError -> CatalogResult.NetworkError
    is CatalogResult.Ready -> error("Expected failure")
}
private fun <T> RegistrationResult<T>.catalog(): CatalogResult<T> = when (this) {
    is RegistrationResult.Ready -> CatalogResult.Ready(value)
    is RegistrationResult.Failed -> CatalogResult.HttpError(status, retryAfterSeconds, code)
}
private fun <T> ItineraryWriteResult<T>.catalog(): CatalogResult<T> = when (this) {
    is ItineraryWriteResult.Completed -> CatalogResult.Ready(value)
    is ItineraryWriteResult.Failed -> cause
    ItineraryWriteResult.PendingDifferentAction -> CatalogResult.HttpError(409, code = TravelerItineraryRepository.PENDING_MUTATION_CODE)
    ItineraryWriteResult.Expired -> CatalogResult.HttpError(409, code = TravelerItineraryRepository.EXPIRED_MUTATION_CODE)
    ItineraryWriteResult.StorageFailure -> CatalogResult.InvalidResponse
}
