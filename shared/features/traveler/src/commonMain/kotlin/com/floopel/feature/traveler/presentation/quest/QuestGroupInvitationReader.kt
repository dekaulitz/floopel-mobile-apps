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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.floopel.core.designsystem.component.FloopelButton
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*

/** Figma 397:8840 pending recipient. Location/capacity are absent from this projection. */
@Composable
internal fun QuestGroupInvitationReader(state: TravelerState, model: TravelerViewModel) {
    val profile = state.profile
    val profileKnown = profile.value?.profileId != null && !profile.loading && profile.failure == null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp).testTag("quest-group-invitation-reader"),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (profile.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        profile.failure?.let { ErrorPanel(it, model::refresh) }
        ReadContent(state.groupInvitation, model::refresh) { invitation ->
            val recipient = profileKnown && profile.value?.profileId == invitation.profileId
            val pending = invitation.status == "pending"
            if (pending && (recipient || !profileKnown)) {
                var ready by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxWidth().height(96.dp).testTag("quest-group-invitation-artwork-slot"), Alignment.Center) {
                    AsyncImage(Res.getUri("files/quest_group_mail.svg"), null,
                        Modifier.size(144.dp,96.dp).testTag(if (ready) "quest-group-invitation-artwork-ready" else "quest-group-invitation-artwork-loading"),
                        contentScale = ContentScale.Fit, onSuccess = { ready = true })
                }
            }
            Text(invitation.questTitle ?: s(Res.string.quest_groups), Modifier.fillMaxWidth().testTag("quest-group-invitation-quest").semantics { heading() },
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.Bold))
            Text(s(Res.string.quest_group_kind), Modifier.fillMaxWidth().testTag("quest-group-invitation-context"),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.55.sp, fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.primary)
            if (pending && (recipient || !profileKnown)) {
                Text(if (recipient && invitation.inviterUsername != null) s(Res.string.quest_group_inviter_heading,"@${invitation.inviterUsername}")
                    else s(Res.string.quest_group_invitation_heading), Modifier.fillMaxWidth().testTag("quest-group-invitation-inviter").semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp, lineHeight = 32.4.sp, fontWeight = FontWeight.Bold))
                Text(s(Res.string.quest_group_invitation_note), Modifier.fillMaxWidth().testTag("quest-group-invitation-note"),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, lineHeight = 17.55.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(groupInvitationStatus(invitation.status), Modifier.testTag("quest-group-invitation-status"))
                Body(s(Res.string.quest_group_note))
            }
            val authoritative = state.groupInvitation.value?.id == state.route.id && !state.groupInvitation.loading && state.groupInvitation.failure == null
            if (pending && recipient) {
                FloopelButton(s(Res.string.quest_group_join), { model.decideGroupInvitation(true) },
                    Modifier.fillMaxWidth().testTag("quest-group-invitation-accept"), enabled = authoritative && !state.busy)
                FloopelButton(s(Res.string.invitation_decline), { model.decideGroupInvitation(false) },
                    Modifier.fillMaxWidth().testTag("quest-group-invitation-decline"), secondary = true, enabled = authoritative && !state.busy)
            }
            if (invitation.status == "accepted") FloopelButton(s(Res.string.quest_groups), { model.open(TravelerRoute(Destination.QUEST_GROUP, invitation.groupId)) },
                Modifier.fillMaxWidth(), enabled = authoritative && !state.busy)
        }
    }
}
