package com.floopel.feature.traveler.presentation.quest

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun QuestToolbar(title: String, model: TravelerViewModel, share: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp).testTag("quest-toolbar"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(model::back, Modifier.size(48.dp)) { Icon(FloopelIcons.ArrowBack, s(Res.string.back)) }
        Text(title, Modifier.weight(1f), style = FloopelCatalogTypography.toolbar)
        if (share != null) IconButton(share, Modifier.size(48.dp)) { Icon(FloopelIcons.Share, s(Res.string.quest_share)) }
    }
}

/** Figma 95:1089/1253/1519/1674; paging and personalization remain repository-owned. */
@Composable
internal fun QuestList(state: TravelerState, model: TravelerViewModel) {
    val browse = state.questBrowse
    val read = state.quests
    val page = read.value
    val scroll = rememberLazyListState()
    LaunchedEffect(browse.revision) { scroll.scrollToItem(0) }
    LaunchedEffect(browse.revision, page?.page, read.loading, read.failure, browse.taken) {
        if (!browse.taken && page != null && page.page < page.totalPages && !read.loading && read.failure == null)
            snapshotFlow { (scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= scroll.layoutInfo.totalItemsCount - 3 }
                .distinctUntilChanged().collect { if (it) model.more() }
    }
    Column(Modifier.fillMaxSize()) {
        QuestToolbar(s(Res.string.quest_catalog_title), model)
        LazyColumn(Modifier.fillMaxSize().testTag("quest-catalog"), state = scroll,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "controls") {
                Column(Modifier.padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(false to Res.string.quest_catalog_available, true to Res.string.quest_catalog_taken).forEach { (taken, label) ->
                            FloopelCompactButton(s(label), { model.questCollection(taken) },
                                Modifier.semantics { selected = browse.taken == taken }.testTag(if (taken) "quest-tab-taken" else "quest-tab-available"),
                                enabled = !state.busy, secondary = browse.taken != taken)
                        }
                    }
                    Text(s(Res.string.quest_catalog_kind), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(null to Res.string.all, "personal" to Res.string.quest_catalog_personal, "group" to Res.string.quest_catalog_group).forEach { (mode, label) ->
                            FloopelFilterChip(browse.filter.participationMode == mode, { model.questMode(mode) }, { Text(s(label)) },
                                Modifier.testTag("quest-mode-${mode ?: "all"}"), enabled = !state.busy, showSelectionIcon = true)
                        }
                    }
                    if (!browse.taken) {
                        val filter = browse.filter
                        val summary = listOfNotNull(filter.locationId?.let { filter.locationName?.takeIf(String::isNotBlank) ?: s(Res.string.spot_selected_location) },
                            when {
                                filter.minXP != null && filter.maxXP != null -> s(Res.string.quest_filter_range, filter.minXP, filter.maxXP)
                                filter.minXP != null -> s(Res.string.quest_filter_min_summary, filter.minXP)
                                filter.maxXP != null -> s(Res.string.quest_filter_max_summary, filter.maxXP)
                                else -> null
                            }).joinToString(" · ").ifBlank { s(Res.string.quest_catalog_filter) }
                        FloopelButton(summary, model::openQuestFilter, Modifier.fillMaxWidth().testTag("quest-filter-open"), secondary = true)
                        if (filter.locationId != null || filter.minXP != null || filter.maxXP != null) {
                            FloopelButton(s(Res.string.quest_filter_remove), model::clearQuestFilters, Modifier.fillMaxWidth(), secondary = true)
                        }
                    }
                }
            }
            if (!browse.taken) {
                if (read.loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                read.failure?.let { item(key = "error") { ErrorPanel(it, model::retryQuestCatalog) } }
                if (page != null) {
                    if (page.items.isEmpty() && !read.loading && read.failure == null) item(key = "empty") { QuestCatalogEmpty(browse.filter.participationMode, model) }
                    items(page.items, key = { it.id }) { entry -> QuestAvailableCard(entry) { model.open(TravelerRoute(Destination.QUEST, entry.card.slug)) } }
                    if (page.items.isNotEmpty()) item(key = "reward-note") { Box(Modifier.padding(top = 4.dp)) { Body(s(Res.string.quest_reward_body)) } }
                }
            } else {
                if (browse.takenRows.loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                browse.takenRows.failure?.let { item(key = "error") { ErrorPanel(it, model::refresh) } }
                val rows = browse.takenRows.value
                if (rows != null) {
                    if (rows.isEmpty() && !browse.takenRows.loading && browse.takenRows.failure == null) item(key = "empty") { QuestCatalogEmpty(browse.filter.participationMode, model) }
                    items(rows, key = { it.id }) { running ->
                        Surface(onClick = { model.continueQuest(running.id) }, Modifier.fillMaxWidth().testTag("quest-taken-${running.id}"),
                            shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow, enabled = !state.busy) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                running.mode?.let { QuestModeLabel(it) }
                                QuestCardTitle(running.title)
                                QuestProgressLabel(running)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestAvailableCard(entry: QuestCatalogItem, open: () -> Unit) {
    val card = entry.card
    Surface(onClick = open, modifier = Modifier.fillMaxWidth().testTag("quest-available-${card.id}"), shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(questAvailability(entry.availability), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                entry.isTaken?.let { Text(s(if (it) Res.string.quest_catalog_taken else Res.string.quest_catalog_not_taken), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            QuestModeLabel(card.mode)
            QuestCardTitle(card.title)
            if (card.locations.isNotEmpty()) Text(card.locations.joinToString(" \u00B7 ") { it.name }, style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(if (card.badgeUrl == null) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (card.badgeUrl != null) Media(card.badgeUrl, Modifier.size(48.dp, 56.dp), card.badgeName, FloopelIcons.EmojiEvents, ContentScale.Fit)
                else Icon(FloopelIcons.EmojiEvents, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(card.xp?.let { s(Res.string.quest_catalog_reward, it, card.activityCount) } ?: s(Res.string.location_activity_count, card.activityCount), style = MaterialTheme.typography.labelLarge)
                    card.badgeName?.let { Text(s(Res.string.quest_catalog_badge, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (card.badgeUrl != null || card.badgeName != null) Text(s(Res.string.quest_catalog_reward_label), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
internal fun QuestModeLabel(mode: String) = Text(s(if (mode == "group") Res.string.quest_mode_group_upper else Res.string.quest_mode_personal_upper),
    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun QuestCardTitle(title: String) = Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 25.65.sp))

@Composable
internal fun QuestProgressLabel(running: QuestRunning) {
    if (running.validProgress()) FloopelLabeledProgressIndicator(running.completed.toFloat() / running.total,
        s(Res.string.quest_progress_count, running.completed, running.total), Modifier.widthIn(max = 280.dp).testTag("quest-owner-progress"))
    else Body(s(Res.string.quest_progress_unavailable))
}

@Composable
private fun QuestCatalogEmpty(mode: String?, model: TravelerViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(s(when (mode) { "group" -> Res.string.quest_catalog_group_empty; "personal" -> Res.string.quest_catalog_personal_empty; else -> Res.string.quest_catalog_empty }), style = MaterialTheme.typography.headlineMedium)
        Body(s(Res.string.quest_catalog_empty_body))
        if (mode != null) FloopelButton(s(Res.string.quest_catalog_all_kinds), { model.questMode(null) }, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun questAvailability(value: String): String = s(when (value) {
    "active" -> Res.string.quest_status_active; "upcoming" -> Res.string.quest_status_upcoming; "ended" -> Res.string.quest_status_ended
    else -> Res.string.quest_status_unavailable
})

/** Figma 95:1395. The retained draft changes the applied catalog only after Apply. */
@Composable
internal fun QuestFilterScreen(state: TravelerState, model: TravelerViewModel) {
    val draft = state.questBrowse.draft ?: QuestFilterDraft()
    Column(Modifier.fillMaxSize()) {
        QuestToolbar(s(Res.string.quest_filter_title), model)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(s(Res.string.quest_filter_title), style = MaterialTheme.typography.headlineMedium.copy(fontSize = 26.sp, lineHeight = 35.1.sp))
            Body(s(Res.string.quest_filter_body))
            QuestXpInput(s(Res.string.quest_filter_minimum), draft.minimum, { model.questXpDraft(minimum = it) }, "quest-filter-min")
            QuestXpInput(s(Res.string.quest_filter_maximum), draft.maximum, { model.questXpDraft(maximum = it) }, "quest-filter-max")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s(Res.string.quest_filter_location), style = MaterialTheme.typography.labelLarge)
                FloopelButton(draft.location?.name?.takeIf(String::isNotBlank) ?: s(Res.string.quest_filter_choose_location), model::openQuestLocationPicker,
                    Modifier.fillMaxWidth().testTag("quest-filter-location"), secondary = true)
            }
            Body(s(Res.string.quest_filter_hint))
            if (!draft.valid) Text(s(Res.string.quest_filter_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            FloopelButton(s(Res.string.quest_filter_reset), model::resetQuestFilterDraft, Modifier.fillMaxWidth(), secondary = true)
            FloopelButton(s(Res.string.quest_filter_apply), model::applyQuestFilter, Modifier.fillMaxWidth().testTag("quest-filter-apply"), enabled = draft.valid && !state.busy)
        }
    }
    if (state.questBrowse.pickerOpen) AlertDialog(model::closeQuestLocationPicker, title = { Text(s(Res.string.quest_filter_choose_location)) }, text = {
        Column(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FloopelSearchField(state.questBrowse.pickerQuery, model::questLocationQuery, s(Res.string.quest_filter_search_location), Modifier.fillMaxWidth())
            ReadContent(state.questBrowse.pickerRows, model::loadQuestPickerLocations) { rows ->
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(rows, key = { it.id }) { location -> ActionRow(location.title, location.ancestors.joinToString(" \u00B7 ") { it.name }, { model.selectQuestFilterLocation(location) }) }
                    if (rows.isEmpty()) item { EmptyContent() }
                }
            }
        }
    }, confirmButton = { TextButton(model::closeQuestLocationPicker) { Text(s(Res.string.close)) } },
        dismissButton = { TextButton({ model.selectQuestFilterLocation(null) }) { Text(s(Res.string.quest_filter_remove_location)) } })
}

@Composable
private fun QuestXpInput(label: String, value: String, change: (String) -> Unit, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FloopelFormTextField(value, change, Modifier.fillMaxWidth().heightIn(min = 50.dp).testTag(tag).semantics { contentDescription = label },
            singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 21.6.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent))
    }
}
