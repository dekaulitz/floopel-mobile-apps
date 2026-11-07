package com.floopel.feature.traveler.presentation.quest

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.floopel.core.designsystem.component.FloopelButton
import com.floopel.core.designsystem.component.FloopelFormTextField
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect

@Composable
internal fun QuestGroupList(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FloopelButton(s(Res.string.quest_group_invitations), { model.open(TravelerRoute(Destination.QUEST_GROUP_INVITATIONS)) }, Modifier.fillMaxWidth(), secondary = true)
        InfoCard(s(Res.string.quest_group_note))
        ReadContent(state.groups, model::refresh) { batch ->
            if (batch.items.isEmpty()) InfoCard(s(Res.string.quest_group_empty))
            batch.items.forEach { group -> ActionRow(group.questTitle ?: s(Res.string.quest_groups), "${group.memberCount} / ${group.sizeLimit}", {
                model.open(TravelerRoute(Destination.QUEST_GROUP, group.id))
            }) }
            if (state.route.id.isNotBlank() && batch.items.isEmpty()) FloopelButton(s(Res.string.quest_group_create), model::createGroup, Modifier.fillMaxWidth(), enabled = !state.busy)
            if (batch.page < batch.totalPages) FloopelButton(s(Res.string.more), model::more, Modifier.fillMaxWidth(), secondary = true, enabled = !state.groups.loading)
        }
    }
}

@Composable
internal fun QuestGroupReader(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReadContent(state.group, model::refresh) { group ->
            Text(group.questTitle ?: s(Res.string.quest_groups), style = MaterialTheme.typography.headlineMedium)
            Body("${group.memberCount} / ${group.sizeLimit}")
            InfoCard(s(Res.string.quest_group_note))
            val manager = state.profile.value?.profileId == group.managerProfileId
            val identityKnown = state.profile.value?.profileId != null && !state.profile.loading && state.profile.failure == null
            if (manager && group.available) {
                var username by remember { mutableStateOf("") }
                FloopelFormTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text(s(Res.string.username)) }, singleLine = true)
                FloopelButton(s(Res.string.quest_group_invite), { model.inviteToGroup(username) }, Modifier.fillMaxWidth(), enabled = !state.busy && username.isNotBlank() && group.memberCount < group.sizeLimit)
                FloopelButton(s(Res.string.quest_group_invitations), { model.open(TravelerRoute(Destination.QUEST_GROUP_INVITATIONS, group.id)) }, Modifier.fillMaxWidth(), secondary = true)
            }
            SectionTitle(s(Res.string.quest_group_members))
            ReadContent(state.groupMembers, model::refresh) { members ->
                members.items.forEach { member ->
                    Text(member.displayName, style = MaterialTheme.typography.titleMedium)
                    if (member.manager) Body(s(Res.string.quest_group_manager))
                    member.progress?.let { Body(s(Res.string.quest_progress_count, it.completed, it.total)) }
                    if (manager && !member.manager && group.available) {
                        var transfer by remember { mutableStateOf(false) }
                        TextButton({ transfer = true }) { Text(s(Res.string.quest_group_transfer)) }
                        if (transfer) AlertDialog({ transfer = false }, title = { Text(s(Res.string.confirm)) }, text = { Text(member.displayName) },
                            confirmButton = { TextButton({ model.transferGroup(member.profileId); transfer = false }) { Text(s(Res.string.confirm)) } },
                            dismissButton = { TextButton({ transfer = false }) { Text(s(Res.string.cancel)) } })
                    }
                }
            }
            group.questSlug?.let { slug -> FloopelButton(s(Res.string.quest_detail), { model.open(TravelerRoute(Destination.QUEST, slug)) }, Modifier.fillMaxWidth()) }
            if (group.available && identityKnown) {
                var leave by remember { mutableStateOf(false) }
                FloopelButton(s(if (manager) Res.string.quest_group_disband else Res.string.quest_group_leave), { leave = true }, Modifier.fillMaxWidth(), secondary = true)
                if (leave) AlertDialog({ leave = false }, title = { Text(s(Res.string.confirm)) }, text = { Text(s(Res.string.confirm_body)) },
                    confirmButton = { TextButton({ model.leaveGroup(manager); leave = false }) { Text(s(Res.string.confirm)) } },
                    dismissButton = { TextButton({ leave = false }) { Text(s(Res.string.cancel)) } })
            }
            if (state.saved) InfoCard(s(Res.string.saved))
        }
    }
}

@Composable
internal fun QuestGroupInvitations(state: TravelerState, model: TravelerViewModel) {
    val list = rememberLazyListState(state.groupInvitationsScrollIndex, state.groupInvitationsScrollOffset)
    val batch = state.groupInvitations.value
    LaunchedEffect(batch?.page, batch?.totalPages, state.groupInvitations.loading, state.groupInvitationsAppending, state.groupInvitationsAppendFailure) {
        if (batch != null && batch.page < batch.totalPages && !state.groupInvitations.loading &&
            !state.groupInvitationsAppending && state.groupInvitationsAppendFailure == null) {
            snapshotFlow { list.layoutInfo.visibleItemsInfo.any { it.key == "next-page" } }.distinctUntilChanged().collect { visible ->
                if (visible) model.more()
            }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("quest-group-invitations-list"), state = list,
        contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { InfoCard(s(Res.string.quest_group_note)) }
        item { ReadContent(state.groupInvitations, model::refresh) { if (it.items.isEmpty()) EmptyContent() } }
        items(batch?.items.orEmpty(), key = { it.id }) { invitation ->
            Box(Modifier.testTag("quest-group-invitation-${invitation.id}")) {
                ActionRow(invitation.questTitle ?: s(Res.string.quest_groups), groupInvitationStatus(invitation.status), {
                    model.openGroupInvitation(invitation.id, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
                })
            }
        }
        if (batch != null && batch.page < batch.totalPages) item(key = "next-page") {
            Box(Modifier.fillMaxWidth().testTag("quest-group-invitations-next")) {
                state.groupInvitationsAppendFailure?.let { ErrorPanel(it, model::more) }
                    ?: LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
internal fun QuestGroupInvitationReader(state: TravelerState, model: TravelerViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ReadContent(state.groupInvitation, model::refresh) { invitation ->
            Text(invitation.questTitle ?: s(Res.string.quest_groups), style = MaterialTheme.typography.headlineMedium)
            Body(groupInvitationStatus(invitation.status))
            InfoCard(s(Res.string.quest_group_note))
            if (invitation.status == "pending" && state.profile.value?.profileId == invitation.profileId) {
                FloopelButton(s(Res.string.quest_group_join), { model.decideGroupInvitation(true) }, Modifier.fillMaxWidth(), enabled = !state.busy)
                FloopelButton(s(Res.string.invitation_decline), { model.decideGroupInvitation(false) }, Modifier.fillMaxWidth(), secondary = true, enabled = !state.busy)
            }
            if (invitation.status == "accepted") FloopelButton(s(Res.string.quest_groups), { model.open(TravelerRoute(Destination.QUEST_GROUP, invitation.groupId)) }, Modifier.fillMaxWidth())
        }
    }
}
