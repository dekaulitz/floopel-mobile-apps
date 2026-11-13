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
