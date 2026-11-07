package com.floopel.feature.traveler.presentation.trip

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.presentation.account.LoginGate
import com.floopel.feature.traveler.presentation.itinerary.FormLayout
import com.floopel.feature.traveler.presentation.itinerary.CheckRow
import com.floopel.feature.traveler.resources.*
import org.jetbrains.compose.resources.stringArrayResource

@Composable
internal fun OpenTripCatalog(state: TravelerState, model: TravelerViewModel) {
    val months = stringArrayResource(Res.array.calendar_months_short)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = FloopelSpace.large, end = FloopelSpace.large, top = FloopelSpace.medium, bottom = FloopelSpace.section),
        verticalArrangement = Arrangement.spacedBy(FloopelSpace.large)) {
        SectionTitle(s(Res.string.open_trip))
        Text(s(Res.string.trip_explore_body),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FloopelSearchField(state.query, model::query, s(Res.string.search_trip), Modifier.fillMaxWidth().testTag("catalog-search"))
        SectionTitle(s(Res.string.trip_explore))
        ReadContent(state.openTrips, model::refresh) { page ->
            if (page.items.isEmpty()) EmptyContent()
            page.items.forEach { trip ->
                val organizer = trip.organizer.displayName.takeIf(String::isNotBlank) ?: trip.organizer.username
                Column(Modifier.fillMaxWidth().padding(bottom = FloopelSpace.large), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Media(trip.banner?.url ?: trip.previewSpot?.spot?.coverUrl,
                        Modifier.fillMaxWidth().height(FloopelMediaSize.tripCover).clip(FloopelShape.tripCover))
                    Text(trip.name, style = MaterialTheme.typography.titleLarge)
                    if (trip.locations.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(FloopelIcons.NearMe, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Body(trip.locations.joinToString(" · ") { it.name }, Modifier.weight(1f))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(FloopelSpace.small)) {
                        FloopelAvatar(organizer.split(' ').filter(String::isNotBlank).take(2).joinToString("") { it.take(1) },
                            FloopelMediaSize.organizerAvatar,
                            image = trip.organizer.appearance?.avatarUrl?.let { url -> { Media(url, Modifier.fillMaxSize()) } })
                        Body(s(Res.string.trip_with_organizer, organizer), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(FloopelSpace.small)) {
                        Icon(FloopelIcons.Route, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(tripCatalogDate(trip.startsOn, trip.endsOn, months) + " · " +
                            s(Res.string.catalog_trip_capacity, trip.participantCount, trip.participantLimit, trip.organizerCount),
                            Modifier.weight(1f).testTag("trip-catalog-schedule"),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
                    }
                    FloopelCompactButton(s(Res.string.view_trip), { model.openTrip(trip.id) }, Modifier.testTag("trip-catalog-action"))
                }
            }
            if (page.nextCursor != null) FloopelButton(s(Res.string.more), model::more, Modifier.fillMaxWidth(), secondary = true, enabled = !state.openTrips.loading)
        }
    }
}

@Composable
internal fun OpenTripDetail(state: TravelerState, model: TravelerViewModel) {
    val trip = state.openTrip.value
    if (trip == null) { ReadContent(state.openTrip, model::refresh) {}; return }
    if (state.route.extra == "form") {
        if (!state.identity.signedIn) { LoginGate(model); return }
        val pending = state.applicationDraft.value
        var message by rememberSaveable(trip.id, pending) { mutableStateOf(pending.orEmpty()) }
        FormLayout(footer = {
            FloopelButton(s(Res.string.trip_apply), { model.apply(message) }, Modifier.fillMaxWidth(),
                enabled = trip.canApply && !state.busy && visibleCharacterCount(message) <= 500 && !state.applicationDraft.loading && state.applicationDraft.failure == null)
        }) {
            Text(trip.name, style = MaterialTheme.typography.headlineMedium)
            InfoCard(s(Res.string.trip_admission_body))
            if (pending != null) InfoCard(s(Res.string.trip_frozen_draft))
            state.applicationDraft.failure?.let { ErrorPanel(it, model::refresh) }
            FloopelFormTextField(message, { message = it }, Modifier.fillMaxWidth(), label = { Text(s(Res.string.trip_apply_message)) },
                minLines = 4, isError = visibleCharacterCount(message) > 500, enabled = !state.busy && pending == null)
            if (!trip.canApply) InfoCard(s(Res.string.trip_apply_unavailable))
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Media(trip.banner?.url, Modifier.fillMaxWidth().height(190.dp))
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(trip.name, style = MaterialTheme.typography.headlineMedium)
            Body(trip.organizer.displayName + " · " + s(Res.string.trip_organizer))
            InfoCard(s(Res.string.trip_admission_body))
            Body("${trip.startsOn} – ${trip.endsOn} · ${trip.timezone}")
            Body(s(Res.string.trip_capacity, trip.participantCount, trip.participantLimit, trip.organizerCount))
            Body(s(Res.string.trip_capacity_remaining, trip.remainingParticipants))
            trip.description?.let { Text(it) }
            trip.locations.forEach { location -> ActionRow(location.name, onClick = {
                model.open(TravelerRoute(Destination.DETAIL, location.id, CatalogKind.LOCATION.name))
            }) }
            SectionTitle(s(Res.string.trip_itinerary))
            ReadContent(state.openTripStops, model::refresh) { page ->
                page.items.forEach { stop -> CatalogCard(stop.spot, { model.openEntry(stop.spot) }) }
                if (page.nextCursor != null) FloopelButton(s(Res.string.more), model::more, secondary = true, enabled = !state.openTripStops.loading)
            }
            if (!state.identity.signedIn) FloopelButton(s(Res.string.sign_in), { model.open(TravelerRoute(Destination.LOGIN)) }, Modifier.fillMaxWidth())
            else when {
                trip.viewer == OpenTripViewer.ORGANIZER || trip.viewer == OpenTripViewer.PARTICIPANT ->
                    FloopelButton(s(Res.string.trip_open), { model.open(TravelerRoute(Destination.TRIP, trip.id)) }, Modifier.fillMaxWidth())
                trip.applicationId != null -> FloopelButton(s(Res.string.trip_application), {
                    model.open(TravelerRoute(Destination.APPLICATION, trip.applicationId))
                }, Modifier.fillMaxWidth())
                else -> FloopelButton(s(Res.string.trip_apply), { model.open(TravelerRoute(Destination.APPLY, trip.id, "form")) },
                    Modifier.fillMaxWidth(), enabled = trip.canApply && !state.busy)
            }
            if (!trip.canApply && trip.applicationId == null && trip.viewer == OpenTripViewer.VISITOR) InfoCard(s(Res.string.trip_apply_unavailable))
        }
    }
}

@Composable
internal fun TripList(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReadContent(state.trips, model::refresh) { trips ->
            if (trips.isEmpty()) EmptyContent()
            trips.forEach { trip -> ActionRow(trip.name, "${trip.startsOn} – ${trip.endsOn}", { model.open(TravelerRoute(Destination.TRIP, trip.id)) }) }
        }
    }
}

@Composable
internal fun TripDetail(state: TravelerState, model: TravelerViewModel, selectPhoto: TravelerImageSelection? = null) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ReadContent(state.trip, model::refresh) { trip ->
            Media(trip.bannerPhoto?.url, Modifier.fillMaxWidth().height(132.dp))
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(s(when (trip.status) { TripStatus.ACTIVE -> Res.string.trip_active; TripStatus.CANCELLED -> Res.string.trip_cancelled; TripStatus.COMPLETED -> Res.string.trip_completed }),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(trip.name, style = MaterialTheme.typography.headlineMedium)
                Body("${trip.startsOn} – ${trip.endsOn} · ${trip.timezone}")
                if (trip.role == TripCallerRole.ORGANIZER && trip.status == TripStatus.ACTIVE) {
                    var editBanner by remember(trip.id) { mutableStateOf(false) }
                    TextButton({ editBanner = !editBanner }) { Text(s(Res.string.choose_trip_banner)) }
                    if (editBanner || state.mediaPartial) ImageEditor(state, selectPhoto, model::refresh) { model.uploadImage(it, banner = true) }
                }
                Body(s(when (trip.role) { TripCallerRole.ORGANIZER -> Res.string.trip_organizer; TripCallerRole.PARTICIPANT -> Res.string.trip_participant; TripCallerRole.INVITEE -> Res.string.trip_invitee }))
                if (trip.role != TripCallerRole.INVITEE) {
                SectionTitle(s(Res.string.trip_itinerary))
                ReadContent(state.plan, model::refresh) { plan ->
                    plan.stops.forEach { stop ->
                        val entry = state.stopCards[stop.spotId] ?: CatalogEntry(stop.spotId, stop.spotId, stop.label, null, CatalogKind.SPOT, null)
                        CatalogCard(entry, { model.openEntry(entry) })
                    }
                }
                FloopelButton(s(Res.string.manage_members), { model.open(TravelerRoute(Destination.MEMBERS, trip.id)) }, Modifier.fillMaxWidth(), secondary = true)
                } else FloopelButton(s(Res.string.manage_invitations), { model.open(TravelerRoute(Destination.INVITATIONS)) }, Modifier.fillMaxWidth())
                if (trip.role == TripCallerRole.ORGANIZER) {
                    FloopelButton(s(Res.string.manage_invitations), { model.open(TravelerRoute(Destination.INVITATIONS, trip.id)) }, Modifier.fillMaxWidth(), secondary = true)
                    FloopelButton(s(Res.string.manage_applications), { model.open(TravelerRoute(Destination.TRIP_APPLICATIONS, trip.id)) }, Modifier.fillMaxWidth(), secondary = true)
                }
                var action by remember { mutableStateOf<TripLifecycleAction?>(null) }
                if (trip.status == TripStatus.ACTIVE) {
                    if (trip.role == TripCallerRole.ORGANIZER) {
                        FloopelButton(s(Res.string.trip_complete), { action = TripLifecycleAction.COMPLETE }, Modifier.fillMaxWidth())
                        FloopelButton(s(Res.string.trip_cancel), { action = TripLifecycleAction.CANCEL }, Modifier.fillMaxWidth(), secondary = true)
                    } else if (trip.role == TripCallerRole.PARTICIPANT) FloopelButton(s(Res.string.trip_leave), { action = TripLifecycleAction.LEAVE }, Modifier.fillMaxWidth(), secondary = true)
                }
                action?.let { pending -> AlertDialog({ action = null }, title = { Text(s(Res.string.confirm)) }, text = { Text(s(Res.string.confirm_body)) },
                    confirmButton = { TextButton({ model.lifecycle(pending); action = null }) { Text(s(Res.string.confirm)) } },
                    dismissButton = { TextButton({ action = null }) { Text(s(Res.string.cancel)) } }) }
            }
        }
    }
}

@Composable
internal fun TripForm(state: TravelerState, model: TravelerViewModel, selectPhoto: TravelerImageSelection? = null) {
    val confirmed = state.tripRecovery.value?.trip
    val pending = state.tripRecovery.value?.draft ?: confirmed?.let { TripDraft(it.name, it.startsOn, it.endsOn, it.timezone,
        it.capabilities?.visibility ?: TripVisibility.UNKNOWN,
        it.capabilities?.registrationOpensAt?.toString(), it.capabilities?.registrationClosesAt?.toString()) }
    var name by rememberSaveable(state.route.id, pending) { mutableStateOf(pending?.name.orEmpty()) }
    var start by rememberSaveable(state.route.id, pending) { mutableStateOf(pending?.startsOn.orEmpty()) }
    var end by rememberSaveable(state.route.id, pending) { mutableStateOf(pending?.endsOn.orEmpty()) }
    var public by rememberSaveable(state.route.id, pending) { mutableStateOf(pending?.visibility == TripVisibility.PUBLIC) }
    var opens by rememberSaveable(state.route.id, pending) { mutableStateOf(pending?.registrationOpensAt.orEmpty()) }
    var closes by rememberSaveable(state.route.id, pending) { mutableStateOf(pending?.registrationClosesAt.orEmpty()) }
    var image by remember(state.route.id) { mutableStateOf<TravelerImage?>(null) }
    val zone = pending?.timezone ?: state.tripTimezone
    val editable = pending == null && !state.busy
    val validDates = validTripDates(start, end)
    val validWindow = !public || validRegistrationWindow(opens, closes)
    FormLayout(footer = {
        FloopelButton(s(if (confirmed != null) Res.string.trip_resume_created else Res.string.create_trip), { if (zone != null) model.createTrip(pending ?: TripDraft(name, start, end, zone,
            if (public) TripVisibility.PUBLIC else TripVisibility.PRIVATE, opens.takeIf { public }, closes.takeIf { public }), image) },
            Modifier.fillMaxWidth(), enabled = !state.busy && name.isNotBlank() && zone != null && validDates && validWindow &&
                state.tripRecovery.value != null && !state.tripRecovery.loading && state.tripRecovery.failure == null)
    }) {
        Text(state.itinerary.value?.itinerary?.title.orEmpty(), style = MaterialTheme.typography.headlineMedium)
        InfoCard(s(Res.string.trip_working_plan_body))
        state.tripRecovery.failure?.let { ErrorPanel(it, model::refresh) }
        if (confirmed != null) InfoCard(s(Res.string.trip_already_created))
        else if (pending != null) InfoCard(s(Res.string.trip_frozen_draft))
        listOf(Triple(name, Res.string.trip_name, { v: String -> name = v }), Triple(start, Res.string.trip_start, { v: String -> start = v }),
            Triple(end, Res.string.trip_end, { v: String -> end = v })).forEach { (value, label, change) ->
            FloopelFormTextField(value, change, Modifier.fillMaxWidth(), label = { Text(s(label)) }, singleLine = true, enabled = editable)
        }
        Body(s(Res.string.trip_date_format))
        if (start.isNotBlank() && end.isNotBlank() && !validDates) InfoCard(s(Res.string.trip_dates_invalid))
        if (zone != null) Body(s(Res.string.trip_timezone, zone)) else InfoCard(s(Res.string.trip_timezone_missing))
        if (pending?.visibility != TripVisibility.UNKNOWN) CheckRow(s(Res.string.trip_public), public, { public = it }, editable)
        if (public) {
            FloopelFormTextField(opens, { opens = it }, Modifier.fillMaxWidth(), label = { Text(s(Res.string.registration_open)) }, enabled = editable)
            FloopelFormTextField(closes, { closes = it }, Modifier.fillMaxWidth(), label = { Text(s(Res.string.registration_close)) }, enabled = editable)
            Body(s(Res.string.trip_registration_format))
            InfoCard(s(Res.string.trip_admission_body))
        }
        if (selectPhoto != null) {
            FloopelButton(s(Res.string.choose_trip_banner), { selectPhoto { image = it } }, secondary = true, enabled = !state.busy)
            if (image != null) { Body(s(Res.string.image_selected)); TextButton({ image = null }) { Text(s(Res.string.remove_image)) } }
            Body(s(Res.string.image_requirements))
        }
    }
}

internal fun validTripDates(start: String, end: String): Boolean = runCatching {
    val pattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    pattern.matches(start) && pattern.matches(end) &&
        kotlin.time.Instant.parse("${start}T00:00:00Z") <= kotlin.time.Instant.parse("${end}T00:00:00Z")
}.getOrDefault(false)

internal fun validRegistrationWindow(opens: String, closes: String): Boolean = runCatching {
    kotlin.time.Instant.parse(opens) < kotlin.time.Instant.parse(closes)
}.getOrDefault(false)

@Composable
internal fun Applications(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReadContent(state.applications, model::refresh) { page ->
            if (page.items.isEmpty()) EmptyContent()
            page.items.forEach { application -> ActionRow(application.tripName, s(application.status.label()), {
                model.open(TravelerRoute(Destination.APPLICATION, application.id))
            }) }
            if (page.nextCursor != null) FloopelButton(s(Res.string.more), model::more, secondary = true, enabled = !state.applications.loading)
        }
    }
}

@Composable
internal fun ApplicationReader(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReadContent(state.application, model::refresh) { item ->
            Text(item.tripName, style = MaterialTheme.typography.headlineMedium)
            Text(s(item.status.label()), style = MaterialTheme.typography.titleLarge)
            Body("${item.startsOn} – ${item.endsOn} · ${item.timezone}")
            item.message?.let { Text(it) }
            InfoCard(s(Res.string.trip_admission_body))
            if (item.currentMember) FloopelButton(s(Res.string.trip_open), { model.open(TravelerRoute(Destination.TRIP, item.tripId)) }, Modifier.fillMaxWidth())
            else if (item.status == TripApplicationStatus.APPROVED) InfoCard(s(Res.string.trip_application_history))
            var decision by remember { mutableStateOf<TripApplicationDecision?>(null) }
            if (item.canApprove) FloopelButton(s(Res.string.approve), { decision = TripApplicationDecision.APPROVE }, Modifier.fillMaxWidth())
            if (item.viewer == TripApplicationViewer.ORGANIZER && item.status == TripApplicationStatus.PENDING && !item.admissionClosed)
                FloopelButton(s(Res.string.reject), { decision = TripApplicationDecision.REJECT }, Modifier.fillMaxWidth(), secondary = true)
            if (item.canWithdraw) FloopelButton(s(Res.string.withdraw), { decision = TripApplicationDecision.WITHDRAW }, Modifier.fillMaxWidth(), secondary = true)
            decision?.let { value -> AlertDialog({ decision = null }, title = { Text(s(Res.string.confirm)) }, text = { Text(s(Res.string.confirm_body)) },
                confirmButton = { TextButton({ model.decision(value); decision = null }) { Text(s(Res.string.confirm)) } },
                dismissButton = { TextButton({ decision = null }) { Text(s(Res.string.cancel)) } }) }
        }
    }
}

@Composable
internal fun TripNoticeReader(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReadContent(state.notice, model::refresh) { item ->
            Text(s(when (item.outcome) { TripNoticeOutcome.MEMBER_REMOVED -> Res.string.notice_removed; TripNoticeOutcome.CANCELLED -> Res.string.notice_cancelled;
                TripNoticeOutcome.SCHEDULE_CHANGED -> Res.string.notice_schedule; TripNoticeOutcome.UNKNOWN -> Res.string.trip_notice }), style = MaterialTheme.typography.headlineMedium)
            Text(item.tripName, style = MaterialTheme.typography.titleLarge)
            Body("${item.startsOn} – ${item.endsOn} · ${item.timezone}")
            InfoCard(s(Res.string.notice_own_only))
            if (item.currentMember) FloopelButton(s(Res.string.trip_open), { model.open(TravelerRoute(Destination.TRIP, item.tripId)) }, Modifier.fillMaxWidth())
            item.applicationId?.let { applicationId -> FloopelButton(s(Res.string.trip_application), {
                model.open(TravelerRoute(Destination.APPLICATION, applicationId))
            }, Modifier.fillMaxWidth(), secondary = true) }
        }
    }
}

@Composable
internal fun Members(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReadContent(state.members, model::refresh) { members ->
            members.forEach { member ->
                Text(member.displayName, style = MaterialTheme.typography.titleMedium)
                Body(s(if (member.role == TripRole.ORGANIZER) Res.string.trip_organizer else Res.string.trip_participant))
                if (state.trip.value?.role == TripCallerRole.ORGANIZER && member.role == TripRole.PARTICIPANT && member.active) {
                    var confirm by remember(member.profileId) { mutableStateOf(false) }
                    TextButton({ confirm = true }) { Text(s(Res.string.trip_remove_member)) }
                    if (confirm) AlertDialog({ confirm = false }, title = { Text(s(Res.string.trip_remove_member)) }, text = { Text(member.displayName) },
                        confirmButton = { TextButton({ model.removeMember(member.profileId); confirm = false }) { Text(s(Res.string.confirm)) } },
                        dismissButton = { TextButton({ confirm = false }) { Text(s(Res.string.cancel)) } })
                }
            }
        }
    }
}

@Composable
internal fun Invitations(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.route.id.isNotBlank()) {
            var recipient by remember { mutableStateOf("") }
            FloopelFormTextField(recipient, { recipient = it }, Modifier.fillMaxWidth(), label = { Text(s(Res.string.invite_recipient)) })
            FloopelButton(s(Res.string.invite), { model.invite(recipient) }, enabled = !state.busy && recipient.isNotBlank() &&
                state.trip.value?.role == TripCallerRole.ORGANIZER && !state.trip.loading && state.trip.failure == null && state.trip.value?.capabilities?.canInvite == true)
        }
        ReadContent(state.invitations, model::refresh) { items ->
            if (items.isEmpty()) EmptyContent()
            items.forEach { invitation ->
                Text(invitation.tripName, style = MaterialTheme.typography.titleMedium)
                Body(invitation.targetLabel)
                Body(invitation.status)
                if (state.route.id.isBlank() && invitation.status == "pending") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FloopelButton(s(Res.string.invitation_accept), { model.invitationDecision(invitation.id, InvitationDecision.ACCEPT) }, enabled = !state.busy)
                        FloopelButton(s(Res.string.invitation_decline), { model.invitationDecision(invitation.id, InvitationDecision.DECLINE) }, secondary = true, enabled = !state.busy)
                    }
                }
            }
        }
    }
}

private fun TripApplicationStatus.label() = when (this) {
    TripApplicationStatus.PENDING -> Res.string.trip_apply_pending
    TripApplicationStatus.APPROVED -> Res.string.trip_apply_approved
    TripApplicationStatus.REJECTED -> Res.string.trip_apply_rejected
    TripApplicationStatus.WITHDRAWN -> Res.string.trip_apply_withdrawn
    else -> Res.string.trip_apply_closed
}
