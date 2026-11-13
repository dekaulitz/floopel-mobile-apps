package com.floopel.feature.traveler.presentation

import androidx.lifecycle.viewModelScope
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.testing.TravelerPhotoFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TravelerPublicationCopyGuardTest {
    private fun runCase(missingProfile: Boolean = false, block: suspend TestScope.(TravelerPhotoFixture, TravelerViewModel) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val f = TravelerPhotoFixture()
        if (missingProfile) f.profileFailure = CatalogResult.NetworkError
        val m = TravelerViewModel(f.runtime, worker = dispatcher)
        try { advanceUntilIdle(); m.open(TravelerRoute(Destination.COMMUNITY)); advanceUntilIdle(); f.profileFailure = null; block(f, m) }
        finally { m.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun ownPublicationIsRejectedBeforeCloneAndRefreshClearsOnlyTheDefiniteMessage() = runCase { f, m ->
        val own = f.base.publicPlan.copy(ownerUsername = f.profile.username)
        m.clonePublicPlan(own); advanceUntilIdle()
        assertEquals(0, f.base.cloneCalls.get()); assertEquals("OWN_PUBLICATION", m.state.value.writeFailure?.code)
        assertFalse(m.state.value.unknownOutcome); m.refreshAfterWriteFailure(); advanceUntilIdle()
        assertNull(m.state.value.writeFailure); assertEquals(0, f.base.cloneCalls.get())
    }
    @Test fun anotherOwnersCloneStillCreatesExactlyOneOwnedPlan() = runCase { f, m ->
        m.clonePublicPlan(f.base.publicPlan); m.clonePublicPlan(f.base.publicPlan); advanceUntilIdle()
        assertEquals(1, f.base.cloneCalls.get()); assertEquals(Destination.ITINERARY, m.state.value.route.destination)
    }
    @Test fun failedOwnerLookupDoesNotSubmitAndIsVisibleForReadOnlyRetry() = runCase(missingProfile = true) { f, m ->
        f.profileFailure = CatalogResult.NetworkError
        m.clonePublicPlan(f.base.publicPlan); advanceUntilIdle()
        assertEquals(0, f.base.cloneCalls.get()); assertNotNull(m.state.value.writeFailure)
        f.profileFailure = null; m.refreshAfterWriteFailure(); advanceUntilIdle()
        assertEquals(0, f.base.cloneCalls.get())
    }
    @Test fun leavingWhileCheckingOwnershipCannotCloneAStaleCard() = runCase(missingProfile = true) { f, m ->
        val gate = CompletableDeferred<Unit>(); f.beforeProfile = { withContext(NonCancellable) { gate.await() } }
        m.clonePublicPlan(f.base.publicPlan); runCurrent(); m.back(); runCurrent()
        gate.complete(Unit); advanceUntilIdle(); assertEquals(0, f.base.cloneCalls.get())
    }
    @Test fun switchingAccountsWhileCheckingOwnershipDiscardsTheOldIntent() = runCase(missingProfile = true) { f, m ->
        val gate = CompletableDeferred<Unit>(); f.beforeProfile = { withContext(NonCancellable) { gate.await() } }
        m.clonePublicPlan(f.base.publicPlan); runCurrent(); f.base.identity.logout(); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(0, f.base.cloneCalls.get()); assertEquals(Destination.LOGIN, m.state.value.route.destination)
    }
}
