package com.floopel.feature.traveler.presentation.quest

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.floopel.core.designsystem.component.FloopelButton
import com.floopel.core.designsystem.component.FloopelFormTextField
import com.floopel.core.designsystem.theme.FloopelShape
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.*
import com.floopel.feature.traveler.resources.*

@Composable private fun GroupBody(text:String,modifier:Modifier=Modifier) = Text(text,modifier,
    style=MaterialTheme.typography.bodySmall.copy(fontSize=13.sp,lineHeight=17.55.sp),color=MaterialTheme.colorScheme.onSurfaceVariant)

@Composable private fun GroupContext(state:TravelerState,group:QuestGroup) {
    Text(group.questTitle ?: s(Res.string.quest_groups),Modifier.fillMaxWidth().testTag("quest-group-title").semantics { heading() },
        style=MaterialTheme.typography.titleLarge.copy(fontSize=20.sp,lineHeight=27.sp,fontWeight=FontWeight.Bold))
    val locations=state.quest.value?.takeIf { group.available && it.card.id==group.questId && !state.quest.loading && state.quest.failure==null }
        ?.card?.locations?.map { it.name }?.distinct().orEmpty()
    Text((listOf(s(Res.string.quest_group_kind))+locations).joinToString(" · "),Modifier.fillMaxWidth().testTag("quest-group-context"),
        style=MaterialTheme.typography.bodySmall.copy(fontSize=13.sp,lineHeight=17.55.sp,fontWeight=FontWeight.Medium),color=MaterialTheme.colorScheme.primary)
}

/** Figma 397:8120/8222; the group projection supplies count/capacity and the roster supplies progress. */
@Composable internal fun QuestGroupReader(state:TravelerState,model:TravelerViewModel) {
    val known=state.profile.value?.profileId?.takeIf { !state.profile.loading && state.profile.failure==null }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,top=20.dp,bottom=28.dp)
        .testTag("quest-group-overview"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        ReadContent(state.group,model::refresh) { group ->
            GroupContext(state,group)
            Text(s(Res.string.quest_group_count,group.memberCount,group.sizeLimit),Modifier.testTag("quest-group-count"),
                style=MaterialTheme.typography.bodyMedium.copy(fontSize=14.sp,lineHeight=18.9.sp,fontWeight=FontWeight.Medium),color=MaterialTheme.colorScheme.primary)
            val manager=known==group.managerProfileId
            val managerName=state.groupMembers.value?.items?.firstOrNull { it.profileId==group.managerProfileId }?.displayName
                ?.takeIf { !state.groupMembers.loading && state.groupMembers.failure==null }
            GroupBody(when {
                !group.available -> s(Res.string.quest_group_unavailable)
                manager -> s(Res.string.quest_group_manager_note)
                managerName!=null -> s(Res.string.quest_group_member_note_named,managerName)
                else -> s(Res.string.quest_group_member_note)
            })
            if(state.profile.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.profile.failure?.let { ErrorPanel(it,model::refresh) }
            ReadContent(state.groupMembers,model::refresh) { members ->
                if(members.items.isEmpty()) GroupBody(s(Res.string.quest_group_members_unavailable))
                members.items.forEach { member ->
                    Surface(Modifier.fillMaxWidth().heightIn(min=84.dp).testTag("quest-group-member-${member.id}")
                        .semantics(mergeDescendants=true) {},shape=FloopelShape.card,color=MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            val label=listOfNotNull(member.displayName,s(if(member.manager) Res.string.quest_group_manager_role else Res.string.quest_group_member_role),
                                s(Res.string.quest_group_you).takeIf { member.profileId==known }).joinToString(" · ")
                            Text(label,style=MaterialTheme.typography.titleMedium.copy(fontSize=16.sp,lineHeight=21.6.sp))
                            val progress=member.progress
                            GroupBody(when {
                                progress==null -> s(Res.string.quest_progress_unavailable)
                                progress.status=="not_started" -> s(Res.string.quest_group_not_started)
                                progress.status=="expired" -> s(Res.string.quest_group_progress_expired)
                                else -> s(Res.string.quest_group_member_progress,progress.completed,progress.total)
                            })
                        }
                    }
                }
            }
            // Keep the existing authorized Quest reader entry; roster summaries contain no participation ID.
            group.questSlug?.let { slug -> FloopelButton(s(Res.string.quest_detail),{model.open(TravelerRoute(Destination.QUEST,slug))},
                Modifier.fillMaxWidth(),enabled=!state.busy && !state.group.loading && state.group.failure==null) }
            if(state.managesCurrentQuestGroup) FloopelButton(s(Res.string.quest_group_manage),model::manageQuestGroup,
                Modifier.fillMaxWidth().testTag("quest-group-manage"),secondary=true,enabled=!state.busy)
            else if(group.available && known!=null && !manager && !state.group.loading && state.group.failure==null) {
                var leave by rememberSaveable(group.id,state.identity.generation) { mutableStateOf(false) }
                FloopelButton(s(Res.string.quest_group_leave),{leave=true},Modifier.fillMaxWidth().testTag("quest-group-leave"),secondary=true,enabled=!state.busy)
                if(leave) AlertDialog({leave=false},title={Text(s(Res.string.confirm))},text={Text(s(Res.string.confirm_body))},
                    confirmButton={TextButton({model.leaveGroup(false);leave=false},enabled=!state.busy){Text(s(Res.string.confirm))}},
                    dismissButton={TextButton({leave=false}){Text(s(Res.string.cancel))}})
            }
        }
    }
}

/** Figma 397:8429. A local mode shares the same group route, repository and mutation journal. */
@Composable internal fun QuestGroupManagementScreen(state:TravelerState,model:TravelerViewModel) {
    var transfer by rememberSaveable(state.route.id,state.identity.generation) { mutableStateOf(false) }
    var selected by rememberSaveable(state.route.id,state.identity.generation) { mutableStateOf<String?>(null) }
    var disband by rememberSaveable(state.route.id,state.identity.generation) { mutableStateOf(false) }
    LaunchedEffect(state.managesCurrentQuestGroup) {
        if(!state.managesCurrentQuestGroup) { transfer=false;selected=null;disband=false }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start=20.dp,end=20.dp,top=20.dp,bottom=28.dp)
        .testTag("quest-group-management"),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        ReadContent(state.group,model::refresh) { group ->
            GroupContext(state,group)
            GroupBody(s(Res.string.quest_group_manage_note))
            if(state.profile.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.profile.failure?.let { ErrorPanel(it,model::refresh) }
            if(state.managesCurrentQuestGroup) {
                val username=state.groupManagementDraft.takeIf { it.groupId==group.id }?.username.orEmpty()
                val valid=username.matches(Regex("[a-zA-Z0-9_]{3,20}"))
                FloopelFormTextField(username,model::groupInviteUsername,Modifier.fillMaxWidth().testTag("quest-group-username"),
                    label={Text(s(Res.string.quest_group_receiver_username))},singleLine=true,enabled=!state.busy,isError=username.isNotEmpty() && !valid)
                GroupBody(s(Res.string.quest_group_username_help))
                FloopelButton(s(Res.string.quest_group_invite),{model.inviteToGroup(username)},Modifier.fillMaxWidth().testTag("quest-group-invite"),
                    secondary=true,enabled=!state.busy && valid && group.memberCount<group.sizeLimit)
                FloopelButton(s(Res.string.quest_group_sent_invitations),{model.open(TravelerRoute(Destination.QUEST_GROUP_INVITATIONS,group.id))},
                    Modifier.fillMaxWidth().testTag("quest-group-sent-invitations"),secondary=true,enabled=!state.busy)
                val candidates=state.groupMembers.value?.items.orEmpty().filter { !it.manager && it.profileId!=group.managerProfileId }
                val rosterReady=!state.groupMembers.loading && state.groupMembers.failure==null && state.groupMembers.value!=null
                state.groupMembers.failure?.let { ErrorPanel(it,model::refresh) }
                FloopelButton(s(Res.string.quest_group_transfer_action),{transfer=true},Modifier.fillMaxWidth().testTag("quest-group-transfer"),
                    secondary=true,enabled=!state.busy && rosterReady)
                FloopelButton(s(Res.string.quest_group_disband),{disband=true},Modifier.fillMaxWidth().testTag("quest-group-disband"),secondary=true,enabled=!state.busy)
                if(state.saved) InfoCard(s(Res.string.saved))
                if(transfer) AlertDialog({transfer=false;model.back()},title={Text(s(Res.string.quest_group_transfer_action))},
                    text={Column(Modifier.verticalScroll(rememberScrollState())){
                        if(candidates.isEmpty()) GroupBody(s(Res.string.quest_group_transfer_empty))
                        candidates.forEach { member ->
                        TextButton({selected=member.profileId;transfer=false},enabled=!state.busy){Text(member.displayName)}
                    }}},confirmButton={},dismissButton={TextButton({transfer=false;model.back()}){Text(s(Res.string.cancel))}})
                val target=candidates.firstOrNull { it.profileId==selected }
                if(target!=null) AlertDialog({selected=null;model.back()},title={Text(s(Res.string.confirm))},text={Text(target.displayName)},
                    confirmButton={TextButton({model.transferGroup(target.profileId);selected=null},enabled=!state.busy && rosterReady){Text(s(Res.string.confirm))}},
                    dismissButton={TextButton({selected=null;model.back()}){Text(s(Res.string.cancel))}})
                if(disband) AlertDialog({disband=false;model.back()},title={Text(s(Res.string.confirm))},text={Text(s(Res.string.confirm_body))},
                    confirmButton={TextButton({model.leaveGroup(true);disband=false},enabled=!state.busy){Text(s(Res.string.confirm))}},
                    dismissButton={TextButton({disband=false;model.back()}){Text(s(Res.string.cancel))}})
            } else if(!state.group.loading && !state.profile.loading) {
                GroupBody(s(Res.string.quest_group_manage_unavailable))
                FloopelButton(s(Res.string.back),model::back,Modifier.fillMaxWidth(),secondary=true,enabled=!state.busy)
            }
        }
    }
}
