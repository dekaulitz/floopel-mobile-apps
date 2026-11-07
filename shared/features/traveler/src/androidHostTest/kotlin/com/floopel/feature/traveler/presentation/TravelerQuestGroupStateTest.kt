package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.data.repository.QuestGroupRecoveryFixture
import com.floopel.feature.traveler.testing.TravelerFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import java.util.concurrent.Executors
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Production state owner and adapter with a real recovery store and mock HTTP only. */
@OptIn(ExperimentalCoroutinesApi::class)
class TravelerQuestGroupStateTest {
    private val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val jobs = mutableListOf<Job>()
    @BeforeTest fun before() { Dispatchers.setMain(ui) }
    @AfterTest fun after() {
        runBlocking { withContext(ui) { owners.forEach(ViewModelStore::clear) }; jobs.joinAll() }
        Dispatchers.resetMain(); ui.close()
    }
    private suspend fun act(call: () -> Unit) = withContext(ui) { call() }
    private suspend fun TravelerViewModel.awaitState(test: (TravelerState) -> Boolean) = withTimeout(10_000) { state.first(test) }
    private suspend fun model(f: QuestGroupRecoveryFixture, base: TravelerFixture = TravelerFixture(), restored: String = ""): TravelerViewModel {
        base.identityState.value = base.identityState.value.copy(generation = f.session.identityGeneration)
        val r = base.runtime
        val runtime = TravelerRuntime(r.identity,r.catalog,r.itineraries,r.distribution,r.trips,r.notifications,
            r.account,r.legal,r.registration,r.publicContent,r.savedSpots,f.repository,r.newOperationKey,r.startup)
        return withContext(ui) { TravelerViewModel(runtime,restored).also {
            owners += ViewModelStore().apply { put("vm",it) }; jobs += requireNotNull(it.viewModelScope.coroutineContext[Job])
        } }.also { it.awaitState { s -> s.identityRestored && s.intro?.completed == true } }
    }
    private suspend fun pending(f: QuestGroupRecoveryFixture, vm: TravelerViewModel) {
        act { vm.open(TravelerRoute(Destination.QUEST_GROUPS,f.questId)) }
        vm.awaitState { it.groups.value != null && !it.groups.loading }
        f.loseCreateReply = true; act { vm.createGroup() }
        vm.awaitState { it.route.destination == Destination.QUEST_GROUP_RECOVERY && it.questGroupMutationRecovery.required && !it.busy }
    }
    @Test fun visibleCreatedGroupAndBlockedInviteRecoverWithoutAnAutomaticPost() = runBlocking {
        val f=QuestGroupRecoveryFixture()
        try {
            f.login(); val vm=model(f); pending(f,vm); val record=f.record
            act { vm.back() }; vm.awaitState { it.groups.value?.items?.size==1 && !it.groups.loading }
            act { vm.open(TravelerRoute(Destination.QUEST_GROUP,f.groupId)) }; vm.awaitState { it.group.value!=null && !it.group.loading }
            act { vm.inviteToGroup("guest_b") }; vm.awaitState { it.route.destination==Destination.QUEST_GROUP_RECOVERY && !it.busy }
            assertEquals(1,f.writeKeys.size); assertEquals(record,f.record)
            act { vm.reviewQuestGroupMutation() }; vm.awaitState { it.questGroupMutationRecovery.review.value!=null && !it.busy }
            assertEquals(f.groupId,vm.state.value.questGroupMutationRecovery.review.value?.groups?.single()?.id)
            assertEquals(record,f.record); assertEquals(1,f.writeKeys.size)
            act { vm.startNewQuestGroupAction() }; vm.awaitState { !it.questGroupMutationRecovery.required && !it.busy }
            assertNull(f.record); assertEquals(1,f.writeKeys.size)
            act { vm.inviteToGroup("guest_b") }; vm.awaitState { it.saved && !it.busy }
            assertEquals(2,f.writeKeys.size); assertEquals(2,f.writeKeys.distinct().size); assertEquals(1,f.createdCount)
        } finally { f.client.close() }
    }
    @Test fun sameCreateRetryUsesTheFrozenKeyInsteadOfReturningPreflightReadyAndLeavingTheJournal() = runBlocking {
        val f=QuestGroupRecoveryFixture()
        try {
            f.login(); val vm=model(f); pending(f,vm)
            act { vm.back() }; vm.awaitState { it.groups.value?.items?.size==1 && !it.groups.loading }
            val original=f.writeKeys.single()
            act { vm.createGroup() }; vm.awaitState { it.route.destination==Destination.QUEST_GROUP && !it.busy }
            assertEquals(listOf(original,original),f.writeKeys); assertNull(f.record)
            assertFalse(vm.state.value.questGroupMutationRecovery.required); assertEquals(1,f.createdCount)
        } finally { f.client.close() }
    }
    @Test fun failedAndStaleReviewDoNotReleaseOrReplayAndActorChangeResetsUiPermission() = runBlocking {
        val f=QuestGroupRecoveryFixture()
        try {
            f.login(); val base=TravelerFixture(); val vm=model(f,base); pending(f,vm); val record=f.record
            f.failReads=true; act { vm.reviewQuestGroupMutation() }; vm.awaitState { it.questGroupMutationRecovery.review.failure!=null && !it.busy }
            act { vm.startNewQuestGroupAction() }; assertEquals(record,f.record); assertEquals(1,f.writeKeys.size)
            f.failReads=false; act { vm.reviewQuestGroupMutation() }; vm.awaitState { it.questGroupMutationRecovery.review.value!=null && !it.busy }
            f.record=requireNotNull(record).replace("\"key\":\"","\"key\":\"changed-")
            act { vm.startNewQuestGroupAction() }; vm.awaitState { it.questGroupMutationRecovery.review.failure!=null && !it.busy }
            assertNotNull(f.record); assertEquals(1,f.writeKeys.size)
            base.identityState.value=base.identityState.value.copy(generation=base.identityState.value.generation+1,signedIn=false)
            vm.awaitState { !it.identity.signedIn }; act { vm.startNewQuestGroupAction() }
            assertFalse(vm.state.value.questGroupMutationRecovery.required); assertNotNull(f.record); assertEquals(1,f.writeKeys.size)
        } finally { f.client.close() }
    }
    @Test fun ordinaryServerConflictDoesNotEnableArbitraryJournalRelease() = runBlocking {
        val f=QuestGroupRecoveryFixture()
        try {
            f.login(); val vm=model(f); f.conflictWrite=true
            act { vm.open(TravelerRoute(Destination.QUEST_GROUP,f.groupId)) }; vm.awaitState { it.group.value!=null && !it.group.loading }
            act { vm.inviteToGroup("guest_b") }; vm.awaitState { it.writeFailure?.status==409 && !it.busy }
            assertFalse(vm.state.value.questGroupMutationRecovery.required)
            act { vm.startNewQuestGroupAction() }; assertNotNull(f.record); assertEquals(1,f.writeKeys.size)
        } finally { f.client.close() }
    }
    @Test fun recipientAndManagerAppendInOrderDeduplicateAndKeepPageAndPositionAfterReaderBack() = runBlocking {
        for (manager in listOf(false,true)) {
            val f=QuestGroupRecoveryFixture()
            try {
                f.login(); f.invitationTotal=21; f.overlapPageTwo=true; val vm=model(f)
                val filter=if(manager) f.groupId else ""
                act { vm.open(TravelerRoute(Destination.QUEST_GROUP_INVITATIONS,filter)) }; vm.awaitState { it.groupInvitations.value?.page==1 && !it.groupInvitations.loading }
                act { vm.more() }; vm.awaitState { it.groupInvitations.value?.page==2 && !it.groupInvitationsAppending }
                assertEquals((1..21).map(f::invitationId),vm.state.value.groupInvitations.value?.items?.map { it.id })
                assertEquals(listOf(Triple(filter.ifBlank { null },1,"id"),Triple(filter.ifBlank { null },2,"id")),f.invitationReads)
                act { vm.openGroupInvitation(f.invitationId(21),18,13) }; vm.awaitState { it.groupInvitation.value?.id==f.invitationId(21) && !it.groupInvitation.loading }
                act { vm.back() }
                assertEquals(2,vm.state.value.groupInvitations.value?.page); assertEquals(21,vm.state.value.groupInvitations.value?.items?.size)
                assertEquals(18,vm.state.value.groupInvitationsScrollIndex); assertEquals(13,vm.state.value.groupInvitationsScrollOffset)
                act { vm.more() }; assertEquals(2,f.invitationReads.size); assertEquals(0,f.writeKeys.size)
            } finally { f.client.close() }
        }
    }
    @Test fun appendFailureKeepsItemsAndRetriesTheSameNextPageWithoutWrites() = runBlocking {
        val f=QuestGroupRecoveryFixture()
        try {
            f.login(); f.invitationTotal=21; val vm=model(f)
            act { vm.open(TravelerRoute(Destination.QUEST_GROUP_INVITATIONS)) }; vm.awaitState { it.groupInvitations.value?.page==1 && !it.groupInvitations.loading }
            f.failPageTwo=true; act { vm.more() }; vm.awaitState { it.groupInvitationsAppendFailure!=null && !it.groupInvitationsAppending }
            assertEquals(20,vm.state.value.groupInvitations.value?.items?.size); assertEquals(1,vm.state.value.groupInvitations.value?.page)
            assertNull(vm.state.value.groupInvitations.failure)
            f.failPageTwo=false; act { vm.more() }; vm.awaitState { it.groupInvitations.value?.page==2 && !it.groupInvitationsAppending }
            assertEquals(listOf(1,2,2),f.invitationReads.map { it.second }); assertEquals(0,f.writeKeys.size)
        } finally { f.client.close() }
    }
    @Test fun latePageTwoAfterRouteOrActorChangeIsDiscarded() = runBlocking {
        for (actorChange in listOf(false,true)) {
            val f=QuestGroupRecoveryFixture()
            try {
                f.login(); f.invitationTotal=21; val base=TravelerFixture(); val vm=model(f,base)
                act { vm.open(TravelerRoute(Destination.QUEST_GROUP_INVITATIONS)) }; vm.awaitState { it.groupInvitations.value?.page==1 && !it.groupInvitations.loading }
                val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
                f.beforeInvitations={ filter,page -> if(filter==null && page==2) withContext(NonCancellable) { entered.complete(Unit); release.await() } }
                val parent=requireNotNull(vm.viewModelScope.coroutineContext[Job]); val previous=parent.children.toSet()
                act { vm.more() }; withTimeout(10_000) { entered.await() }
                val pending=parent.children.filter { it !in previous }.toList()
                if(actorChange) {
                    base.identityState.value=base.identityState.value.copy(generation=base.identityState.value.generation+1,signedIn=false)
                    vm.awaitState { !it.identity.signedIn }
                } else {
                    act { vm.open(TravelerRoute(Destination.QUEST_GROUP_INVITATIONS,f.groupId)) }
                    vm.awaitState { it.groupInvitations.value?.page==1 && !it.groupInvitations.loading }
                }
                release.complete(Unit); withTimeout(10_000) { pending.joinAll() }
                assertEquals(if(actorChange) null else 1,vm.state.value.groupInvitations.value?.page)
                assertFalse(vm.state.value.groupInvitationsAppending); assertEquals(0,f.writeKeys.size)
            } finally { f.client.close() }
        }
    }
    @Test fun emptyAndEndListsDoNotRequestAnotherPage() = runBlocking {
        val f=QuestGroupRecoveryFixture()
        try {
            f.login(); val vm=model(f)
            act { vm.open(TravelerRoute(Destination.QUEST_GROUP_INVITATIONS)) }; vm.awaitState { it.groupInvitations.value!=null && !it.groupInvitations.loading }
            assertTrue(vm.state.value.groupInvitations.value?.items.orEmpty().isEmpty())
            act { vm.more() }; assertEquals(listOf(1),f.invitationReads.map { it.second }); assertEquals(0,f.writeKeys.size)
        } finally { f.client.close() }
    }
    @Test fun restoringTheRecoveryRouteStillRequiresAuthorizedReviewAndAnExplicitDecision() = runBlocking {
        val f=QuestGroupRecoveryFixture()
        try {
            f.login(); f.loseCreateReply=true; f.repository.createGroup(f.questId,"id","create-a"); val record=f.record
            val restored=Json.encodeToString(listOf(TravelerRoute(Destination.HOME),TravelerRoute(Destination.QUEST_GROUPS,f.questId),TravelerRoute(Destination.QUEST_GROUP_RECOVERY)))
            val vm=model(f,restored=restored)
            assertTrue(vm.state.value.questGroupMutationRecovery.required)
            act { vm.startNewQuestGroupAction() }; assertEquals(record,f.record)
            act { vm.reviewQuestGroupMutation() }; vm.awaitState { it.questGroupMutationRecovery.review.value!=null && !it.busy }
            act { vm.startNewQuestGroupAction() }; vm.awaitState { !it.questGroupMutationRecovery.required && !it.busy }
            assertNull(f.record); assertEquals(1,f.writeKeys.size)
        } finally { f.client.close() }
    }
    @Test fun aForbiddenAppendDoesNotKeepThePreviouslyAuthorizedPrivateListVisible() = runBlocking {
        val f=QuestGroupRecoveryFixture()
        try {
            f.login(); f.invitationTotal=21; val vm=model(f)
            act { vm.open(TravelerRoute(Destination.QUEST_GROUP_INVITATIONS,f.groupId)) }; vm.awaitState { it.groupInvitations.value?.page==1 && !it.groupInvitations.loading }
            f.forbiddenPageTwo=true; act { vm.more() }; vm.awaitState { it.groupInvitations.failure?.status==403 && !it.groupInvitationsAppending }
            assertNull(vm.state.value.groupInvitations.value); assertEquals(0,f.writeKeys.size)
        } finally { f.client.close() }
    }
}
