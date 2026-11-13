package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModelStore
import com.floopel.feature.traveler.domain.model.TravelerIdentityView
import com.floopel.feature.traveler.testing.TravelerPublicationFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TravelerPublicationStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun close() { owners.forEach(ViewModelStore::clear); Dispatchers.resetMain() }
    private fun model(f: TravelerPublicationFixture) = TravelerViewModel(f.runtime, worker = dispatcher)
        .also { owners += ViewModelStore().apply { put("test", it) } }
    private fun open(m: TravelerViewModel, f: TravelerPublicationFixture) = m.open(TravelerRoute(Destination.PUBLICATION, f.base.planId))

    @Test fun confirmedPublishWithoutCountsKeepsKnownStopsAndReloadsTheAuthoritativeRevision() = runTest {
        val f = TravelerPublicationFixture(); val m = model(f); advanceUntilIdle(); open(m,f); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        f.beforeOwnerRead = { gate.await() }
        m.publish(true,true,true); runCurrent()
        assertEquals(1, f.base.publicationCalls.get()); assertTrue(m.state.value.saved)
        assertNull(m.state.value.distribution.value?.stopCount); assertNull(m.state.value.distribution.value?.updatedAt)
        assertEquals(true, m.state.value.publicationHasSpots); assertTrue(m.state.value.distribution.loading)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("revision-2", m.state.value.distribution.value?.updatedAt)
        assertEquals(1, m.state.value.distribution.value?.stopCount); assertEquals(true, m.state.value.publicationHasSpots)
        assertEquals(2, f.ownerReads.get()); assertEquals(3, f.planReads.get()); assertEquals(1, f.base.publicationCalls.get())
    }
    @Test fun aFailedOwnerReadAfterKnownPublishSuccessRecoversWithGetAndNeverRepeatsPublish() = runTest {
        val f = TravelerPublicationFixture(); val m = model(f); advanceUntilIdle(); open(m,f); advanceUntilIdle()
        f.onPublished = { f.failOwnerRead = true }
        m.publish(true,true,true); advanceUntilIdle()
        assertTrue(m.state.value.saved); assertFalse(m.state.value.unknownOutcome); assertNull(m.state.value.writeFailure)
        assertNotNull(m.state.value.distribution.failure); assertNull(m.state.value.distribution.value?.updatedAt)
        assertEquals(true, m.state.value.publicationHasSpots)
        f.failOwnerRead = false; m.refresh(); advanceUntilIdle()
        assertNull(m.state.value.distribution.failure); assertEquals("revision-2", m.state.value.distribution.value?.updatedAt)
        assertEquals(1, f.base.publicationCalls.get()); assertEquals(3, f.ownerReads.get())
    }
    @Test fun aFailedDetailReadIsUnknownContentRatherThanAnEmptyPlanAfterPublish() = runTest {
        val f = TravelerPublicationFixture(); val m = model(f); advanceUntilIdle(); open(m,f); advanceUntilIdle()
        f.onPublished = { f.failPlanRead = true }
        m.publish(true,true,true); advanceUntilIdle()
        assertNotNull(m.state.value.itinerary.failure); assertNull(m.state.value.publicationHasSpots)
        assertEquals(1, m.state.value.itinerary.value?.stops?.size); assertTrue(m.state.value.saved)
        f.failPlanRead = false; m.refresh(); advanceUntilIdle()
        assertEquals(true, m.state.value.publicationHasSpots); assertEquals(1, f.base.publicationCalls.get())
    }
    @Test fun aLatePublicationReadCannotReplaceAnotherItinerary() = runTest {
        val f = TravelerPublicationFixture(); val m = model(f); advanceUntilIdle(); open(m,f); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        f.beforeOwnerRead = { withContext(NonCancellable) { gate.await() } }
        m.publish(true,true,true); runCurrent()
        m.open(TravelerRoute(Destination.ITINERARY, "other-plan")); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("other-plan", m.state.value.route.id); assertEquals("other-plan", m.state.value.itinerary.value?.itinerary?.id)
        assertNull(m.state.value.distribution.value); assertNull(m.state.value.publicationHasSpots)
        assertEquals(1, f.base.publicationCalls.get())
    }
    @Test fun aLatePublicationReadCannotRestoreThePreviousActorsStateAfterLogout() = runTest {
        val f = TravelerPublicationFixture(); val m = model(f); advanceUntilIdle(); open(m,f); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        f.beforeOwnerRead = { withContext(NonCancellable) { gate.await() } }
        m.publish(true,true,true); runCurrent()
        f.base.identityState.value = TravelerIdentityView(generation = 2); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(Destination.LOGIN, m.state.value.route.destination); assertNull(m.state.value.distribution.value)
        assertNull(m.state.value.itinerary.value); assertFalse(m.state.value.identity.signedIn)
        assertEquals(1, f.base.publicationCalls.get())
    }
    @Test fun unpublishAlsoReloadsOwnerMetadataWithoutTurningAbsentCountsIntoEmptyContent() = runTest {
        val f = TravelerPublicationFixture(); f.published = true
        val m = model(f); advanceUntilIdle(); open(m,f); advanceUntilIdle()
        m.unpublish(); advanceUntilIdle()
        assertEquals("private", m.state.value.distribution.value?.visibility)
        assertEquals("revision-2", m.state.value.distribution.value?.updatedAt)
        assertEquals(true, m.state.value.publicationHasSpots)
        assertEquals(1, f.unpublishCalls.get()); assertEquals(0, f.base.publicationCalls.get()); assertEquals(2, f.ownerReads.get())
    }
}
