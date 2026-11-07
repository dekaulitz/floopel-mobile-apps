package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerIntroProgress
import com.floopel.feature.traveler.presentation.catalog.LocationBrowserPage
import com.floopel.feature.traveler.presentation.catalog.LocationContentState
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
            it.id.length <= 200 && it.extra.length <= 200 && it.operationKey.length <= 128 && it.countryId.length <= 200
        } }?.map { route ->
            // A pre-key saved route must recover to the same identity on every restoration.
            if (route.destination == Destination.TRIP_FORM && route.operationKey.isBlank())
                route.copy(operationKey = "restored-trip-create:${route.id}") else route
        }
    private val mutable = MutableStateFlow(TravelerState(tab = restored?.firstOrNull()?.destination?.takeIf { it in rootTabs } ?: Destination.HOME,
        stack = restored ?: listOf(TravelerRoute(Destination.HOME)),
        identityRestored = !autoStart))
    val state = mutable.asStateFlow()
    private val reads = mutableMapOf<String, Job>()
    private val acknowledgements = mutableSetOf<Pair<Long, String>>()
    private var writeJob: Job? = null
    private var registrationKey: String? = null
    private var startupJob: Job? = null
    private val locationQueries = mutableMapOf<TravelerRoute, String>()

    init {
        if (autoStart) {
            viewModelScope.launch {
                runtime.identity.view.collect { identity ->
                    if (state.value.identity.generation != identity.generation || state.value.identity.signedIn != identity.signedIn) {
                        reads.values.forEach(Job::cancel); reads.clear()
                        writeJob?.cancel()
                        acknowledgements.clear()
                        locationQueries.clear()
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
                    current.copy(identity = identity, identityRestored = true, stack = stack, tab = rootTab(stack))
                }
                loadIntro()
            }
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
        val route = creationRoute(requested)
        if (route.destination in rootTabs && route.id.isEmpty() && route.extra.isEmpty()) { tab(route.destination); return }
        reads.values.forEach(Job::cancel); reads.clear()
        mutable.update { it.copy(stack = it.stack + route, writeFailure = null, saved = false,
            query = "", feedback = null, detail = ReadState(), quest = if (route.destination == Destination.QUEST) ReadState() else it.quest, itinerary = ReadState(),
            distribution = ReadState(), shareLink = ReadState(), trip = ReadState(), plan = ReadState(),
            openTrip = ReadState(), openTripStops = ReadState(), application = ReadState(), notice = ReadState(), tripTimezone = null,
            tripRecovery = ReadState(), media = ReadState(), mediaPartial = false,
            itineraryDraft = ReadState(), pendingStop = ReadState(), applicationDraft = ReadState(),
            pendingPublication = ReadState(), isSpotSaved = ReadState(), publicPlan = ReadState(), publicProfile = ReadState(),
            publicProgress = ReadState(), publicPlans = if (route.destination == Destination.PUBLIC_PROFILE) ReadState() else it.publicPlans,
            group = ReadState(), groupMembers = ReadState(), groupInvitation = ReadState(), groupInvitations = ReadState(),
            locationBrowser = ReadState(), locationContent = LocationContentState()) }
        refresh()
    }
    fun tab(destination: Destination) {
        if (!state.value.identityRestored || state.value.intro == null || state.value.showIntro || !state.value.identity.signedIn) { requireLogin(); return }
        if (state.value.busy) return
        reads.values.forEach(Job::cancel); reads.clear()
        mutable.update { it.copy(tab = destination, stack = listOf(TravelerRoute(destination)), query = "",
            writeFailure = null, saved = false, feedback = null) }
        refresh()
    }
    fun back() {
        if (state.value.busy) return
        val current = state.value
        if (current.route.destination == Destination.REGISTER && current.registrationStep > 1) {
            mutable.update { it.copy(registrationStep = it.registrationStep - 1) }; return
        }
        val previous = current.stack.dropLast(1).lastOrNull()
        if (current.route.destination == Destination.ITINERARY && !(previous?.destination == Destination.DETAIL && previous.extra == CatalogKind.SPOT.name)) {
            mutable.update { it.copy(stack = listOf(TravelerRoute(it.tab), TravelerRoute(Destination.ITINERARIES)),
                writeFailure = null, saved = false, feedback = null) }
        } else if (current.stack.size > 1) mutable.update { it.copy(stack = it.stack.dropLast(1), writeFailure = null, saved = false, feedback = null) }
        else if (current.tab != Destination.HOME) tab(Destination.HOME)
        if (state.value.route.destination == Destination.COUNTRIES ||
            state.value.route.destination == Destination.CATALOG && state.value.route.extra == CatalogKind.LOCATION.name)
            mutable.update { it.copy(query = locationQueries[it.route].orEmpty()) }
        refresh()
    }
    fun background() { viewModelScope.launch(worker) { runtime.identity.background() } }
    fun clearFeedback() { mutable.update { it.copy(feedback = null, saved = false) } }

    fun refresh() {
        if (!state.value.identityRestored || state.value.intro == null || state.value.showIntro) return
        if (!state.value.identity.signedIn && !state.value.route.destination.isAuthenticationScreen()) { requireLogin(); return }
        val route = state.value.route
        when (route.destination) {
            Destination.HOME -> home()
            Destination.PROFILE -> fetch("profile", { it.profile }, { s, r -> s.copy(profile = r) }) { runtime.account.profile() }
            Destination.COMMUNITY -> when (state.value.communityTab) { 0 -> publicPlans(); 1 -> loadOpenTrips(); else -> catalog() }
            Destination.EXPLORER, Destination.ADD_SPOT -> catalog()
            Destination.CATALOG -> if (route.extra == CatalogKind.LOCATION.name) loadLocationBrowser() else catalog()
            Destination.COUNTRIES -> fetch("locationCountries", { it.locationCountries }, { s, r -> s.copy(locationCountries = r) }) { locationCountries() }
            Destination.DETAIL -> {
                fetch("detail", { it.detail }, { s, r -> s.copy(detail = r) }) {
                runtime.catalog.detail(CatalogEntry(route.id, route.slug.ifBlank { route.id }, "", null,
                    CatalogKind.entries.firstOrNull { it.name == route.extra } ?: CatalogKind.SPOT, null), "id") }
                if (route.extra == CatalogKind.SPOT.name && state.value.identity.signedIn)
                    fetch("isSaved", { it.isSpotSaved }, { s, r -> s.copy(isSpotSaved = r) }) { runtime.savedSpots.isSaved(route.id) }
                if (route.extra == CatalogKind.LOCATION.name) loadLocationContent(route.id)
            }
            Destination.QUESTS -> fetch("quests", { it.quests }, { s, r -> s.copy(quests = r) }) {
                if (route.id.isBlank()) runtime.catalog.activeQuests("id", 1) else runtime.catalog.untakenLocationQuests(route.id, "id", 1)
            }
            Destination.QUEST -> fetch("quest", { it.quest }, { s, r -> s.copy(quest = r) }) { runtime.identity.quest(route.id) }
            Destination.PASSPORT -> viewModelScope.launch(worker) { runtime.identity.passport() }
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
                        null, CatalogKind.SPOT, null), "id") }
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
                fetch("notifications", { it.notifications }, { s, r -> s.copy(notifications = r) }) { runtime.notifications.notifications(1, state.value.notificationClass, "id") }
                fetch("unread", { it.unread }, { s, r -> s.copy(unread = r) }) { runtime.notifications.unreadNotifications() }
            }
            Destination.PUBLIC_PLAN -> fetch("publicPlan", { it.publicPlan }, { s, r -> s.copy(publicPlan = r) }) { runtime.publicContent.itinerary(route.id) }
            Destination.PUBLIC_PROFILE -> {
                fetch("publicProfile", { it.publicProfile }, { s, r -> s.copy(publicProfile = r) }) { runtime.publicContent.profile(route.id) }
                fetch("publicProgress", { it.publicProgress }, { s, r -> s.copy(publicProgress = r) }) { runtime.publicContent.progress(route.id) }
                fetch("publicPlans", { it.publicPlans }, { s, r -> s.copy(publicPlans = r) }) { runtime.publicContent.profileItineraries(route.id) }
            }
            Destination.SAVED_SPOTS -> loadSaved()
            Destination.QUEST_GROUPS -> loadGroups()
            Destination.QUEST_GROUP -> {
                fetch("group", { it.group }, { s, r -> s.copy(group = r) }) { runtime.questGroups.group(route.id, "id") }
                fetch("groupMembers", { it.groupMembers }, { s, r -> s.copy(groupMembers = r) }) { runtime.questGroups.members(route.id, 1) }
                fetch("profile", { it.profile }, { s, r -> s.copy(profile = r) }) { runtime.account.profile() }
            }
            Destination.QUEST_GROUP_INVITATIONS -> fetch("groupInvitations", { it.groupInvitations }, { s, r -> s.copy(groupInvitations = r) }) {
                runtime.questGroups.invitations(1, "id", route.id.takeIf(String::isNotBlank)) }
            Destination.QUEST_GROUP_INVITATION -> {
                fetch("groupInvitation", { it.groupInvitation }, { s, r -> s.copy(groupInvitation = r) }) { runtime.questGroups.invitation(route.id, "id") }
                fetch("profile", { it.profile }, { s, r -> s.copy(profile = r) }) { runtime.account.profile() }
            }
            Destination.REGISTER -> fetch("references", { it.references }, { s, r -> s.copy(references = r) }) {
                when (val r = runtime.registration.references("id")) {
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
                        null, CatalogKind.SPOT, null), "id")
                    if (result is CatalogResult.Ready && state.value.route == route && runtime.identity.generation == epoch)
                        mutable.update { it.copy(stopCards = it.stopCards + (stop.spotId to result.value)) }
                } }.awaitAll() }
            }
        }
    }
    private fun home() {
        fetch("homeSpots", { it.home.spots }, { s, r -> s.copy(home = s.home.copy(spots = r)) }) { runtime.catalog.discoverySpots("id") }
        fetch("homeLocations", { it.home.locations }, { s, r -> s.copy(home = s.home.copy(locations = r)) }) { runtime.catalog.topLocations("id") }
        fetch("homeCollections", { it.home.collections }, { s, r -> s.copy(home = s.home.copy(collections = r)) }) { runtime.catalog.collections("id", 8) }
        fetch("homeStories", { it.home.stories }, { s, r -> s.copy(home = s.home.copy(stories = r)) }) { runtime.catalog.stories("id", perPage = 8) }
        fetch("homeQuests", { it.home.quests }, { s, r -> s.copy(home = s.home.copy(quests = r)) }) { runtime.catalog.activeQuests("id", 1) }
        if (state.value.identity.signedIn) {
            fetch("profile", { it.profile }, { s, r -> s.copy(profile = r) }) { runtime.account.profile() }
            fetch("trips", { it.trips }, { s, r -> s.copy(trips = r) }) { runtime.trips.mine() }
            fetch("unread", { it.unread }, { s, r -> s.copy(unread = r) }) { runtime.notifications.unreadNotifications() }
        }
    }
    fun query(value: String) {
        mutable.update { it.copy(query = value) }
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
            if (kind == CatalogKind.ITINERARY) runtime.catalog.curatedItineraries(query, null, "id", page)
            else if (query.isNotBlank() && kind == CatalogKind.SPOT && route.destination != Destination.EXPLORER)
                runtime.catalog.searchSpotPage(query, locationId, "id", page)
            else if (query.isNotBlank()) when (val r = runtime.catalog.search(query, "id")) {
                is CatalogResult.Ready -> CatalogResult.Ready(CatalogPage(r.value, 1, 1))
                else -> r.failure()
            } else runtime.catalog.browse(kind, "id", page, storyType = if (kind == CatalogKind.STORY && locationId != null) StoryType.ARTICLE else null, locationId = locationId)
        }
    }

    private suspend fun locationCountries(): CatalogResult<List<CatalogEntry>> {
        val countries = mutableListOf<CatalogEntry>()
        var page = 1
        while (true) {
            val result = runtime.catalog.countryPage("id", page)
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
                val result = runtime.catalog.detail(CatalogEntry(route.id, route.id, "", null, CatalogKind.LOCATION, null), "id")
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
                query.isNotBlank() -> when (val search = runtime.catalog.searchLocations(query, "id")) {
                    is CatalogResult.Ready -> CatalogResult.Ready(CatalogPage(search.value, 1, 1))
                    else -> search.failure()
                }
                country == null -> CatalogResult.Ready(CatalogPage(emptyList(), 1, 0))
                else -> runtime.catalog.browse(CatalogKind.LOCATION, "id", page, parentId = region?.id ?: country.id)
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
            runtime.catalog.browse(CatalogKind.SPOT, "id", 1, locationId = id)
        }
        fetch("locationQuests", { it.locationContent.quests }, { s, r -> s.copy(locationContent = s.locationContent.copy(quests = r)) }) {
            runtime.catalog.untakenLocationQuests(id, "id", 1)
        }
        fetch("locationTrips", { it.locationContent.trips }, { s, r -> s.copy(locationContent = s.locationContent.copy(trips = r)) }) {
            runtime.trips.openTrips(OpenTripQuery(locationId = id, limit = 8))
        }
        fetch("locationArticles", { it.locationContent.articles }, { s, r -> s.copy(locationContent = s.locationContent.copy(articles = r)) }) {
            runtime.catalog.browse(CatalogKind.STORY, "id", 1, storyType = StoryType.ARTICLE, locationId = id)
        }
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
        write({ runtime.itineraries.clone(id).catalog() }) { replace(TravelerRoute(Destination.ITINERARY, it.itinerary.id)) }
    }
    private fun loadGroups(page: Int = 1) = fetch("groups", { it.groups }, { s, r -> s.copy(groups =
        if (page > 1 && r.value != null) r.copy(value = r.value.copy(items = (s.groups.value?.items.orEmpty() + r.value.items).distinctBy { it.id })) else r) }) {
        runtime.questGroups.groups(page, "id", state.value.route.id.takeIf(String::isNotBlank)) }
    fun createGroup() {
        val questId = state.value.route.id
        if (questId.isBlank()) return
        write({
            when (val current = runtime.questGroups.groups(1, "id", questId)) {
                is CatalogResult.Ready -> current.value.items.firstOrNull { it.questId == questId }?.let { CatalogResult.Ready(it) }
                    ?: runtime.questGroups.createGroup(questId, "id", runtime.newOperationKey())
                else -> current.failure()
            }
        }) { replace(TravelerRoute(Destination.QUEST_GROUP, it.id)) }
    }
    fun inviteToGroup(username: String) = write({ runtime.questGroups.invite(state.value.route.id, username, "id", runtime.newOperationKey()) }) {
        mutable.update { it.copy(saved = true) }
    }
    fun decideGroupInvitation(accept: Boolean) = write({ runtime.questGroups.decideInvitation(state.value.route.id, accept, "id", runtime.newOperationKey()) }) {
        if (accept) replace(TravelerRoute(Destination.QUEST_GROUP, it.groupId)) else back()
    }
    fun leaveGroup(disband: Boolean) = write({ if (disband) runtime.questGroups.disbandGroup(state.value.route.id, runtime.newOperationKey())
        else runtime.questGroups.leaveGroup(state.value.route.id, runtime.newOperationKey()) }) { back() }
    fun transferGroup(profileId: String) = write({ runtime.questGroups.transferManager(state.value.route.id, profileId, "id", runtime.newOperationKey()) }) { refresh() }
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
            Destination.QUESTS -> s.quests.value?.takeIf { !s.quests.loading && it.page < it.totalPages }?.let { previous ->
                fetch("quests", { it.quests }, { old, r -> old.copy(quests = if (r.value != null)
                    r.copy(value = r.value.copy(items = (previous.items + r.value.items).distinctBy { it.id })) else r) }) {
                    if (s.route.id.isBlank()) runtime.catalog.activeQuests("id", previous.page + 1)
                    else runtime.catalog.untakenLocationQuests(s.route.id, "id", previous.page + 1)
                } }
            Destination.NOTIFICATIONS -> s.notifications.value?.takeIf { !s.notifications.loading && it.page < it.totalPages }?.let { previous ->
                fetch("notifications", { it.notifications }, { old, r -> old.copy(notifications = if (r.value != null)
                    r.copy(value = r.value.copy(items = (previous.items + r.value.items).distinctBy { it.id })) else r) }) { runtime.notifications.notifications(previous.page + 1, state.value.notificationClass, "id") } }
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
    fun logout() = auth { runtime.identity.logout() }
    fun takeQuest() = progressAction { runtime.identity.takeQuest() }
    fun continueQuest() = progressAction { runtime.identity.continueQuest() }
    fun continueQuest(id: String) = progressAction { runtime.identity.continueQuest(id) }
    private fun progressAction(action: suspend () -> Unit) {
        if (state.value.identity.busy) return
        viewModelScope.launch {
            withContext(worker) { action() }
            val current = runtime.identity.view.first()
            mutable.update { it.copy(identity = current) }
            if (current.failure == null && current.running != null) open(TravelerRoute(Destination.QUEST_PROGRESS, current.running.id))
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
        write({ if (route.id.isBlank()) runtime.itineraries.create(draft).catalog() else runtime.itineraries.update(route.id, draft) }) { result ->
            replace(TravelerRoute(Destination.ITINERARY, result.itinerary.id))
        }
    }
    fun addSpot(itineraryId: String, spotId: String, day: Int) = write({ runtime.itineraries.addStop(itineraryId, spotId, day).catalog() }) { result ->
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
        }) { (trip, banner) ->
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
    fun apply(message: String) = write({ runtime.trips.applyToOpenTrip(state.value.route.id, message, runtime.newOperationKey()) }) { application ->
        replace(TravelerRoute(Destination.APPLICATION, application.id))
    }
    fun decision(decision: TripApplicationDecision) = write({ runtime.trips.decideTripApplication(state.value.route.id, decision, runtime.newOperationKey()) }) { result ->
        mutable.update { it.copy(application = ReadState(result), saved = true) }
    }
    fun lifecycle(action: TripLifecycleAction) = write({ runtime.trips.lifecycle(state.value.route.id, action, runtime.newOperationKey()) }) { back() }
    fun invite(recipient: String) = write({ runtime.trips.invite(state.value.route.id, recipient, runtime.newOperationKey()) }) { refresh() }
    fun invitationDecision(id: String, decision: InvitationDecision) = write({ runtime.trips.decide(id, decision, runtime.newOperationKey()) }) { refresh() }
    fun removeMember(id: String) = write({ runtime.trips.removeMember(state.value.route.id, id, runtime.newOperationKey()) }) { refresh() }

    fun openNotification(id: String) {
        viewModelScope.launch {
            val result = withContext(worker) { runtime.notifications.notification(id, "id") }
            if (result is CatalogResult.Ready) {
                val target = notificationRoute(result.value.targetUrl)
                if (target != null) open(target.copy(inboxId = id))
                else mutable.update { it.copy(writeFailure = ReadFailure(404, "TARGET_UNAVAILABLE")) }
            } else mutable.update { it.copy(writeFailure = ReadState<Unit>().result(result.failure(), now()).failure) }
        }
    }
    /** Called after the authorized target has rendered a frame, never by list fetch or target GET alone. */
    fun targetVisible(route: TravelerRoute) {
        val s = state.value
        val inbox = route.inboxId ?: return
        if (route != s.route || !s.identity.signedIn) return
        val visible = when (route.destination) {
            Destination.TRIP -> s.trip.value?.id == route.id && s.trip.failure == null && !s.trip.loading
            Destination.APPLICATION -> s.application.value?.id == route.id && s.application.failure == null && !s.application.loading
            Destination.NOTICE -> s.notice.value?.id == route.id && s.notice.failure == null && !s.notice.loading
            Destination.QUEST_GROUP -> s.group.value?.id == route.id && s.group.failure == null && !s.group.loading
            Destination.QUEST_GROUP_INVITATION -> s.groupInvitation.value?.id == route.id && s.groupInvitation.failure == null && !s.groupInvitation.loading
            else -> false
        }
        val identity = runtime.identity.generation
        if (!visible || !acknowledgements.add(identity to inbox)) return
        viewModelScope.launch {
            val result = withContext(worker) { runtime.notifications.markNotificationRead(inbox) }
            if (result !is CatalogResult.Ready) acknowledgements.remove(identity to inbox)
        }
    }

    fun acceptLegal() {
        val documents = state.value.legal.value ?: return
        val epoch = runtime.identity.generation
        write(checkConsent = false, call = {
            var failure: CatalogResult<Unit> = CatalogResult.Ready(Unit)
            for (document in documents) {
                failure = runtime.legal.accept(document, runtime.newOperationKey(), epoch)
                if (failure !is CatalogResult.Ready) break
            }
            failure
        }) { back() }
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
        update: (TravelerState, ReadState<T>) -> TravelerState, ready: (T) -> Unit = {}, call: suspend () -> CatalogResult<T>) {
        val initial = current(state.value)
        if ((initial.failure?.retryAtMillis ?: 0) > now()) return
        reads.remove(key)?.cancel()
        val route = state.value.route; val generation = runtime.identity.generation
        mutable.update { update(it, initial.copy(loading = true, failure = null)) }
        reads[key] = viewModelScope.launch {
            val result = try { withContext(worker) { call() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { CatalogResult.NetworkError }
            ensureActive()
            if (state.value.route == route && runtime.identity.generation == generation) {
                mutable.update { update(it, current(it).result(result, now())) }
                if (result is CatalogResult.Ready) ready(result.value)
            }
        }
    }
    private fun <T> write(call: suspend () -> CatalogResult<T>, checkConsent: Boolean = true, success: suspend (T) -> Unit) {
        if (checkConsent && !state.value.identity.signedIn) { requireLogin(); return }
        if (state.value.busy || writeJob?.isActive == true || (state.value.writeFailure?.retryAtMillis ?: 0) > now()) return
        val epoch = runtime.identity.generation
        mutable.update { it.copy(busy = true, writeFailure = null, saved = false) }
        writeJob = viewModelScope.launch {
            try {
                if (checkConsent) {
                    val pending = withContext(worker) { runtime.legal.pending("id") }
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
                if (result is CatalogResult.Ready) { mutable.update { it.copy(unknownOutcome = false) }; success(result.value) }
                else {
                    mutable.update { it.copy(writeFailure = ReadState<T>().result(result, now()).failure,
                        unknownOutcome = result is CatalogResult.NetworkError || result is CatalogResult.InvalidResponse || result is CatalogResult.Maintenance || result is CatalogResult.HttpError && result.status >= 500) }
                    if (state.value.unknownOutcome) refresh() // Read recovery only. Never replay a write.
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(writeFailure = ReadFailure(), unknownOutcome = true) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}

private val rootTabs = setOf(Destination.HOME, Destination.COMMUNITY, Destination.EXPLORER, Destination.OPEN_TRIPS, Destination.PROFILE)

internal fun Destination.isAuthenticationScreen() = this in setOf(Destination.LOGIN, Destination.REGISTER, Destination.VERIFY, Destination.FORGOT)

private fun rootTab(stack: List<TravelerRoute>) = stack.first().destination.takeIf { it in rootTabs } ?: Destination.HOME
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
    ItineraryWriteResult.PendingDifferentAction -> CatalogResult.HttpError(409, code = "PENDING_OPERATION")
    ItineraryWriteResult.Expired -> CatalogResult.HttpError(409, code = "EXPIRED_OPERATION")
    ItineraryWriteResult.StorageFailure -> CatalogResult.InvalidResponse
}
