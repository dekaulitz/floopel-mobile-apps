package com.floopel.feature.traveler.presentation.quest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.FloopelShape
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.presentation.catalog.catalogCount
import com.floopel.feature.traveler.presentation.catalog.catalogScore
import com.floopel.feature.traveler.presentation.catalog.profileInitials
import com.floopel.feature.traveler.resources.*

/** Figma 97:1483/1796. Public checkpoint previews never fetch activity answers or proof. */
@Composable
internal fun QuestDetail(state: TravelerState, model: TravelerViewModel, share: (String) -> Unit) {
    val quest = state.quest.value
    val sharedText = quest?.let { s(Res.string.quest_share_text, it.card.title, it.card.locations.joinToString(" Â· ") { location -> location.name }) }
    Column(Modifier.fillMaxSize()) {
        QuestToolbar(s(Res.string.quest_detail_title), model, sharedText?.let { text -> { share(text) } })
        LazyColumn(Modifier.fillMaxSize().testTag("quest-detail"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.quest.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.quest.failure?.let { item { ErrorPanel(it, model::refresh) } }
            if (quest != null) {
                questSection("availability") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(FloopelIcons.EmojiEvents, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(if (quest.availability == "owner_snapshot") s(Res.string.quest_owner_snapshot) else
                            s(Res.string.quest_detail_availability, questAvailability(quest.availability).uppercase(), s(if (quest.card.mode == "group") Res.string.quest_mode_group_upper else Res.string.quest_mode_personal_detail)),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
                questSection("title") { Text(quest.card.title, style = MaterialTheme.typography.headlineLarge) }
                if (quest.card.locations.isNotEmpty()) questSection("locations") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        quest.card.locations.forEach { location -> FloopelButton(location.name,
                            { model.open(TravelerRoute(Destination.DETAIL, location.id, CatalogKind.LOCATION.name)) }, Modifier.fillMaxWidth(), secondary = true) }
                    }
                }
                if (quest.description.isNotBlank()) questSection("description") { RichBody(quest.description) }
                quest.card.xp?.let { xp -> questSection("reward") {
                    Surface(Modifier.fillMaxWidth().testTag("quest-reward-preview"), shape = FloopelShape.card,
                        color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (quest.card.badgeUrl != null) Media(quest.card.badgeUrl, Modifier.size(64.dp, 74.667.dp), quest.badgeName,
                                FloopelIcons.EmojiEvents, ContentScale.Fit)
                            else Icon(FloopelIcons.EmojiEvents, null, Modifier.size(32.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(s(Res.string.quest_reward_preview), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium))
                                Text(s(Res.string.xp, xp), style = MaterialTheme.typography.headlineLarge.copy(fontSize = 26.sp, lineHeight = 35.1.sp))
                                quest.badgeName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                            }
                        }
                    }
                } }
                if (quest.card.xp != null) questSection("reward-note") { Body(s(Res.string.quest_detail_reward_body)) }
                if (quest.travelerPreviews.isNotEmpty() || quest.travelers.isNotEmpty()) questSection("travelers") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s(Res.string.quest_travelers), style = MaterialTheme.typography.titleMedium)
                        if (quest.travelerPreviews.isNotEmpty()) Row {
                            quest.travelerPreviews.forEach { traveler -> FloopelAvatar(profileInitials(traveler.displayName).uppercase(), 32.dp,
                                image = traveler.avatarUrl?.let { url -> { Media(url, Modifier.fillMaxSize()) } },
                                contentColor = MaterialTheme.colorScheme.onSurface, labelStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Normal)) }
                        }
                        Body((quest.travelerPreviews.map { it.displayName }.ifEmpty { quest.travelers }).joinToString(", "))
                    }
                }
                questSection("owner-action") { QuestOwnerAction(quest, state, model) }
                if (quest.instructions.isNotBlank()) questSection("instructions") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s(Res.string.quest_how), style = MaterialTheme.typography.titleLarge)
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) { RichBody(quest.instructions) }
                    }
                }
                item(key = "steps-title") { Text(s(Res.string.quest_steps, quest.steps.size), style = MaterialTheme.typography.titleLarge) }
                items(quest.steps.sortedBy { it.ordinal }, key = { it.id }) { step -> QuestCheckpoint(step, quest, state, model) }
            }
        }
    }
}

@Composable
private fun QuestOwnerAction(quest: QuestReader, state: TravelerState, model: TravelerViewModel) {
    val owner = quest.boundOwner()
    val running = owner?.participation?.takeIf { owner.status == "active" && it.status == "active" }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            running != null -> {
                Text(s(Res.string.quest_in_progress), style = MaterialTheme.typography.titleMedium)
                QuestProgressLabel(running)
                FloopelButton(s(Res.string.quest_taken), model::continueQuest, Modifier.fillMaxWidth().testTag("quest-continue"),
                    enabled = !state.busy && !state.identity.busy && !state.quest.loading && state.quest.failure == null)
            }
            owner?.status == "completed" -> InfoCard(s(Res.string.quest_complete_title))
            quest.card.mode == "group" -> {
                InfoCard(s(Res.string.quest_group_note))
                FloopelButton(s(Res.string.quest_groups), { model.open(TravelerRoute(Destination.QUEST_GROUPS, quest.card.id, quest.card.slug)) }, Modifier.fillMaxWidth(), enabled = !state.busy)
            }
            else -> {
                if (quest.remaining != null && quest.limit != null) Text(s(Res.string.quest_detail_quota, quest.remaining, quest.limit),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 18.9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                FloopelButton(s(Res.string.quest_take), model::takeQuest, Modifier.fillMaxWidth().testTag("quest-take"),
                    enabled = quest.canTake(state.identity) && !state.busy && !state.quest.loading && state.quest.failure == null)
                if (!state.identity.executionConfigured && owner?.status == "not_completed") InfoCard(s(Res.string.quest_provider_missing))
            }
        }
    }
}

@Composable
private fun QuestCheckpoint(step: QuestStep, quest: QuestReader, state: TravelerState, model: TravelerViewModel) {
    val owner = quest.boundOwner()
    val status = owner?.participation?.takeIf { owner.status == "active" }?.steps?.get(step.id) ?: step.status
    Surface(Modifier.fillMaxWidth().testTag("quest-checkpoint-${step.id}"), shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(step.ordinal.toString().padStart(2, '0'), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(step.title.ifBlank { s(Res.string.quest_activity) }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            }
            if (status != null) Text(s(when (status) { "completed" -> Res.string.quest_checkpoint_complete
                "locked", "available", "ready", "pending" -> Res.string.quest_checkpoint_incomplete
                else -> Res.string.quest_progress_unavailable }), style = MaterialTheme.typography.labelSmall,
                color = if (status == "completed") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            step.spotId?.let { id ->
                val read = state.questSpots[id]
                if (read?.loading == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                read?.failure?.let { ErrorPanel(it, { model.loadQuestSpot(id) }) }
                read?.value?.let { spot ->
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { model.openEntry(spot) }.testTag("quest-checkpoint-spot-$id"),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Media(spot.coverUrl, Modifier.size(72.dp).clip(FloopelShape.media), placeholderIcon = FloopelIcons.Image)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(spot.title, style = MaterialTheme.typography.titleSmall)
                            spot.locationName?.let { Text(it, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            val decimal = s(Res.string.catalog_decimal_separator)
                            val score = spot.aiReference?.takeUnless { it.suppressed }?.score?.takeIf { it.isFinite() && it in 0.0..5.0 }
                            val count = spot.commentCount?.takeIf { it >= 0 }
                            val description = listOfNotNull(score?.let { s(Res.string.reference_rating, catalogScore(it, decimal)) },
                                count?.let { s(Res.string.comments_count, it) }).joinToString(", ")
                            FloopelCheckpointRating(score, score?.let { catalogScore(it, decimal) }, count?.let { catalogCount(it, decimal) }, description,
                                Modifier.testTag("quest-checkpoint-rating-$id"))
                        }
                    }
                }
            }
        }
    }
}

/** Keeps 20dp between summary sections and 12dp within the lazy checkpoint list. */
private fun LazyListScope.questSection(key: String, content: @Composable () -> Unit) {
    item(key) { Box(Modifier.padding(bottom = 8.dp)) { content() } }
}
