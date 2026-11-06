package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
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
        .getOrNull()?.takeIf { routes -> routes.isNotEmpty() && routes.size <= 40 && routes.all { it.id.length <= 200 && it.extra.length <= 200 } }
    private val mutable = MutableStateFlow(TravelerState(tab = restored?.firstOrNull()?.destination?.takeIf { it in rootTabs } ?: Destination.HOME,
        stack = restored ?: listOf(TravelerRoute(Destination.HOME))))
    val state = mutable.asStateFlow()
    private val reads = mutableMapOf<String, Job>()
    private val acknowledgements = mutableSetOf<Pair<Long, String>>()
    private var writeJob: Job? = null
    private var registrationKey: String? = null

    init {
        if (autoStart) {
            viewModelScope.launch {
                runtime.identity.view.collect { identity ->
                    mutable.update { current ->
                        if (current.identity.generation != identity.generation) {
                            val routeStack = if (current.identity.signedIn && !identity.signedIn)
                                listOf(TravelerRoute(Destination.HOME)) else current.stack
                            TravelerState(tab = current.tab, stack = routeStack, identity = identity, home = current.home)
                        } else current.copy(identity = identity)
                    }
                }
            }
            viewModelScope.launch {
                withContext(worker) { runtime.identity.restore() }
                if (runtime.identity.view.first().signedIn) mutable.update { current -> current.copy(stack = current.stack
                    .dropLastWhile { it.destination in setOf(Destination.LOGIN, Destination.VERIFY) }.ifEmpty { listOf(TravelerRoute(Destination.HOME)) }) }
                refresh()
            }
        }
    }

    fun savedStack(): String = Json.encodeToString(state.value.stack)
    fun openEntry(entry: CatalogEntry) = open(TravelerRoute(Destination.DETAIL, entry.id, entry.kind.name, slug = entry.slug))
    fun open(route: TravelerRoute) {
        if (state.value.busy || state.value.route == route) return
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
            group = ReadState(), groupMembers = ReadState(), groupInvitation = ReadState(), groupInvitations = ReadState()) }
        refresh()
    }
    fun tab(destination: Destination) {
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
        if (current.route.destination == Destination.ITINERARY) {
            mutable.update { it.copy(stack = listOf(TravelerRoute(it.tab), TravelerRoute(Destination.ITINERARIES)),
                writeFailure = null, saved = false, feedback = null) }
        } else if (current.stack.size > 1) mutable.update { it.copy(stack = it.stack.dropLast(1), writeFailure = null, saved = false, feedback = null) }
        else if (current.tab != Destination.HOME) tab(Destination.HOME)
        refresh()
    }
    fun background() { viewModelScope.launch(worker) { runtime.identity.background() } }
    fun clearFeedback() { mutable.update { it.copy(feedback = null, saved = false) } }

    fun refresh() {
        val route = state.value.route
        when (route.destination) {
            Destination.HOME -> home()
            Destination.PROFILE -> fetch("profile", { it.profile }, { s, r -> s.copy(profile = r) }) { runtime.account.profile() }
            Destination.COMMUNITY -> when (state.value.communityTab) { 0 -> publicPlans(); 1 -> loadOpenTrips(); else -> catalog() }
            Destination.EXPLORER, Destination.CATALOG, Destination.ADD_SPOT -> catalog()
            Destination.DETAIL -> {
                fetch("detail", { it.detail }, { s, r -> s.copy(detail = r) }) {
                runtime.catalog.detail(CatalogEntry(route.id, route.slug.ifBlank { route.id }, "", null,
                    CatalogKind.entries.firstOrNull { it.name == route.extra } ?: CatalogKind.SPOT, null), "id") }
                if (route.extra == CatalogKind.SPOT.name && state.value.identity.signedIn)
                    fetch("isSaved", { it.isSpotSaved }, { s, r -> s.copy(isSpotSaved = r) }) { runtime.savedSpots.isSaved(route.id) }
            }
            Destination.QUESTS -> fetch("quests", { it.quests }, { s, r -> s.copy(quests = r) }) { runtime.catalog.activeQuests("id", 1) }
            Destination.QUEST -> fetch("quest", { it.quest }, { s, r -> s.copy(quest = r) }) { runtime.identity.quest(route.id) }
            Destination.PASSPORT -> viewModelScope.launch(worker) { runtime.identity.passport() }
            Destination.QUEST_PROGRESS -> if (state.value.identity.running?.id != route.id)
                viewModelScope.launch(worker) { runtime.identity.continueQuest(route.id) }
            Destination.QUEST_ACTIVITY -> if (state.value.identity.question?.id != route.id && route.extra.isNotBlank())
                viewModelScope.launch(worker) { runtime.identity.continueQuest(route.extra); runtime.identity.activity(route.id) }
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
                    else when (val trip = runtime.trips.pendingCreatedTrip(route.id)) {
                        is CatalogResult.Ready -> CatalogResult.Ready(TripCreationRecovery(draft.value, trip.value))
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
        reads.remove("searchDelay")?.cancel()
        reads["searchDelay"] = viewModelScope.launch { delay(350)
            when {
                state.value.route.destination == Destination.OPEN_TRIPS || state.value.route.destination == Destination.COMMUNITY && state.value.communityTab == 1 -> loadOpenTrips()
                state.value.route.destination == Destination.COMMUNITY && state.value.communityTab == 0 -> publicPlans()
                else -> catalog()
            }
        }
    }
    private fun catalog(page: Int = 1) {
        val route = state.value.route; val query = state.value.query
        val kind = if (route.destination == Destination.COMMUNITY) CatalogKind.ITINERARY
            else CatalogKind.entries.firstOrNull { it.name == route.extra } ?: CatalogKind.SPOT
        fetch("catalog", { if (page == 1) ReadState() else it.catalog }, { s, r ->
            s.copy(catalog = if (page > 1 && r.value != null && s.catalog.value != null)
                r.copy(value = r.value.copy(items = (s.catalog.value.items + r.value.items).distinctBy { it.id })) else r) }) {
            if (kind == CatalogKind.ITINERARY) runtime.catalog.curatedItineraries(query, null, "id", page)
            else if (query.isNotBlank() && kind == CatalogKind.SPOT && route.destination != Destination.EXPLORER)
                runtime.catalog.searchSpotPage(query, null, "id", page)
            else if (query.isNotBlank()) when (val r = runtime.catalog.search(query, "id")) {
                is CatalogResult.Ready -> CatalogResult.Ready(CatalogPage(r.value, 1, 1))
                else -> r.failure()
            } else runtime.catalog.browse(kind, "id", page)
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
        if (!state.value.identity.signedIn) { open(TravelerRoute(Destination.LOGIN)); return }
        if (!plan.allowClone) return
        write({ runtime.itineraries.clone(plan.id).catalog() }) { replace(TravelerRoute(Destination.ITINERARY, it.itinerary.id)) }
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
        fetch("openTrips", { if (cursor == null) ReadState() else it.openTrips }, { s, r ->
            s.copy(openTrips = if (cursor != null && r.value != null && s.openTrips.value != null)
                r.copy(value = r.value.copy(items = (s.openTrips.value.items + r.value.items).distinctBy { it.id })) else r) }) {
            runtime.trips.openTrips(OpenTripQuery(query = query, cursor = cursor)) }
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
                    r.copy(value = r.value.copy(items = (previous.items + r.value.items).distinctBy { it.id })) else r) }) { runtime.catalog.activeQuests("id", previous.page + 1) } }
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
                    mutable.update { current -> current.copy(registrationStep = 1,
                        stack = current.stack.dropLastWhile { it.destination in setOf(Destination.LOGIN, Destination.REGISTER, Destination.VERIFY) }
                            .ifEmpty { listOf(TravelerRoute(Destination.HOME)) }) }
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
        identityAction { runtime.identity.activity(id) }
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
    fun removeStop(stopId: String) = write({ runtime.itineraries.removeStop(state.value.route.id, stopId) }) { refresh() }

    fun publish(allowClone: Boolean, notes: Boolean, pii: Boolean) {
        val id = state.value.route.id
        val revision = state.value.distribution.value?.updatedAt ?: return
        val review = state.value.pendingPublication.value ?: ItineraryPublicationReview(revision, allowClone, notes, pii)
        val key = runtime.newOperationKey()
        write({
            val fresh = runtime.itineraries.detail(id)
            if (fresh !is CatalogResult.Ready) fresh.failure()
            else if (fresh.value.stops.isEmpty() || !fresh.value.stopsLoaded) CatalogResult.HttpError(422, code = "EMPTY_ITINERARY")
            else runtime.distribution.publish(id, review, key)
        }) { result -> mutable.update { it.copy(distribution = ReadState(result), pendingPublication = ReadState(), saved = true) } }
    }
    fun shareLink(create: Boolean) = write({
        val id = state.value.route.id
        if (create) runtime.distribution.rotateShareLink(id, runtime.newOperationKey()) else runtime.distribution.shareLink(id)
    }) { link -> mutable.update { it.copy(shareLink = ReadState(link), saved = true) } }
    fun unpublish() = write({ runtime.distribution.unpublish(state.value.route.id, runtime.newOperationKey()) }) { result ->
        mutable.update { it.copy(distribution = ReadState(result), saved = true) }
    }
    fun revokeLink() = write({ runtime.distribution.revokeShareLink(state.value.route.id, runtime.newOperationKey()) }) { refresh() }
    fun createTrip(draft: TripDraft, image: TravelerImage? = null) {
        val source = state.value.route.id
        write({
            val recovered = runtime.trips.pendingCreatedTrip(source)
            val result = when (recovered) {
                is CatalogResult.Ready -> recovered.value?.let { CatalogResult.Ready(it) }
                    ?: runtime.trips.create(source, draft, runtime.newOperationKey())
                else -> recovered.failure()
            }
            if (result !is CatalogResult.Ready) result.failure()
            else {
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
