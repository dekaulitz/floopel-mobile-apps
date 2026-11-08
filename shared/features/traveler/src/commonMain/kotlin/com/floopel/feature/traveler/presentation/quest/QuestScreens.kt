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
    val submittedAnswer = state.identity.submittedAnswer
    var answer by rememberSaveable(state.identity.generation, state.identity.running?.id, question?.id) {
        mutableStateOf(submittedAnswer.orEmpty())
    }
    LaunchedEffect(submittedAnswer) {
        if (submittedAnswer != null) answer = submittedAnswer
    }
    val shownAnswer = if (state.identity.busy) submittedAnswer ?: answer else answer
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
            if (question.options.isNotEmpty()) question.options.forEach { (id, label) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("quest-answer-option-$id")
                    .selectable(shownAnswer == id, enabled = !state.identity.busy, role = Role.RadioButton,
                        onClick = { if (!state.identity.busy) answer = id }),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(shownAnswer == id, null, enabled = !state.identity.busy)
                    Text(label, Modifier.padding(start = 12.dp))
                }
            } else FloopelFormTextField(shownAnswer, { if (!state.identity.busy) answer = it },
                Modifier.fillMaxWidth().testTag("quest-answer-input"), enabled = !state.identity.busy, label = { Text(s(Res.string.quest_answer)) })
            FloopelButton(s(Res.string.quest_submit), { model.answer(answer) }, Modifier.fillMaxWidth().testTag("quest-answer-submit"),
                enabled = answer.isNotBlank() && !state.identity.busy && state.identity.executionConfigured)
        } else InfoCard(s(Res.string.error_unavailable))
        state.identity.answerOutcome?.takeIf {
            !state.identity.busy && (submittedAnswer == null || answer == submittedAnswer)
        }?.let { outcome ->
            InfoCard(s(if (outcome == "correct" || outcome == "accepted") Res.string.quest_result_correct else Res.string.quest_result_incorrect))
            state.identity.explanation?.let { Text(it) }
            FloopelButton(s(Res.string.quest_progress), model::back, Modifier.fillMaxWidth(), secondary = true)
        }
        if (state.identity.completed) InfoCard(s(Res.string.quest_complete_body))
    }
}
