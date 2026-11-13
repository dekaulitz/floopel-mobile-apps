package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.data.repository.TripFixture
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.testing.TravelerFixture
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Production adapter + mock HTTP + production state owner; no real cancellation. */
@OptIn(ExperimentalCoroutinesApi::class)
class TravelerTripMutationRecoveryTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val jobs = mutableListOf<Job>()
    @BeforeTest fun before() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun after() {
        runBlocking { owners.forEach(ViewModelStore::clear); jobs.joinAll() }
        Dispatchers.resetMain()
    }
    private suspend fun TravelerViewModel.awaitState(predicate: (TravelerState) -> Boolean): TravelerState =
        withContext(Dispatchers.Default) { withTimeout(10_000) { state.first(predicate) } }
    private suspend fun model(f: TripFixture, base: TravelerFixture = TravelerFixture()): TravelerViewModel {
        base.identityState.value = base.identityState.value.copy(generation = f.session.identityGeneration)
        val r = base.runtime
        val runtime = TravelerRuntime(r.identity, r.catalog, r.itineraries, r.distribution, f.repository, r.notifications,
            r.account, r.legal, r.registration, r.publicContent, r.savedSpots, r.questGroups, r.newOperationKey, r.startup)
        return TravelerViewModel(runtime, worker = dispatcher).also {
            owners += ViewModelStore().apply { put("vm", it) }
            jobs += requireNotNull(it.viewModelScope.coroutineContext[Job])
            it.awaitState { s -> s.identityRestored && s.intro?.completed == true }
        }
    }
    private suspend fun TravelerViewModel.cancelAndWait() {
        open(TravelerRoute(Destination.TRIP, "trip")); awaitState { it.trip.value != null && !it.trip.loading }
        lifecycle(TripLifecycleAction.CANCEL)
        awaitState { it.tripMutationRecovery.required && !it.busy && it.trip.value?.status == TripStatus.CANCELLED }
    }
    @Test fun terminalCancelCanRecoverInSessionAndCreateTheNextEligibleTripExactlyOnce() = runTest {
        val f = TripFixture()
        try {
            f.login(); f.cancelAcceptedLost = true
            val base = TravelerFixture(); base.plan = base.plan.copy(itinerary = base.plan.itinerary.copy(id = "plan-b"))
            val vm = model(f, base); vm.cancelAndWait(); val old = f.recovery.record
            vm.refresh(); vm.awaitState { !it.trip.loading }; assertEquals(old, f.recovery.record); assertEquals(1, f.writes)
            vm.open(TravelerRoute(Destination.TRIP_FORM, "plan-b")); vm.awaitState { it.tripRecovery.value != null && !it.tripRecovery.loading }
            val routeKey = vm.state.value.route.operationKey
            val draft = TripDraft("Next Trip", "2026-12-01", "2026-12-02", "Asia/Jakarta")
            vm.createTrip(draft); vm.awaitState { !it.busy && it.writeFailure != null }
            assertEquals(com.floopel.feature.traveler.domain.repository.TravelerTripRepository.PENDING_MUTATION_CODE, vm.state.value.writeFailure?.code)
            assertEquals(old, f.recovery.record); assertEquals(1, f.writes)
            vm.reviewTripMutation(); vm.awaitState { it.tripMutationRecovery.review.value != null && !it.busy }
            assertEquals(old, f.recovery.record); assertEquals(1, f.writes)
            vm.startNewTripAction(); vm.awaitState { !it.tripMutationRecovery.required && !it.busy }
            assertNull(f.recovery.record); assertEquals(1, f.writes)
            vm.awaitState { it.tripRecovery.value != null && !it.tripRecovery.loading }
            assertEquals(routeKey, vm.state.value.route.operationKey)
            vm.createTrip(draft)
            vm.awaitState { it.route.destination == Destination.TRIP && it.route.id == "trip-b" && !it.busy }
            assertEquals(2, f.writes); assertEquals(2, f.keys.distinct().size)
        } finally { f.client.close() }
    }
    @Test fun failedReviewAndStaleJournalKeepTheRecoveryVisibleWithoutReplay() = runTest {
        val f = TripFixture()
        try {
            f.login(); f.cancelAcceptedLost = true; val vm = model(f); vm.cancelAndWait(); val old = requireNotNull(f.recovery.record)
            f.networkFailure = true; vm.reviewTripMutation(); vm.awaitState { it.tripMutationRecovery.review.failure != null && !it.busy }
            vm.startNewTripAction(); assertEquals(old, f.recovery.record); assertEquals(1, f.writes)
            f.networkFailure = false; vm.reviewTripMutation(); vm.awaitState { it.tripMutationRecovery.review.value != null && !it.busy }
            f.recovery.record = old.replace(f.keys.first(), "00000000-0000-4000-8000-000000000999")
            vm.startNewTripAction(); vm.awaitState { it.tripMutationRecovery.review.failure != null && !it.busy }
            assertTrue(vm.state.value.tripMutationRecovery.required); assertNotNull(f.recovery.record); assertEquals(1, f.writes)
        } finally { f.client.close() }
    }
    @Test fun actorChangeClearsUiPermissionButDoesNotReleaseAnotherActorsJournal() = runTest {
        val f = TripFixture()
        try {
            f.login(); f.cancelAcceptedLost = true; val base = TravelerFixture(); val vm = model(f, base); vm.cancelAndWait()
            vm.reviewTripMutation(); vm.awaitState { it.tripMutationRecovery.review.value != null && !it.busy }
            val record = f.recovery.record
            base.identityState.value = base.identityState.value.copy(generation = base.identityState.value.generation + 1, signedIn = false)
            vm.awaitState { !it.identity.signedIn }; vm.startNewTripAction()
            assertFalse(vm.state.value.tripMutationRecovery.required); assertEquals(record, f.recovery.record); assertEquals(1, f.writes)
        } finally { f.client.close() }
    }
    @Test fun anOrdinaryServerConflictDoesNotEnableLocalJournalRelease() = runTest {
        val f = TripFixture()
        try {
            f.login(); val vm = model(f)
            vm.open(TravelerRoute(Destination.TRIP, "trip")); vm.awaitState { it.trip.value != null && !it.trip.loading }
            f.mutationFailure = HttpStatusCode.Conflict to """{"meta":{"code":"TRIP_CONFLICT"},"data":null,"error":{"message":"Synthetic conflict"}}"""
            vm.lifecycle(TripLifecycleAction.CANCEL); vm.awaitState { !it.busy && it.writeFailure != null }
            assertEquals(409, vm.state.value.writeFailure?.status)
            assertFalse(vm.state.value.tripMutationRecovery.required)
            val record = f.recovery.record; vm.startNewTripAction(); assertEquals(record, f.recovery.record); assertEquals(1, f.writes)
        } finally { f.client.close() }
    }
}
