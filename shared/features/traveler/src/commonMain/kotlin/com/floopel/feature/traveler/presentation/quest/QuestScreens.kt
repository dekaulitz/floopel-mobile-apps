package com.floopel.feature.traveler.presentation.quest

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.floopel.core.designsystem.component.*
import com.floopel.core.designsystem.icons.FloopelIcons
import com.floopel.core.designsystem.theme.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*

@Composable
internal fun QuestList(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReadContent(state.quests, model::refresh) { page ->
            if (page.items.isEmpty()) EmptyContent()
            page.items.forEach { quest ->
                Surface(onClick = { model.open(TravelerRoute(Destination.QUEST, quest.slug)) }, shape = FloopelShape.card,
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Media(quest.badgeImage?.url, Modifier.size(44.dp, 52.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(quest.title, style = MaterialTheme.typography.titleMedium)
                            Body(quest.locations.joinToString { it.name })
                            Text(s(Res.string.xp, quest.xp), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (page.page < page.totalPages) FloopelButton(s(Res.string.more), model::more, Modifier.fillMaxWidth(), secondary = true, enabled = !state.quests.loading)
        }
    }
}

@Composable
internal fun QuestDetail(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        ReadContent(state.quest, model::refresh) { quest ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(FloopelIcons.EmojiEvents, null, tint = MaterialTheme.colorScheme.primary)
                Text(if (quest.availability == "active") s(if (quest.card.mode == "group") Res.string.quest_active_group else Res.string.quest_active_personal)
                    else quest.availability, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(quest.card.title, style = MaterialTheme.typography.headlineLarge)
            quest.card.locations.forEach { location ->
                FloopelButton(location.name, { model.open(TravelerRoute(Destination.DETAIL, location.id, CatalogKind.LOCATION.name)) }, Modifier.fillMaxWidth(), secondary = true)
            }
            if (quest.description.isNotBlank()) RichBody(quest.description)
            quest.card.xp?.let { xp ->
                Surface(shape = FloopelShape.card, color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (quest.card.badgeUrl != null) Media(quest.card.badgeUrl, Modifier.size(64.dp, 75.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(s(Res.string.quest_reward), style = MaterialTheme.typography.labelSmall)
                            Text(s(Res.string.xp, xp), style = MaterialTheme.typography.headlineLarge)
                            quest.badgeName?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                        }
                    }
                }
            }
            Body(s(Res.string.quest_reward_body))
            if (quest.travelers.isNotEmpty()) { SectionTitle(s(Res.string.quest_travelers)); Body(quest.travelers.joinToString()) }
            if (quest.remaining != null && quest.limit != null) Body(s(Res.string.quest_quota, quest.remaining, quest.limit))
            if (quest.card.mode == "group") {
                InfoCard(s(Res.string.quest_group_note))
                if (state.identity.signedIn) FloopelButton(s(Res.string.quest_groups), {
                    model.open(TravelerRoute(Destination.QUEST_GROUPS, quest.card.id, quest.card.slug))
                }, Modifier.fillMaxWidth(), secondary = true)
            }
            if (quest.availability == "owner_snapshot") InfoCard(s(Res.string.quest_owner_snapshot))
            if (!state.identity.signedIn) FloopelButton(s(Res.string.sign_in), { model.open(TravelerRoute(Destination.LOGIN)) }, Modifier.fillMaxWidth())
            else if (state.identity.ownerStatus == "active") FloopelButton(s(Res.string.quest_taken), model::continueQuest, Modifier.fillMaxWidth(), enabled = !state.identity.busy)
            else if (state.identity.ownerStatus == "completed") InfoCard(s(Res.string.quest_complete_title))
            else {
                if (!state.identity.executionConfigured) InfoCard(s(Res.string.quest_provider_missing))
                FloopelButton(s(Res.string.quest_take), model::takeQuest, Modifier.fillMaxWidth(),
                    enabled = state.identity.executionConfigured && !state.identity.busy && quest.availability == "active" && quest.executionStatus == "available" && quest.remaining != 0)
            }
            SectionTitle(s(Res.string.quest_how))
            RichBody(quest.instructions)
            SectionTitle(s(Res.string.quest_steps, quest.steps.size))
            quest.steps.sortedBy { it.ordinal }.forEach { step ->
                Surface(shape = FloopelShape.card, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text("${step.ordinal.toString().padStart(2, '0')}  ${step.title}", Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@Composable
internal fun QuestProgress(state: TravelerState, model: TravelerViewModel) {
    val running = state.identity.running
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(s(if (state.identity.completed) Res.string.quest_complete_title else Res.string.quest_progress_title), style = MaterialTheme.typography.headlineMedium)
        Body(s(if (state.identity.completed) Res.string.quest_complete_body else Res.string.quest_progress_body))
        if (running != null) {
            Text(running.title, style = MaterialTheme.typography.titleMedium)
            Body(s(Res.string.quest_progress_count, running.completed, running.total))
            LinearProgressIndicator(progress = { if (running.total > 0) running.completed.toFloat() / running.total else 0f }, Modifier.fillMaxWidth())
            val titles = state.quest.value?.steps?.associateBy { it.id }.orEmpty()
            running.steps.entries.forEachIndexed { index, entry ->
                val title = titles[entry.key]?.title?.takeIf(String::isNotBlank) ?: "${s(Res.string.quest_activity)} ${index + 1}"
                val description = when (entry.value) { "locked" -> s(Res.string.quest_locked); "completed" -> s(Res.string.quest_completed);
                    "pending" -> s(Res.string.quest_pending); else -> s(Res.string.quest_ready) }
                ActionRow(title, description, { model.activity(entry.key) }, enabled = entry.value != "locked" && !state.identity.busy)
            }
        } else if (state.identity.busy) Text(s(Res.string.loading))
        if (state.identity.completed) FloopelButton(s(Res.string.passport), { model.open(TravelerRoute(Destination.PASSPORT)) }, Modifier.fillMaxWidth())
    }
}

@Composable
internal fun QuestActivity(state: TravelerState, model: TravelerViewModel) {
    val question = state.identity.question?.takeIf { it.id == state.route.id }
    var retryAllowed by remember(state.identity.retryAtMillis) { mutableStateOf(state.identity.retryAtMillis <= kotlin.time.Clock.System.now().toEpochMilliseconds()) }
    LaunchedEffect(state.identity.retryAtMillis) {
        val remaining = (state.identity.retryAtMillis - kotlin.time.Clock.System.now().toEpochMilliseconds()).coerceAtLeast(0)
        if (remaining > 0) kotlinx.coroutines.delay(remaining)
        retryAllowed = true
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (question == null) {
            if (state.identity.busy) Text(s(Res.string.loading))
            else FloopelButton(s(Res.string.retry), model::refresh, Modifier.testTag("quest-activity-retry"))
            return@Column
        }
        Text(question.title, style = MaterialTheme.typography.headlineMedium)
        RichBody(question.instructions)
        if (question.kind == QuestActivityKind.GPS) {
            InfoCard(s(Res.string.quest_gps_body))
            if (state.identity.gpsCollecting) Text(s(Res.string.quest_gps_collecting))
            if (state.identity.locationUnavailable) InfoCard(s(Res.string.quest_gps_unavailable))
            if (state.identity.gpsSubmissionPending) InfoCard(s(Res.string.quest_gps_pending))
            state.identity.gpsOutcome?.let { outcome ->
                InfoCard(s(when (outcome) {
                    "verified" -> Res.string.quest_gps_verified
                    "expired" -> Res.string.quest_gps_expired
                    else -> Res.string.quest_gps_rejected
                }))
                FloopelButton(s(Res.string.quest_progress), model::back, Modifier.fillMaxWidth(), secondary = true)
            }
            if (state.identity.gpsOutcome != "verified") FloopelButton(s(Res.string.quest_collect_gps), { model.gps() }, Modifier.fillMaxWidth().testTag("quest-gps-collect"),
                enabled = state.identity.executionConfigured && !state.identity.busy && !state.identity.gpsCollecting && !state.identity.gpsSubmissionPending && !state.identity.gpsCollectionBlocked)
            if (state.identity.gpsReady || state.identity.gpsSubmissionPending)
                FloopelButton(s(if (state.identity.gpsSubmissionPending) Res.string.retry else Res.string.quest_submit_gps),
                    { model.gps(true) }, Modifier.fillMaxWidth().testTag("quest-gps-submit"), enabled = !state.identity.busy && retryAllowed)
        } else if (question.kind == QuestActivityKind.QUESTION) {
            var answer by rememberSaveable(question.id) { mutableStateOf("") }
            if (question.options.isNotEmpty()) question.options.forEach { (id, label) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(answer == id, role = Role.RadioButton, onClick = { answer = id }),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(answer == id, null)
                    Text(label, Modifier.padding(start = 12.dp))
                }
            } else FloopelFormTextField(answer, { answer = it }, Modifier.fillMaxWidth(), label = { Text(s(Res.string.quest_answer)) })
            FloopelButton(s(Res.string.quest_submit), { model.answer(answer) }, Modifier.fillMaxWidth(),
                enabled = answer.isNotBlank() && !state.identity.busy && state.identity.executionConfigured)
        } else InfoCard(s(Res.string.error_unavailable))
        state.identity.answerOutcome?.let { outcome ->
            InfoCard(s(if (outcome == "correct" || outcome == "accepted") Res.string.quest_result_correct else Res.string.quest_result_incorrect))
            state.identity.explanation?.let { Text(it) }
            FloopelButton(s(Res.string.quest_progress), model::back, Modifier.fillMaxWidth(), secondary = true)
        }
        if (state.identity.completed) InfoCard(s(Res.string.quest_complete_body))
    }
}

@Composable
internal fun PassportScreen(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        val passport = state.identity.passport
        if (passport == null) { if (state.identity.busy) Text(s(Res.string.loading)); return@Column }
        Text(s(Res.string.level, passport.level, passport.levelName), style = MaterialTheme.typography.headlineMedium)
        Text(s(Res.string.xp, passport.xp), style = MaterialTheme.typography.headlineLarge)
        SectionTitle(s(Res.string.badges))
        passport.badges.forEach { (name, image) ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Media(image, Modifier.size(64.dp, 75.dp))
                Text(name, style = MaterialTheme.typography.titleMedium)
            }
        }
        SectionTitle(s(Res.string.unfinished_quest))
        passport.running.forEach { quest -> ActionRow(quest.title, s(Res.string.quest_progress_count, quest.completed, quest.total), { model.continueQuest(quest.id) }) }
    }
}
