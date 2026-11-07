package com.floopel.feature.traveler.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import com.floopel.core.designsystem.component.FloopelButton
import com.floopel.core.designsystem.component.FloopelTopBar
import com.floopel.core.designsystem.component.FloopelBottomNavigation
import com.floopel.core.designsystem.component.FloopelBottomNavigationItem
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.FloopelCatalogTypography
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.TravelerImage
import com.floopel.feature.traveler.domain.model.CatalogKind
import com.floopel.feature.traveler.presentation.account.*
import com.floopel.feature.traveler.presentation.catalog.*
import com.floopel.feature.traveler.presentation.home.TravelerHomeContent
import com.floopel.feature.traveler.presentation.itinerary.*
import com.floopel.feature.traveler.presentation.quest.*
import com.floopel.feature.traveler.presentation.passport.*
import com.floopel.feature.traveler.presentation.trip.*
import com.floopel.feature.traveler.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TravelerRoot(runtime: TravelerRuntime, share: (String) -> Unit,
    googleSignIn: (suspend () -> String?)?,
    photoPicker: (suspend () -> TravelerImage?)?,
    startupSurface: (Boolean) -> Unit,
    language: String,
    selectLanguage: ((String) -> Unit)?,
    backHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    remember(runtime) {
        SingletonImageLoader.setSafe { context -> ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build() }
    }
    var savedStack by rememberSaveable { mutableStateOf("") }
    val model = viewModel { TravelerViewModel(runtime, savedStack) }
    LaunchedEffect(language) { model.changeLanguage(language) }
    var erasureOpen by rememberSaveable { mutableStateOf(false) }
    val erasure = runtime.erasure?.let { repository ->
        viewModel(key = "account-erasure") { ErasureViewModel(repository, runtime.identity, runtime.newOperationKey) }
    }
    val platformScope = rememberCoroutineScope()
    var googleBusy by remember { mutableStateOf(false) }
    val state by model.state.collectAsStateWithLifecycle()
    val brandedStartup = !state.identityRestored || state.intro == null
    LaunchedEffect(brandedStartup) { startupSurface(brandedStartup) }
    if (brandedStartup) {
        TravelerStartupSplash(state.startupFailure, model::retryStartup)
        return
    }
    if (state.showIntro) {
        backHandler(state.introSaving || (state.intro?.step ?: 0) > 0, model::previousIntro)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { TravelerIntroScreen(state, model) }
        return
    }
    var pickerBusy by remember { mutableStateOf(false) }
    val selectPhoto: TravelerImageSelection? = photoPicker?.let { picker -> { selected ->
        if (!pickerBusy && !state.busy) platformScope.launch {
            val requestedRoute = model.state.value.route
            val epoch = runtime.identity.generation
            pickerBusy = true
            try {
                val image = picker()
                if (image != null && model.state.value.route == requestedRoute && runtime.identity.generation == epoch) selected(image)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { model.imageUnavailable() }
            finally { pickerBusy = false }
        }
    } }
    LaunchedEffect(state.stack) { savedStack = model.savedStack() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.background() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    backHandler(state.canGoBack || state.busy, model::back)
    val route = state.route
    LaunchedEffect(state.identity.accountRecoveryRequired) {
        if (state.identity.accountRecoveryRequired) erasureOpen = true
    }
    if (erasure != null && (erasureOpen || state.identity.accountRecoveryRequired) && route.destination != Destination.FORGOT) {
        val closeErasure: () -> Unit = {
            erasure.close()
            erasureOpen = false
            if (state.identity.accountRecoveryRequired) model.logout()
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                AccountErasureScreen(erasure, state.identity.signedIn, state.identity.accountRecoveryRequired,
                    close = closeErasure, forgot = {
                        erasure.close(); erasureOpen = false; model.open(TravelerRoute(Destination.FORGOT))
                    }, backHandler = backHandler)
            }
        }
        return
    }
    val locationScreen = route.destination == Destination.COUNTRIES ||
        route.destination in setOf(Destination.CATALOG, Destination.DETAIL) && route.extra == "LOCATION"
    val spotArchive = route.destination == Destination.CATALOG && route.extra == CatalogKind.SPOT.name
    val spotDetail = route.destination == Destination.DETAIL && route.extra == CatalogKind.SPOT.name
    val collectionFailure = state.detail.failure.takeIf {
        route.destination == Destination.DETAIL && route.extra == CatalogKind.COLLECTION.name && state.detail.value == null
    }
    val targetReady = when (route.destination) {
        Destination.TRIP -> state.trip.value?.id == route.id && !state.trip.loading && state.trip.failure == null
        Destination.APPLICATION -> state.application.value?.id == route.id && !state.application.loading && state.application.failure == null
        Destination.NOTICE -> state.notice.value?.id == route.id && !state.notice.loading && state.notice.failure == null
        Destination.QUEST_GROUP -> state.group.value?.id == route.id && !state.group.loading && state.group.failure == null
        Destination.QUEST_GROUP_INVITATION -> state.groupInvitation.value?.id == route.id && !state.groupInvitation.loading && state.groupInvitation.failure == null
        else -> false
    }
    LaunchedEffect(route, targetReady) { if (targetReady) { withFrameNanos { }; model.targetVisible(route) } }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            if (!spotArchive && !spotDetail && (route.destination !in setOf(Destination.HOME, Destination.COMMUNITY, Destination.OPEN_TRIPS, Destination.LOGIN,
                Destination.NOTIFICATIONS, Destination.NOTIFICATION_DEVICE, Destination.NOTIFICATION_OPEN, Destination.NOTIFICATION_DETAIL,
                Destination.QUESTS, Destination.QUEST_FILTER, Destination.QUEST,
                Destination.PUBLIC_PROFILE, Destination.PROFILE, Destination.LEGAL, Destination.PASSPORT, Destination.PROFILE_SETTINGS, Destination.PROFILE_EDIT,
                Destination.PROFILE_INTERESTS, Destination.PROFILE_SECURITY, Destination.PROFILE_EMERGENCY, Destination.PROFILE_PREFERENCES) ||
                route.destination == Destination.OPEN_TRIPS && route.id.isNotBlank()))
            FloopelTopBar(s(when {
                    collectionFailure != null -> if (collectionFailure.status in setOf(403, 404)) Res.string.collection_unavailable_header else Res.string.collection_error_header
                    locationScreen && route.destination == Destination.CATALOG && state.query.isNotBlank() -> Res.string.location_search_title
                    else -> route.title()
                }), Modifier.testTag("traveler-top-bar"),
                titleStyle = if (locationScreen) FloopelCatalogTypography.toolbar else MaterialTheme.typography.titleLarge,
                minimumHeight = if (locationScreen) 48.dp else 56.dp,
                contentPadding = if (locationScreen) PaddingValues(horizontal = 8.dp) else PaddingValues(start = 12.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
                spacing = if (locationScreen) 8.dp else 12.dp,
                navigationIcon = if (state.stack.size > 1) { {
                    IconButton(model::back, Modifier.size(48.dp), enabled = !state.busy) { Icon(FloopelIcons.ArrowBack, s(Res.string.back)) }
                } } else null) {
                if (route.destination == Destination.DETAIL && route.extra == "SPOT" && state.identity.signedIn) {
                    val saved = state.isSpotSaved.value
                    IconToggleButton(saved == true, { model.setSaved() }, enabled = saved != null && !state.isSpotSaved.loading && !state.busy) {
                        Icon(if (saved == true) FloopelIcons.Bookmark else FloopelIcons.BookmarkBorder,
                            s(if (saved == true) Res.string.unsave_spot else Res.string.save_spot))
                    }
                }
            }
            if (route.destination != Destination.PASSPORT && (state.busy || state.identity.busy)) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.writeFailure?.let { ErrorPanel(it, model::refresh) }
            state.startupFailure?.let { ErrorPanel(it, model::retryStartup) }
            if (state.unknownOutcome) InfoCard(s(Res.string.unknown_outcome))
            state.identity.failure?.takeIf { route.destination != Destination.PASSPORT }?.let { failure -> ErrorPanel(ReadFailure(failure.status, failure.code, state.identity.retryAtMillis), model::refresh) }
            Box(Modifier.weight(1f).fillMaxWidth().testTag("traveler-viewport")) {
                if (!state.identity.signedIn && !route.destination.isAuthenticationScreen()) LoginGate(model)
                else when (route.destination) {
                    Destination.HOME -> TravelerHomeContent(state, model)
                    Destination.COMMUNITY -> CommunityScreen(state, model, share)
                    Destination.EXPLORER, Destination.ADD_SPOT -> CatalogScreen(state, model)
                    Destination.CATALOG -> if (route.extra == "LOCATION") LocationBrowser(state, model) else CatalogScreen(state, model)
                    Destination.COUNTRIES -> CountryPicker(state, model)
                    Destination.DETAIL -> when (route.extra) {
                        "LOCATION" -> LocationDetail(state, model)
                        "SPOT" -> SpotDetailScreen(state, model, share, runtime.comments != null)
                        "COLLECTION" -> CollectionScreen(state, model)
                        else -> CatalogDetail(state, model)
                    }
                    Destination.OPEN_TRIPS -> OpenTripCatalog(state, model)
                    Destination.APPLY -> OpenTripDetail(state, model)
                    Destination.PROFILE -> ProfileOverview(state, model)
                    Destination.PROFILE_SETTINGS, Destination.PROFILE_EDIT, Destination.PROFILE_INTERESTS, Destination.PROFILE_SECURITY,
                    Destination.PROFILE_EMERGENCY, Destination.PROFILE_PREFERENCES -> ProfileAccountScreen(state, model,
                        language, selectLanguage, erasure?.let { { erasureOpen = true } })
                    Destination.LOGIN -> LoginScreen(state, model, googleSignIn?.let { provider -> {
                        if (!googleBusy) platformScope.launch {
                            googleBusy = true
                            try { provider()?.let(model::loginGoogle) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { model.googleUnavailable() }
                            finally { googleBusy = false }
                        }
                    } }, googleBusy)
                    Destination.REGISTER -> RegistrationScreen(state, model)
                    Destination.VERIFY -> VerificationScreen(state, model)
                    Destination.FORGOT -> ForgotScreen(state, model)
                    Destination.LEGAL -> LegalReviewScreen(runtime, language, route.extra == "settings", model::back, model::logout, backHandler)
                    Destination.QUESTS -> QuestList(state, model)
                    Destination.QUEST_FILTER -> QuestFilterScreen(state, model)
                    Destination.QUEST -> QuestDetail(state, model, share)
                    Destination.QUEST_PROGRESS -> QuestProgress(state, model)
                    Destination.QUEST_ACTIVITY -> QuestActivity(state, model)
                    Destination.PASSPORT -> PassportScreen(state, model, share)
                    Destination.ITINERARIES, Destination.PICK_PLAN -> ItineraryList(state, model)
                    Destination.ITINERARY -> ItineraryScreen(state, model)
                    Destination.ITINERARY_FORM -> ItineraryForm(state, model, selectPhoto)
                    Destination.STOP_FORM -> StopForm(state, model)
                    Destination.SHARE -> ShareHub(state, model)
                    Destination.PUBLICATION -> PublicationScreen(state, model, selectPhoto)
                    Destination.SHARE_LINK -> ShareLinkScreen(state, model, share)
                    Destination.TRIP_FORM -> TripForm(state, model, selectPhoto)
                    Destination.TRIPS -> TripList(state, model)
                    Destination.TRIP -> TripDetail(state, model, selectPhoto)
                    Destination.MEMBERS -> Members(state, model)
                    Destination.INVITATIONS -> Invitations(state, model)
                    Destination.APPLICATIONS, Destination.TRIP_APPLICATIONS -> Applications(state, model)
                    Destination.APPLICATION -> ApplicationReader(state, model)
                    Destination.NOTICE -> TripNoticeReader(state, model)
                    Destination.NOTIFICATIONS, Destination.NOTIFICATION_DEVICE, Destination.NOTIFICATION_OPEN, Destination.NOTIFICATION_DETAIL -> NotificationScreen(state, model)
                    Destination.PUBLIC_PLAN -> PublicPlanScreen(state, model)
                    Destination.PUBLIC_PROFILE -> PublicProfileContent(state, model, share)
                    Destination.SAVED_SPOTS -> SavedSpotScreen(state, model)
                    Destination.QUEST_GROUPS -> QuestGroupList(state, model)
                    Destination.QUEST_GROUP -> QuestGroupReader(state, model)
                    Destination.QUEST_GROUP_INVITATIONS -> QuestGroupInvitations(state, model)
                    Destination.QUEST_GROUP_INVITATION -> QuestGroupInvitationReader(state, model)
                }
            }
            state.feedback?.let { feedback ->
                Surface(modifier = Modifier.testTag("spot-feedback"), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s(Res.string.spot_added, feedback.title, feedback.day), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FloopelButton(s(Res.string.open_plan), { model.open(TravelerRoute(Destination.ITINERARY, feedback.itineraryId, day = feedback.day)) })
                            TextButton(model::clearFeedback) { Text(s(Res.string.keep_exploring)) }
                        }
                    }
                }
            }
            if (state.identity.signedIn && state.stack.size == 1 && !route.destination.isAuthenticationScreen()) FloopelBottomNavigation(
                modifier = Modifier.testTag("traveler-navigation")) {
                listOf(Triple(Destination.HOME, Res.string.home, FloopelIcons.Home),
                    Triple(Destination.COMMUNITY, Res.string.community, FloopelIcons.Groups),
                    Triple(Destination.EXPLORER, Res.string.explorer, FloopelIcons.Explore),
                    Triple(Destination.OPEN_TRIPS, Res.string.open_trip, FloopelIcons.Route),
                    Triple(Destination.PROFILE, Res.string.profile, FloopelIcons.PersonOutline)).forEach { (destination, title, icon) ->
                    FloopelBottomNavigationItem(s(title), icon, state.tab == destination, { model.tab(destination) },
                        Modifier.testTag("navigation-${destination.name.lowercase()}"), enabled = !state.busy)
                }
            }
        }
    }
}

private fun TravelerRoute.title() = when (destination) {
    Destination.HOME -> Res.string.home
    Destination.COMMUNITY -> Res.string.community
    Destination.EXPLORER -> Res.string.explorer
    Destination.CATALOG -> when (extra) {
        "LOCATION" -> Res.string.location_all
        "COLLECTION" -> Res.string.collection_detail
        "STORY" -> Res.string.stories
        else -> Res.string.explorer
    }
    Destination.COUNTRIES -> Res.string.location_choose_country
    Destination.OPEN_TRIPS -> Res.string.open_trip
    Destination.PROFILE -> Res.string.profile
    Destination.PROFILE_SETTINGS -> Res.string.profile_settings
    Destination.PROFILE_EDIT -> Res.string.profile_edit
    Destination.PROFILE_INTERESTS -> Res.string.profile_interests
    Destination.PROFILE_SECURITY -> Res.string.profile_security
    Destination.PROFILE_EMERGENCY -> Res.string.profile_emergency
    Destination.PROFILE_PREFERENCES -> Res.string.profile_preferences
    Destination.QUESTS -> Res.string.quests
    Destination.QUEST_FILTER -> Res.string.quest_filter_title
    Destination.QUEST -> Res.string.quest_detail
    Destination.QUEST_PROGRESS -> Res.string.quest_progress
    Destination.QUEST_ACTIVITY -> Res.string.quest_activity
    Destination.PASSPORT -> Res.string.passport
    Destination.ITINERARIES -> Res.string.itinerary_yours
    Destination.PICK_PLAN -> Res.string.choose_plan
    Destination.ITINERARY -> Res.string.itinerary
    Destination.ITINERARY_FORM -> Res.string.new_itinerary
    Destination.ADD_SPOT -> Res.string.add_spot
    Destination.STOP_FORM -> Res.string.stop_settings
    Destination.SHARE -> Res.string.share_hub_title
    Destination.PUBLICATION -> Res.string.review_publication
    Destination.SHARE_LINK -> Res.string.private_link
    Destination.TRIP_FORM -> Res.string.create_trip
    Destination.TRIPS -> Res.string.trip_yours
    Destination.TRIP -> Res.string.trip
    Destination.MEMBERS -> Res.string.manage_members
    Destination.INVITATIONS -> Res.string.invitations
    Destination.TRIP_APPLICATIONS -> Res.string.manage_applications
    Destination.APPLICATIONS -> Res.string.applications_yours
    Destination.APPLICATION -> Res.string.trip_application
    Destination.APPLY -> if (extra == "form") Res.string.trip_apply else Res.string.open_trip
    Destination.NOTICE -> Res.string.trip_notice
    Destination.NOTIFICATIONS -> Res.string.notifications
    Destination.NOTIFICATION_DEVICE -> Res.string.notification_device
    Destination.NOTIFICATION_OPEN -> Res.string.notification_opening
    Destination.NOTIFICATION_DETAIL -> Res.string.notification_detail
    Destination.DETAIL -> when (extra) { "SPOT" -> Res.string.spot_detail; "LOCATION" -> Res.string.location_detail;
        "COLLECTION" -> Res.string.collection_detail; else -> Res.string.story_detail }
    Destination.LOGIN -> Res.string.sign_in
    Destination.REGISTER -> Res.string.sign_up
    Destination.VERIFY -> Res.string.verify_email
    Destination.FORGOT -> Res.string.forgot_password
    Destination.LEGAL -> Res.string.legal_title
    Destination.PUBLIC_PLAN -> Res.string.public_itinerary
    Destination.PUBLIC_PROFILE -> Res.string.public_profile
    Destination.SAVED_SPOTS -> Res.string.saved_spots
    Destination.QUEST_GROUPS, Destination.QUEST_GROUP -> Res.string.quest_groups
    Destination.QUEST_GROUP_INVITATIONS, Destination.QUEST_GROUP_INVITATION -> Res.string.quest_group_invitations
}
