package com.floopel.feature.traveler.presentation.quest

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.floopel.core.designsystem.component.FloopelButton
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*

/** Figma 397:10728/10829 feedback; current identity and server outcomes stay repository-owned. */
@Composable
internal fun QuestGroupRecoveryScreen(state: TravelerState, model: TravelerViewModel) {
    val recovery = state.questGroupMutationRecovery
    val read = recovery.review
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // The 799dp Figma viewport places the columns at y=252.167 / 284.167.
    // Keep the feedback near its lower edge as the native system insets change.
    val top = (maxHeight - if (read.loading) 514.833.dp else 546.833.dp).coerceAtLeast(20.dp)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = top, bottom = 28.dp)
        .testTag("quest-group-mutation-recovery"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            recovery.title?.let { Text(it, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 21.6.sp)) }
            Text(s(Res.string.quest_group_kind), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.55.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth().heightIn(min = 677.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
            var ready by remember { mutableStateOf(false) }
            AsyncImage(Res.getUri("files/quest_group_progress.svg"), null,
                Modifier.size(184.dp, 122.667.dp).testTag(if (ready) "quest-group-artwork-ready" else "quest-group-artwork-loading"),
                contentScale = ContentScale.Fit, onSuccess = { ready = true })
            Text(s(if (read.loading) Res.string.quest_group_recovery_checking else Res.string.quest_group_recovery_unknown),
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp, lineHeight = 32.4.sp, fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
            Text(s(if (read.loading) Res.string.quest_group_recovery_checking_body else Res.string.quest_group_recovery_unknown_body),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.55.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            if (read.failure != null) ErrorPanel(read.failure, model::reviewQuestGroupMutation)
            else if (!read.loading) FloopelButton(s(Res.string.quest_group_recovery_review), model::reviewQuestGroupMutation,
                Modifier.fillMaxWidth().testTag("quest-group-recovery-review"), enabled = !state.busy)
        }
    }
    }
    val review = read.value
    val rows = review?.let {
        it.groups.map { group -> (group.questTitle ?: s(Res.string.quest_groups)) + " · ${group.memberCount}/${group.sizeLimit} · " +
            s(if (group.managerProfileId == it.profileId) Res.string.quest_group_manager else Res.string.quest_group_members) + " · " +
            s(if (group.available) Res.string.trip_active else Res.string.quest_group_unavailable) } +
            it.invitations.map { invitation -> (invitation.username ?: invitation.questTitle ?: s(Res.string.quest_group_invitations)) + " · " + groupInvitationStatus(invitation.status) }
    }
    MutationRecoveryDecision(ReadState(rows, read.loading, read.failure),
        MutationRecoveryLabels("", "", s(Res.string.quest_group_recovery_title), s(Res.string.quest_group_recovery_confirm_body)),
        "quest-group", state.busy, model::startNewQuestGroupAction, model::closeQuestGroupMutationReview)
}

@Composable
internal fun groupInvitationStatus(status: String): String = s(when (status) {
    "pending" -> Res.string.quest_group_invitation_pending
    "accepted" -> Res.string.quest_group_invitation_accepted
    "declined" -> Res.string.quest_group_invitation_declined
    else -> Res.string.quest_group_invitation_cancelled
})
