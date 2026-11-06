package com.floopel.feature.traveler.presentation.itinerary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.theme.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*

@Composable
internal fun FormLayout(footer: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { footer() }
    }
}

@Composable
internal fun ItineraryList(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (state.route.destination != Destination.PICK_PLAN)
            FloopelButton(s(Res.string.new_itinerary), { model.open(TravelerRoute(Destination.ITINERARY_FORM)) }, Modifier.fillMaxWidth())
        state.pendingStop.value?.let { pending ->
            InfoCard(s(Res.string.pending_stop_body, pending.day))
            FloopelButton(s(Res.string.resume_action), { model.addSpot(pending.itineraryId, pending.spotId, pending.day) }, enabled = !state.busy)
        }
        ReadContent(state.itineraries, model::refresh) { items ->
            if (items.isEmpty()) EmptyContent(s(Res.string.itinerary_empty), s(Res.string.itinerary_start_body))
            var selected by remember { mutableStateOf<OwnedItinerary?>(null) }
            items.forEach { item ->
                ActionRow(item.title, s(Res.string.itinerary_summary, item.days, item.stops), {
                    if (state.route.destination == Destination.PICK_PLAN) selected = item
                    else model.open(TravelerRoute(Destination.ITINERARY, item.id))
                })
            }
            selected?.let { itinerary ->
                var day by remember(itinerary.id) { mutableStateOf("1") }
                AlertDialog(onDismissRequest = { selected = null }, title = { Text(s(Res.string.choose_day)) },
                    text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(itinerary.title)
                        FloopelFormTextField(day, { day = it }, label = { Text(s(Res.string.stop_day)) }, singleLine = true,
                            isError = day.toIntOrNull()?.let { it >= 1 } != true)
                    } }, confirmButton = {
                        TextButton({ day.toIntOrNull()?.takeIf { it >= 1 }?.let {
                            model.addSpot(itinerary.id, state.route.extra, it); selected = null
                        } }, enabled = !state.busy && day.toIntOrNull()?.let { it >= 1 } == true) { Text(s(Res.string.add_spot)) }
                    }, dismissButton = { TextButton({ selected = null }) { Text(s(Res.string.cancel)) } })
            }
        }
    }
}

@Composable
internal fun ItineraryScreen(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ReadContent(state.itinerary, model::refresh) { detail ->
            val itinerary = detail.itinerary
            Media(itinerary.coverUrl, Modifier.fillMaxWidth().height(154.dp).testTag("itinerary-cover"))
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(itinerary.title, Modifier.testTag("itinerary-title"), style = MaterialTheme.typography.headlineMedium)
                if (itinerary.description.isNotBlank()) Body(itinerary.description)
                Surface(shape = FloopelShape.chip, color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(s(if (itinerary.visibility == "public") Res.string.published else Res.string.private_plan),
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                if (itinerary.isOwner) ActionRow(s(Res.string.edit_info), s(Res.string.edit_info_body), {
                    model.open(TravelerRoute(Destination.ITINERARY_FORM, itinerary.id))
                })
                var titleDay by remember { mutableStateOf<Int?>(null) }
                val days = (detail.stops.map { it.day } + detail.dayTitles.keys + (1..maxOf(1, itinerary.days))).distinct().sorted()
                days.forEach { day ->
                    SectionTitle(detail.dayTitles[day]?.takeIf { it.isNotBlank() } ?: s(Res.string.day, day),
                        if (itinerary.isOwner) s(Res.string.edit) else null) { titleDay = day }
                    detail.stops.filter { it.day == day }.sortedBy { it.position }.forEach { stop ->
                        val card = state.stopCards[stop.spotId] ?: CatalogEntry(stop.spotId, stop.spotId,
                            stop.label, null, CatalogKind.SPOT, null)
                        CatalogCard(card, {
                            if (itinerary.isOwner) model.open(TravelerRoute(Destination.STOP_FORM, itinerary.id, stop.id))
                            else model.openEntry(card)
                        })
                        val metadata = listOfNotNull(stop.arriveTime.takeIf(String::isNotBlank),
                            stop.durationMinutes?.let { s(Res.string.duration) + ": $it" },
                            s(Res.string.stop_order) + ": ${stop.position}").joinToString(" · ")
                        Body(metadata)
                        if (stop.note.isNotBlank()) Body(stop.note)
                    }
                    if (itinerary.isOwner) FloopelButton(s(Res.string.add_spot), {
                        model.open(TravelerRoute(Destination.ADD_SPOT, itinerary.id, CatalogKind.SPOT.name, day))
                    }, Modifier.fillMaxWidth(), secondary = true)
                }
                if (itinerary.isOwner) {
                    FloopelButton(s(Res.string.manage_days), { titleDay = (days.maxOrNull() ?: 0) + 1 }, Modifier.fillMaxWidth(), secondary = true)
                    if (detail.stops.isEmpty()) InfoCard(s(Res.string.itinerary_empty_body))
                    FloopelButton(s(Res.string.create_trip), { model.open(TravelerRoute(Destination.TRIP_FORM, itinerary.id)) },
                        Modifier.fillMaxWidth(), enabled = detail.stopsLoaded && detail.stops.isNotEmpty())
                    FloopelButton(s(Res.string.review_publication), { model.open(TravelerRoute(Destination.PUBLICATION, itinerary.id)) },
                        Modifier.fillMaxWidth(), secondary = true, enabled = detail.stopsLoaded && detail.stops.isNotEmpty())
                    FloopelButton(s(Res.string.share_plan), { model.open(TravelerRoute(Destination.SHARE, itinerary.id)) }, Modifier.fillMaxWidth(), secondary = true)
                }
                titleDay?.let { day ->
                    var value by remember(day) { mutableStateOf(detail.dayTitles[day].orEmpty()) }
                    AlertDialog({ titleDay = null }, title = { Text(s(Res.string.day_title)) }, text = {
                        FloopelFormTextField(value, { value = it }, label = { Text(s(Res.string.day, day)) }, singleLine = true)
                    }, confirmButton = { TextButton({ model.dayTitle(day, value); titleDay = null }) { Text(s(Res.string.save)) } },
                        dismissButton = { TextButton({ titleDay = null }) { Text(s(Res.string.cancel)) } })
                }
            }
        }
    }
}

@Composable
internal fun ItineraryForm(state: TravelerState, model: TravelerViewModel, selectPhoto: TravelerImageSelection? = null) {
    val initial = state.itinerary.value?.itinerary
    val pending = state.itineraryDraft.value
    if (state.route.id.isNotBlank() && initial == null) { ReadContent(state.itinerary, model::refresh) {}; return }
    var title by rememberSaveable(state.route.id, initial?.id, pending) { mutableStateOf(initial?.title ?: pending?.title.orEmpty()) }
    var description by rememberSaveable(state.route.id, initial?.id, pending) { mutableStateOf(initial?.description ?: pending?.description.orEmpty()) }
    var submitted by remember { mutableStateOf(false) }
    FormLayout(footer = {
        FloopelButton(s(if (initial == null) Res.string.new_itinerary else Res.string.save), {
            submitted = true
            if (title.isNotBlank()) model.saveItinerary(pending ?: ItineraryDraft(title.trim(), description, initial?.coverMediaId))
        }, Modifier.fillMaxWidth(), enabled = !state.busy && !state.itineraryDraft.loading && state.itineraryDraft.failure == null)
    }) {
        Text(s(Res.string.itinerary_start), style = MaterialTheme.typography.headlineMedium)
        Body(s(Res.string.itinerary_start_body))
        if (pending != null) InfoCard(s(Res.string.trip_frozen_draft))
        state.itineraryDraft.failure?.let { ErrorPanel(it, model::refresh) }
        FloopelFormTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(s(Res.string.itinerary_title)) },
            singleLine = true, isError = submitted && title.isBlank(), enabled = !state.busy && pending == null)
        FloopelFormTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text(s(Res.string.description)) },
            minLines = 4, enabled = !state.busy && pending == null)
        if (initial != null) {
            Media(initial.coverUrl, Modifier.fillMaxWidth().height(154.dp))
            ImageEditor(state, selectPhoto, model::refresh) { model.uploadImage(it) }
        }
    }
}

@Composable
internal fun StopForm(state: TravelerState, model: TravelerViewModel) {
    val stop = state.itinerary.value?.stops?.firstOrNull { it.id == state.route.extra }
    if (stop == null) { ReadContent(state.itinerary, model::refresh) {}; return }
    var day by rememberSaveable(stop.id) { mutableStateOf(stop.day.toString()) }
    var order by rememberSaveable(stop.id) { mutableStateOf(stop.position.toString()) }
    var arrival by rememberSaveable(stop.id) { mutableStateOf(stop.arriveTime) }
    var duration by rememberSaveable(stop.id) { mutableStateOf(stop.durationMinutes?.toString().orEmpty()) }
    var note by rememberSaveable(stop.id) { mutableStateOf(stop.note) }
    var tips by rememberSaveable(stop.id) { mutableStateOf(stop.tips) }
    var travel by rememberSaveable(stop.id) { mutableStateOf(stop.travelNote) }
    var invalid by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    FormLayout(footer = {
        FloopelButton(s(Res.string.save_stop), {
            val time = arrival.trim(); val minutes = duration.trim()
            invalid = day.toIntOrNull()?.let { it >= 1 } != true || order.toIntOrNull()?.let { it >= 1 } != true ||
                time.isNotEmpty() && !Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(time) ||
                minutes.isNotEmpty() && minutes.toIntOrNull()?.let { it >= 0 } != true
            if (!invalid) model.saveStop(stop.copy(day = day.toInt(), position = order.toInt(),
                arriveTime = time, durationMinutes = minutes.toIntOrNull(), note = note, tips = tips, travelNote = travel))
        }, Modifier.fillMaxWidth(), enabled = !state.busy)
    }) {
        Text(stop.label, style = MaterialTheme.typography.titleLarge)
        if (invalid) Text(s(Res.string.stop_validation), color = MaterialTheme.colorScheme.error)
        listOf(Triple(day, Res.string.stop_day, { v: String -> day = v }),
            Triple(order, Res.string.stop_order, { v: String -> order = v }),
            Triple(arrival, Res.string.arrival_time, { v: String -> arrival = v }),
            Triple(duration, Res.string.duration, { v: String -> duration = v })).forEach { (value, label, change) ->
            FloopelFormTextField(value, change, Modifier.fillMaxWidth(), label = { Text(s(label)) }, singleLine = true, enabled = !state.busy)
            if (label == Res.string.arrival_time) Body(s(Res.string.arrival_hint))
        }
        listOf(Triple(note, Res.string.notes, { v: String -> note = v }), Triple(tips, Res.string.tips, { v: String -> tips = v }),
            Triple(travel, Res.string.travel_notes, { v: String -> travel = v })).forEach { (value, label, change) ->
            FloopelFormTextField(value, change, Modifier.fillMaxWidth(), label = { Text(s(label)) }, minLines = 3, enabled = !state.busy)
        }
        TextButton({ remove = true }, enabled = !state.busy) { Text(s(Res.string.remove_spot)) }
        if (remove) AlertDialog({ remove = false }, title = { Text(s(Res.string.remove_spot)) }, text = { Text(stop.label) },
            confirmButton = { TextButton({ model.removeStop(stop.id); remove = false }) { Text(s(Res.string.confirm)) } },
            dismissButton = { TextButton({ remove = false }) { Text(s(Res.string.cancel)) } })
    }
}

@Composable
internal fun ShareHub(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReadContent(state.distribution, model::refresh) { item ->
            Text(item.title, style = MaterialTheme.typography.headlineMedium)
            Body(s(Res.string.share_hub_body))
            FloopelButton(s(Res.string.private_link), { model.open(TravelerRoute(Destination.SHARE_LINK, item.id)) }, Modifier.fillMaxWidth())
            FloopelButton(s(Res.string.review_publication), { model.open(TravelerRoute(Destination.PUBLICATION, item.id)) },
                Modifier.fillMaxWidth(), secondary = true, enabled = item.stopCount > 0)
            FloopelButton(s(Res.string.back_to_plan), model::back, Modifier.fillMaxWidth(), secondary = true)
        }
    }
}

@Composable
internal fun PublicationScreen(state: TravelerState, model: TravelerViewModel, selectPhoto: TravelerImageSelection? = null) {
    val item = state.distribution.value
    if (item == null) { ReadContent(state.distribution, model::refresh) {}; return }
    val hasSpots = state.itinerary.value?.let { it.stopsLoaded && it.stops.isNotEmpty() } == true && item.stopCount > 0
    val pending = state.pendingPublication.value
    val readyCover = state.media.value == CoverProcessing.READY && !state.media.loading && state.media.failure == null
    var notes by rememberSaveable(item.updatedAt, pending) { mutableStateOf(pending?.notesReviewed ?: false) }
    var pii by rememberSaveable(item.updatedAt, pending) { mutableStateOf(pending?.piiAcknowledged ?: false) }
    var clone by rememberSaveable(item.updatedAt, pending) { mutableStateOf(pending?.allowClone ?: item.allowClone) }
    var confirm by remember { mutableStateOf(false) }
    FormLayout(footer = {
        FloopelButton(s(Res.string.publish), { confirm = true }, Modifier.fillMaxWidth(),
            enabled = notes && pii && hasSpots && readyCover && !state.busy && item.updatedAt != null)
    }) {
        Text(item.title, style = MaterialTheme.typography.headlineMedium)
        Media(item.coverUrls.firstOrNull(), Modifier.fillMaxWidth().height(128.dp))
        ImageEditor(state, selectPhoto, model::refresh) { model.uploadImage(it) }
        if (!readyCover) InfoCard(s(Res.string.publication_cover_required))
        Body(item.description)
        Body(s(Res.string.publication_body))
        ReadContent(state.itinerary, model::refresh) { detail -> Body(s(Res.string.itinerary_summary, detail.itinerary.days, detail.stops.size)) }
        if (!hasSpots) InfoCard(s(Res.string.publication_empty))
        CheckRow(s(Res.string.publication_notes), notes, { notes = it }, pending == null && !state.busy)
        CheckRow(s(Res.string.publication_pii), pii, { pii = it }, pending == null && !state.busy)
        CheckRow(s(Res.string.allow_clone), clone, { clone = it }, pending == null && !state.busy)
        if (state.saved) InfoCard(s(Res.string.publication_done))
        if (item.visibility == "public") {
            var unpublish by remember { mutableStateOf(false) }
            FloopelButton(s(Res.string.unpublish), { unpublish = true }, Modifier.fillMaxWidth(), secondary = true, enabled = !state.busy)
            if (unpublish) AlertDialog({ unpublish = false }, title = { Text(s(Res.string.unpublish)) }, text = { Text(s(Res.string.unpublish_body)) },
                confirmButton = { TextButton({ model.unpublish(); unpublish = false }) { Text(s(Res.string.confirm)) } },
                dismissButton = { TextButton({ unpublish = false }) { Text(s(Res.string.cancel)) } })
        }
    }
    if (confirm) AlertDialog({ confirm = false }, title = { Text(s(Res.string.confirm_publish)) }, text = { Text(item.title) },
        confirmButton = { TextButton({ if (hasSpots && readyCover) model.publish(clone, notes, pii); confirm = false }, enabled = hasSpots && readyCover) { Text(s(Res.string.publish)) } },
        dismissButton = { TextButton({ confirm = false }) { Text(s(Res.string.cancel)) } })
}

@Composable
internal fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
        .padding(vertical = 8.dp)) {
        Checkbox(checked, null, enabled = enabled)
        Text(label, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ShareLinkScreen(state: TravelerState, model: TravelerViewModel, share: (String) -> Unit) {
    var confirmation by remember { mutableStateOf<Boolean?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Body(s(Res.string.link_body))
        ReadContent(state.shareLink, model::refresh) { link ->
            val url = link.url
            if (link.active && url != null) {
                Text(url, style = MaterialTheme.typography.bodyMedium)
                FloopelButton(s(Res.string.share_link), { share(url) }, Modifier.fillMaxWidth())
                FloopelButton(s(Res.string.revoke_link), { confirmation = false }, Modifier.fillMaxWidth(), secondary = true, enabled = !state.busy)
            } else InfoCard(s(Res.string.link_unavailable))
        }
        FloopelButton(s(if (state.shareLink.value?.active == true) Res.string.rotate_link else Res.string.create_link), { confirmation = true }, Modifier.fillMaxWidth(), enabled = !state.busy)
    }
    confirmation?.let { create -> AlertDialog({ confirmation = null }, title = { Text(s(Res.string.confirm)) }, text = { Text(s(Res.string.link_rotation_body)) },
        confirmButton = { TextButton({ if (create) model.shareLink(true) else model.revokeLink(); confirmation = null }) { Text(s(Res.string.confirm)) } },
        dismissButton = { TextButton({ confirmation = null }) { Text(s(Res.string.cancel)) } }) }
}
