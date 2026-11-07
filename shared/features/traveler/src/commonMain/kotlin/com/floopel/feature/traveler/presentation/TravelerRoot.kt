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
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.TravelerImage
import com.floopel.feature.traveler.presentation.account.*
import com.floopel.feature.traveler.presentation.catalog.*
import com.floopel.feature.traveler.presentation.home.TravelerHomeContent
import com.floopel.feature.traveler.presentation.itinerary.*
import com.floopel.feature.traveler.presentation.quest.*
import com.floopel.feature.traveler.presentation.trip.*
import com.floopel.feature.traveler.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TravelerRoot(runtime: TravelerRuntime, share: (String) -> Unit,
    googleSignIn: (suspend () -> String?)?,
    photoPicker: (suspend () -> TravelerImage?)?,
    backHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    remember(runtime) {
        SingletonImageLoader.setSafe { context -> ImageLoader.Builder(context).components { add(SvgDecoder.Factory()) }.build() }
    }
    var savedStack by rememberSaveable { mutableStateOf("") }
    val model = viewModel { TravelerViewModel(runtime, savedStack) }
    val platformScope = rememberCoroutineScope()
    var googleBusy by remember { mutableStateOf(false) }
    val state by model.state.collectAsStateWithLifecycle()
    if (!state.identityRestored) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
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
            if (route.destination !in setOf(Destination.HOME, Destination.COMMUNITY, Destination.OPEN_TRIPS, Destination.LOGIN))
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp)) {
                if (state.stack.size > 1) IconButton(model::back, enabled = !state.busy) { Icon(FloopelIcons.ArrowBack, s(Res.string.back)) }
                Text(s(route.title()), Modifier.weight(1f).padding(vertical = 14.dp, horizontal = 8.dp), style = MaterialTheme.typography.titleLarge)
                if (route.destination == Destination.DETAIL && route.extra == "SPOT" && state.identity.signedIn) {
                    val saved = state.isSpotSaved.value
                    IconToggleButton(saved == true, { model.setSaved() }, enabled = saved != null && !state.isSpotSaved.loading && !state.busy) {
                        Icon(if (saved == true) FloopelIcons.Bookmark else FloopelIcons.BookmarkBorder,
                            s(if (saved == true) Res.string.unsave_spot else Res.string.save_spot))
                    }
                }
            }
            if (state.busy || state.identity.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.writeFailure?.let { ErrorPanel(it, model::refresh) }
            if (state.unknownOutcome) InfoCard(s(Res.string.unknown_outcome))
            state.identity.failure?.let { failure -> ErrorPanel(ReadFailure(failure.status, failure.code, state.identity.retryAtMillis), model::refresh) }
            Box(Modifier.weight(1f).fillMaxWidth().testTag("traveler-viewport")) {
                if (!state.identity.signedIn && !route.destination.isAuthenticationScreen()) LoginGate(model)
                else when (route.destination) {
                    Destination.HOME -> TravelerHomeContent(state, model)
                    Destination.COMMUNITY -> CommunityScreen(state, model, share)
                    Destination.EXPLORER, Destination.CATALOG, Destination.ADD_SPOT -> CatalogScreen(state, model)
                    Destination.DETAIL -> CatalogDetail(state, model)
                    Destination.OPEN_TRIPS -> OpenTripCatalog(state, model)
                    Destination.APPLY -> OpenTripDetail(state, model)
                    Destination.PROFILE -> ProfileScreen(state, model)
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
                    Destination.LEGAL -> LegalScreen(state, model)
                    Destination.QUESTS -> QuestList(state, model)
                    Destination.QUEST -> QuestDetail(state, model)
                    Destination.QUEST_PROGRESS -> QuestProgress(state, model)
                    Destination.QUEST_ACTIVITY -> QuestActivity(state, model)
                    Destination.PASSPORT -> PassportScreen(state, model)
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
                    Destination.NOTIFICATIONS -> NotificationsScreen(state, model)
                    Destination.PUBLIC_PLAN -> PublicPlanScreen(state, model)
                    Destination.PUBLIC_PROFILE -> PublicProfileScreen(state, model)
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
            if (state.identity.signedIn && state.stack.size == 1 && !route.destination.isAuthenticationScreen()) NavigationBar(
                modifier = Modifier.testTag("traveler-navigation"), containerColor = MaterialTheme.colorScheme.surface, windowInsets = WindowInsets(0)) {
                listOf(Triple(Destination.HOME, Res.string.home, FloopelIcons.Home),
                    Triple(Destination.COMMUNITY, Res.string.community, FloopelIcons.Groups),
                    Triple(Destination.EXPLORER, Res.string.explorer, FloopelIcons.Explore),
                    Triple(Destination.OPEN_TRIPS, Res.string.open_trip, FloopelIcons.Route),
                    Triple(Destination.PROFILE, Res.string.profile, FloopelIcons.PersonOutline)).forEach { (destination, title, icon) ->
                    NavigationBarItem(selected = state.tab == destination, onClick = { model.tab(destination) },
                        icon = { Icon(icon, null) }, label = { Text(s(title), style = MaterialTheme.typography.labelSmall, maxLines = 1) }, enabled = !state.busy,
                        colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
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
        "LOCATION" -> Res.string.explore_locations
        "COLLECTION" -> Res.string.collections
        "STORY" -> Res.string.stories
        else -> Res.string.explorer
    }
    Destination.OPEN_TRIPS -> Res.string.open_trip
    Destination.PROFILE -> Res.string.profile
    Destination.QUESTS -> Res.string.quests
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
