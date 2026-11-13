package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.data.repository.QuestGroupRecoveryFixture
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerAccountRepository
import com.floopel.feature.traveler.testing.TravelerFixture
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import java.util.concurrent.Executors
import kotlin.test.*

/** Actual recipient, production adapter/VM and mock PUT; no real invitation decisions. */
@OptIn(ExperimentalCoroutinesApi::class)
class TravelerQuestGroupDecisionStateTest {
    private val ui=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val owners=mutableListOf<ViewModelStore>()
    private val jobs=mutableListOf<Job>()
    @BeforeTest fun before() { Dispatchers.setMain(ui) }
    @AfterTest fun after() {
        runBlocking { withContext(ui) { owners.forEach(ViewModelStore::clear) }; jobs.joinAll() }
        Dispatchers.resetMain(); ui.close()
    }
    private suspend fun act(call:()->Unit)=withContext(ui) { call() }
    private suspend fun TravelerViewModel.awaitState(test:(TravelerState)->Boolean)=withTimeout(10_000) { state.first(test) }
    private suspend fun model(f:QuestGroupRecoveryFixture,base:TravelerFixture=TravelerFixture()):TravelerViewModel {
        f.login(); f.recipient=f.actor; f.inviterProfileId="other-manager"; f.invitationTotal=21
        base.identityState.value=base.identityState.value.copy(generation=f.session.identityGeneration)
        val r=base.runtime
        val account=object:TravelerAccountRepository by r.account {
            override suspend fun profile()=CatalogResult.Ready(PersonalProfile("Fixture","Recipient","fixture_recipient","ID",profileId=f.actor))
        }
        val runtime=TravelerRuntime(r.identity,r.catalog,r.itineraries,r.distribution,r.trips,r.notifications,account,
            r.legal,r.registration,r.publicContent,r.savedSpots,f.repository,r.newOperationKey,r.startup)
        return withContext(ui) { TravelerViewModel(runtime).also {
            owners += ViewModelStore().apply { put("vm",it) }; jobs+=requireNotNull(it.viewModelScope.coroutineContext[Job])
        } }.also { it.awaitState { s -> s.identityRestored && s.intro?.completed==true } }
    }
    private suspend fun reader(f:QuestGroupRecoveryFixture,vm:TravelerViewModel) {
        act { vm.open(TravelerRoute(Destination.QUEST_GROUP_INVITATIONS)) }
        vm.awaitState { it.groupInvitations.value?.page==1 && !it.groupInvitations.loading }
        act { vm.more() }; vm.awaitState { it.groupInvitations.value?.page==2 && !it.groupInvitationsAppending }
        act { vm.openGroupInvitation(f.invitationId(21),18,13) }
        vm.awaitState { it.groupInvitation.value?.id==f.invitationId(21) && !it.groupInvitation.loading && it.profile.value?.profileId==f.recipient && !it.profile.loading }
    }
    private fun assertRetained(vm:TravelerViewModel,status:String) {
        val s=vm.state.value
        assertEquals(2,s.groupInvitations.value?.page); assertEquals(18,s.groupInvitationsScrollIndex); assertEquals(13,s.groupInvitationsScrollOffset)
        assertEquals((1..21).map { "33333333-3333-4333-8333-${it.toString().padStart(12,'0')}" },s.groupInvitations.value?.items?.map { it.id })
        assertEquals(status,s.groupInvitations.value?.items?.last()?.status)
        assertTrue(s.groupInvitations.value?.items?.dropLast(1).orEmpty().all { it.status=="pending" })
    }
    @Test fun acknowledgedDeclineUpdatesOnlyItem21BeforeBackWithoutReloadingPageOne()=runBlocking {
        val f=QuestGroupRecoveryFixture()
        try {
            val vm=model(f); reader(f,vm); act { vm.decideGroupInvitation(false) }
            vm.awaitState { it.route.destination==Destination.QUEST_GROUP_INVITATIONS && !it.busy }
            assertRetained(vm,"declined"); assertEquals(listOf(1,2),f.invitationReads.map { it.second })
            assertEquals(listOf("{\"status\":\"declined\"}"),f.decisionBodies); assertEquals(1,f.writeKeys.size); assertEquals(0,f.groupReads)
        } finally { f.client.close() }
    }
    @Test fun ordinaryReadOnlyBackKeepsThePendingItemAndDoesNotWrite()=runBlocking {
        val f=QuestGroupRecoveryFixture()
        try {
            val vm=model(f); reader(f,vm); act { vm.back() }
            assertRetained(vm,"pending"); assertEquals(listOf(1,2),f.invitationReads.map { it.second }); assertEquals(0,f.writeKeys.size)
        } finally { f.client.close() }
    }
    @Test fun definiteFailureDoesNotInventAnAcknowledgementOrLeaveTheReader()=runBlocking {
        val f=QuestGroupRecoveryFixture()
        try {
            val vm=model(f); reader(f,vm); f.decisionStatus=HttpStatusCode.UnprocessableEntity
            act { vm.decideGroupInvitation(false) }; vm.awaitState { it.writeFailure?.status==422 && !it.busy }
            assertEquals(Destination.QUEST_GROUP_INVITATION,vm.state.value.route.destination)
            assertRetained(vm,"pending"); assertEquals(1,f.writeKeys.size); assertEquals(0,f.groupReads)
        } finally { f.client.close() }
    }
    @Test fun lostDecisionResponseKeepsPendingUntilAnAuthoritativeReadReconcilesIt()=runBlocking {
        val f=QuestGroupRecoveryFixture()
        try {
            val vm=model(f); reader(f,vm); f.loseDecisionReply=true
            act { vm.decideGroupInvitation(false) }; vm.awaitState { it.route.destination==Destination.QUEST_GROUP_RECOVERY && !it.busy }
            assertEquals("Invitation 21",vm.state.value.questGroupMutationRecovery.title)
            assertRetained(vm,"pending"); assertEquals(1,f.writeKeys.size)
            act { vm.back() }; vm.awaitState { it.groupInvitation.value?.status=="declined" && !it.groupInvitation.loading }
            act { vm.back() }; assertRetained(vm,"declined")
            assertEquals(listOf(1,2),f.invitationReads.map { it.second }); assertEquals(1,f.writeKeys.size)
        } finally { f.client.close() }
    }
    @Test fun lateDecisionAfterActorGenerationChangeCannotRepopulateTheOldListOrNavigateBack()=runBlocking {
        val f=QuestGroupRecoveryFixture()
        try {
            val base=TravelerFixture(); val vm=model(f,base); reader(f,vm)
            val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
            f.beforeDecision={ withContext(NonCancellable) { entered.complete(Unit); release.await() } }
            val parent=requireNotNull(vm.viewModelScope.coroutineContext[Job]); val before=parent.children.toSet()
            act { vm.decideGroupInvitation(false) }; withTimeout(10_000) { entered.await() }
            val pending=parent.children.filter { it !in before }.toList()
            base.identityState.value=base.identityState.value.copy(generation=base.identityState.value.generation+1,signedIn=false)
            vm.awaitState { !it.identity.signedIn }; release.complete(Unit); withTimeout(10_000) { pending.joinAll() }
            assertNull(vm.state.value.groupInvitations.value); assertEquals(Destination.LOGIN,vm.state.value.route.destination); assertEquals(1,f.writeKeys.size)
        } finally { f.client.close() }
    }
}
