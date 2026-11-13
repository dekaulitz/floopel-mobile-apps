package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.data.repository.ItineraryFixture
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerItineraryRepository
import com.floopel.feature.traveler.testing.TravelerFixture
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import java.util.concurrent.Executors
import kotlin.test.*

/** Serial UI dispatcher, production adapter and fake HTTP/clock; no device clock or real writes. */
@OptIn(ExperimentalCoroutinesApi::class)
class TravelerItineraryMutationRecoveryTest {
    private val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val jobs = mutableListOf<Job>()
    @BeforeTest fun before() { Dispatchers.setMain(ui) }
    @AfterTest fun after() {
        runBlocking { withContext(ui) { owners.forEach(ViewModelStore::clear) }; jobs.joinAll() }
        Dispatchers.resetMain(); ui.close()
    }
    private suspend fun act(call: () -> Unit) = withContext(ui) { call() }
    private suspend fun TravelerViewModel.awaitState(predicate: (TravelerState) -> Boolean) = withTimeout(10_000) { state.first(predicate) }
    private suspend fun model(f: ItineraryFixture, base: TravelerFixture = TravelerFixture()): TravelerViewModel {
        base.identityState.value = base.identityState.value.copy(generation = f.session.identityGeneration)
        val r = base.runtime
        val runtime = TravelerRuntime(r.identity, r.catalog, f.repository, r.distribution, r.trips, r.notifications,
            r.account, r.legal, r.registration, r.publicContent, r.savedSpots, r.questGroups, r.newOperationKey, r.startup)
        return withContext(ui) {
            TravelerViewModel(runtime).also {
                owners += ViewModelStore().apply { put("vm", it) }; jobs += requireNotNull(it.viewModelScope.coroutineContext[Job])
            }
        }.also { it.awaitState { s -> s.identityRestored && s.intro?.completed == true } }
    }
    private suspend fun expired(f: ItineraryFixture, vm: TravelerViewModel, base: TravelerFixture) {
        f.acceptedCloneResponseLost = true
        act { vm.clonePublicPlan(base.publicPlan.copy(id = "source-a")) }
        vm.awaitState { it.itineraryMutationRecovery.required && !it.busy }
        f.now += 86_400_001L
        act { vm.clonePublicPlan(base.publicPlan.copy(id = "source-a")) }
        vm.awaitState { it.writeFailure?.code == TravelerItineraryRepository.EXPIRED_MUTATION_CODE && !it.busy }
    }
    @Test fun expiredAAndBlockedBRecoverThroughReviewAndExplicitDecisionBeforeExactlyOneNewWrite() = runBlocking {
        val f = ItineraryFixture()
        try {
            f.login(); val base = TravelerFixture(); val vm = model(f, base); expired(f, vm, base)
            val record = f.record
            act { vm.open(TravelerRoute(Destination.ITINERARY_FORM)) }; vm.awaitState { !it.itineraryDraft.loading && it.itineraryDraft.value == null }
            act { vm.saveItinerary(ItineraryDraft("Plan B", "")) }
            vm.awaitState { it.writeFailure?.code == TravelerItineraryRepository.PENDING_MUTATION_CODE && !it.busy }
            assertEquals(record, f.record); assertEquals(1, f.postPaths.size); assertEquals(1, f.generatedKeys)
            act { vm.reviewItineraryMutation() }; vm.awaitState { it.itineraryMutationRecovery.review.value != null && !it.busy }
            assertTrue(vm.state.value.itineraryMutationRecovery.review.value.orEmpty().any { it.id == "copy-a" })
            assertEquals(record, f.record); assertEquals(1, f.postPaths.size)
            act { vm.startNewItineraryAction() }; vm.awaitState { !it.itineraryMutationRecovery.required && !it.busy }
            assertNull(f.record); assertEquals(1, f.postPaths.size); f.freshKey()
            act { vm.saveItinerary(ItineraryDraft("Plan B", "")) }
            vm.awaitState { it.route.destination == Destination.ITINERARY && it.route.id == "plan-b" && !it.busy }
            assertEquals(2, f.postPaths.size); assertEquals(2, f.generatedKeys); assertEquals(2, f.keys.distinct().size)
        } finally { f.client.close() }
    }
    @Test fun failedReviewAndChangedRecordCannotClearOrReplay() = runBlocking {
        val f = ItineraryFixture()
        try {
            f.login(); val base = TravelerFixture(); val vm = model(f, base); expired(f, vm, base); val record = f.record
            f.networkFailure = true; act { vm.reviewItineraryMutation() }
            vm.awaitState { it.itineraryMutationRecovery.review.failure != null && !it.busy }
            act { vm.startNewItineraryAction() }; assertEquals(record, f.record); assertEquals(1, f.postPaths.size)
            f.networkFailure = false; act { vm.reviewItineraryMutation() }
            vm.awaitState { it.itineraryMutationRecovery.review.value != null && !it.busy }
            f.record = requireNotNull(record).replace(f.key, f.key.replace("0001", "0999"))
            act { vm.startNewItineraryAction() }; vm.awaitState { it.itineraryMutationRecovery.review.failure != null && !it.busy }
            assertNotNull(f.record); assertTrue(vm.state.value.itineraryMutationRecovery.required); assertEquals(1, f.postPaths.size)
        } finally { f.client.close() }
    }
    @Test fun actorChangeRemovesUiReleasePermissionWithoutClearingTheRecord() = runBlocking {
        val f = ItineraryFixture()
        try {
            f.login(); val base = TravelerFixture(); val vm = model(f, base); expired(f, vm, base)
            act { vm.reviewItineraryMutation() }; vm.awaitState { it.itineraryMutationRecovery.review.value != null && !it.busy }
            val record = f.record
            base.identityState.value = base.identityState.value.copy(generation = base.identityState.value.generation + 1, signedIn = false)
            vm.awaitState { !it.identity.signedIn }; act { vm.startNewItineraryAction() }
            assertFalse(vm.state.value.itineraryMutationRecovery.required); assertEquals(record, f.record); assertEquals(1, f.postPaths.size)
        } finally { f.client.close() }
    }
    @Test fun metadataEditStaysIndependentAndItsValidationFailureKeepsItsOwnUiOwner() = runBlocking {
        val f = ItineraryFixture()
        try {
            f.login(); val base = TravelerFixture(); val vm = model(f, base); expired(f, vm, base); val record = f.record
            act { vm.open(TravelerRoute(Destination.ITINERARY_FORM, "plan")) }; vm.awaitState { it.itinerary.value != null && !it.itinerary.loading }
            act { vm.saveItinerary(ItineraryDraft("Updated metadata", "")) }; vm.awaitState { !it.busy && it.route.destination == Destination.ITINERARY }
            assertEquals(record, f.record); assertTrue(vm.state.value.itineraryMutationRecovery.required); assertEquals(1, f.postPaths.size)
            f.status = HttpStatusCode.UnprocessableEntity
            act { vm.saveItinerary(ItineraryDraft("Invalid metadata", "")) }; vm.awaitState { it.writeFailure != null && !it.busy }
            assertEquals(422, vm.state.value.writeFailure?.status); assertNull(vm.state.value.writeFailureJournal)
            assertEquals(record, f.record); assertEquals(1, f.postPaths.size)
            f.status = HttpStatusCode.OK
            act { vm.reviewItineraryMutation() }; vm.awaitState { it.itineraryMutationRecovery.review.value != null && !it.busy }
            act { vm.startNewItineraryAction() }; vm.awaitState { !it.itineraryMutationRecovery.required && !it.busy }
            assertNull(f.record); assertEquals(422, vm.state.value.writeFailure?.status)
            assertNull(vm.state.value.writeFailureJournal); assertEquals(1, f.postPaths.size)
        } finally { f.client.close() }
    }
}
