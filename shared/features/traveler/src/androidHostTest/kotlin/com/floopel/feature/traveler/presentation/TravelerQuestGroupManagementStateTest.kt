package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.data.repository.QuestGroupRecoveryFixture
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerAccountRepository
import com.floopel.feature.traveler.testing.TravelerFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import java.util.concurrent.Executors
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TravelerQuestGroupManagementStateTest {
    private val ui=Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val stores=mutableListOf<ViewModelStore>()
    private val jobs=mutableListOf<Job>()
    private var profileFails=false
    @BeforeTest fun before() { Dispatchers.setMain(ui) }
    @AfterTest fun after() {
        runBlocking { withContext(ui) { stores.forEach(ViewModelStore::clear) }; jobs.joinAll() }
        Dispatchers.resetMain(); ui.close()
    }
    private suspend fun act(call:()->Unit)=withContext(ui) {call()}
    private suspend fun TravelerViewModel.awaitState(test:(TravelerState)->Boolean)=withTimeout(10_000) {state.first(test)}
    private suspend fun model(f:QuestGroupRecoveryFixture):TravelerViewModel {
        f.login(); val base=TravelerFixture(); base.identityState.value=base.identityState.value.copy(generation=f.session.identityGeneration)
        val r=base.runtime
        val account=object:TravelerAccountRepository by r.account {
            override suspend fun profile():CatalogResult<PersonalProfile> = if(profileFails) CatalogResult.NetworkError else
                CatalogResult.Ready(PersonalProfile("Fixture","Manager","fixture_manager","ID",profileId=f.actor))
        }
        val runtime=TravelerRuntime(r.identity,r.catalog,r.itineraries,r.distribution,r.trips,r.notifications,account,
            r.legal,r.registration,r.publicContent,r.savedSpots,f.repository,r.newOperationKey,r.startup)
        return withContext(ui) {TravelerViewModel(runtime).also {
            stores+=ViewModelStore().apply {put("vm",it)}; jobs+=requireNotNull(it.viewModelScope.coroutineContext[Job])
        }}.also {it.awaitState {s->s.identityRestored && s.intro?.completed==true}}
    }
    private fun overview(f:QuestGroupRecoveryFixture)=TravelerRoute(Destination.QUEST_GROUP,f.groupId,extra="origin-context",slug="quest-fixture",countryId="origin-country",locationContextId="origin-location")
    private suspend fun ready(vm:TravelerViewModel) {vm.awaitState {it.group.value!=null && !it.group.loading && !it.profile.loading && it.profile.value?.profileId!=null}}
    @Test fun managerModePreservesGroupOriginDraftAndBackWithoutWrites()=runBlocking {
        val f=QuestGroupRecoveryFixture()
        try {
            val vm=model(f); val source=overview(f); act {vm.open(source)}; ready(vm)
            act {vm.manageQuestGroup()}; ready(vm)
            assertTrue(vm.state.value.route.isQuestGroupManagement); assertEquals(source,vm.state.value.stack.dropLast(1).last())
            assertEquals(source.id,vm.state.value.route.id); assertEquals(source.slug,vm.state.value.route.slug)
            assertEquals(source.countryId,vm.state.value.route.countryId); assertEquals(source.locationContextId,vm.state.value.route.locationContextId)
            act {vm.groupInviteUsername("guest_b")}; act {vm.open(TravelerRoute(Destination.QUEST_GROUP_INVITATIONS,f.groupId))}
            vm.awaitState {it.groupInvitations.value!=null && !it.groupInvitations.loading}
            act {vm.back()}; ready(vm); assertEquals("guest_b",vm.state.value.groupManagementDraft.username)
            act {vm.back()}; ready(vm); assertEquals(source,vm.state.value.route); assertEquals(0,f.writeKeys.size)
        } finally {f.client.close()}
    }
    @Test fun memberUnavailableOrFailedProfileCannotEnterManagement()=runBlocking {
        val f=QuestGroupRecoveryFixture()
        try {
            val vm=model(f); val source=overview(f)
            f.managerProfileId="other-manager"; act {vm.open(source)}; ready(vm)
            act {vm.manageQuestGroup()}; assertEquals(source,vm.state.value.route)
            f.managerProfileId=null; f.groupAvailable=false; act {vm.refresh()}; vm.awaitState {it.group.value?.available==false && !it.group.loading}
            act {vm.manageQuestGroup()}; assertEquals(source,vm.state.value.route)
            f.groupAvailable=true; profileFails=true; act {vm.refresh()}; vm.awaitState {it.profile.failure!=null && !it.profile.loading && !it.group.loading}
            act {vm.manageQuestGroup()}; assertEquals(source,vm.state.value.route); assertEquals(0,f.writeKeys.size)
        } finally {f.client.close()}
    }
    @Test fun unknownInviteKeepsItsJournalAndManagementDraftUntilAnExplicitRecovery()=runBlocking {
        val f=QuestGroupRecoveryFixture()
        try {
            val vm=model(f); act {vm.open(overview(f))}; ready(vm); act {vm.manageQuestGroup()}; ready(vm)
            act {vm.groupInviteUsername("guest_b")}; f.loseInviteReply=true; act {vm.inviteToGroup("guest_b")}
            vm.awaitState {it.route.destination==Destination.QUEST_GROUP_RECOVERY && !it.busy}
            val record=f.record; assertNotNull(record); assertEquals(1,f.writeKeys.size)
            act {vm.back()}; ready(vm); assertTrue(vm.state.value.route.isQuestGroupManagement)
            assertEquals(f.groupId,vm.state.value.route.id); assertEquals("guest_b",vm.state.value.groupManagementDraft.username)
            assertEquals(record,f.record); assertEquals(1,f.writeKeys.size)
        } finally {f.client.close()}
    }
    @Test fun acknowledgedDisbandExitsBothGroupSurfacesToTheExistingOrigin()=runBlocking {
        val f=QuestGroupRecoveryFixture()
        try {
            val vm=model(f)
            act {vm.open(TravelerRoute(Destination.QUEST_GROUPS,f.questId))}; vm.awaitState {it.groups.value!=null && !it.groups.loading}
            val origin=vm.state.value.route; act {vm.open(overview(f))}; ready(vm); act {vm.manageQuestGroup()}; ready(vm)
            act {vm.leaveGroup(true)}; vm.awaitState {it.route==origin && !it.busy}
            assertEquals(1,f.writeKeys.size); assertTrue(vm.state.value.stack.none {it.destination==Destination.QUEST_GROUP && it.id==f.groupId})
        } finally {f.client.close()}
    }
}
