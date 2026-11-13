package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModelStore
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.testing.TravelerCommunitySearchFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TravelerCommunitySearchBackTest {
    private val ui = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    @BeforeTest fun before() { Dispatchers.setMain(ui) }
    @AfterTest fun after() { owners.forEach(ViewModelStore::clear); Dispatchers.resetMain() }
    private fun model(f: TravelerCommunitySearchFixture) = TravelerViewModel(f.runtime, worker = ui).also {
        owners += ViewModelStore().apply { put("vm", it) }
    }
    private val origins = listOf(TravelerRoute(Destination.OPEN_TRIPS), TravelerRoute(Destination.OPEN_TRIPS, "location-a"),
        TravelerRoute(Destination.COMMUNITY), TravelerRoute(Destination.COMMUNITY))
    private fun enter(m: TravelerViewModel, index: Int) {
        m.open(origins[index]); if (index >= 2) m.communityTab(index - 2)
    }
    private fun reader(m: TravelerViewModel, f: TravelerCommunitySearchFixture, index: Int) {
        if (index == 2) m.open(TravelerRoute(Destination.PUBLIC_PLAN, f.base.publicPlan.slug)) else m.openTrip(f.base.tripId)
    }
    private fun assertRequest(m: TravelerViewModel, f: TravelerCommunitySearchFixture, index: Int, query: String) {
        assertEquals(origins[index], m.state.value.route); assertEquals(query, m.state.value.query)
        if (index == 2) {
            assertEquals(TravelerCommunitySearchFixture.PublicQuery(query, null, 1), f.publicQueries.last())
            assertEquals("Rencana hasil $query", m.state.value.publicPlans.value!!.single().title)
        } else {
            assertEquals(OpenTripQuery(query, locationId = if (index == 1) "location-a" else null), f.tripQueries.last())
            assertEquals("Trip hasil $query", m.state.value.openTrips.value!!.items.single().name)
        }
        if (index >= 2) assertEquals(index - 2, m.state.value.communityTab)
        assertEquals(0, f.navigation.unexpectedWrites.get())
    }
    @Test fun allFourOriginsRestoreTheFieldFilteredRequestAndContextAfterReaderBack() = runTest {
        for (index in origins.indices) {
            val f = TravelerCommunitySearchFixture(); val m = model(f); advanceUntilIdle()
            enter(m, index); advanceUntilIdle(); m.query("bromo"); advanceUntilIdle()
            reader(m, f, index); advanceUntilIdle(); m.back(); advanceUntilIdle()
            assertRequest(m, f, index, "bromo")
        }
    }
    @Test fun originAndCommunityTabChangesDoNotBorrowQueriesOrResurrectThemAfterExplicitReset() = runTest {
        val f = TravelerCommunitySearchFixture(); val m = model(f); advanceUntilIdle()
        enter(m, 0); advanceUntilIdle(); m.query("global"); advanceUntilIdle()
        enter(m, 1); advanceUntilIdle(); assertEquals("", m.state.value.query)
        m.query("scoped"); advanceUntilIdle(); reader(m, f, 1); advanceUntilIdle(); m.back(); advanceUntilIdle()
        assertRequest(m, f, 1, "scoped"); m.back(); advanceUntilIdle(); assertRequest(m, f, 0, "global")
        enter(m, 2); advanceUntilIdle(); assertEquals("", m.state.value.query); m.query("public"); advanceUntilIdle()
        m.communityTab(1); advanceUntilIdle(); assertEquals("", m.state.value.query); m.query("community-trip"); advanceUntilIdle()
        reader(m, f, 3); advanceUntilIdle(); m.back(); advanceUntilIdle(); assertRequest(m, f, 3, "community-trip")
        m.communityTab(0); advanceUntilIdle(); reader(m, f, 2); advanceUntilIdle(); m.back(); advanceUntilIdle()
        assertRequest(m, f, 2, "")
        m.tab(Destination.OPEN_TRIPS); advanceUntilIdle(); reader(m, f, 0); advanceUntilIdle(); m.back(); advanceUntilIdle()
        assertRequest(m, f, 0, "")
    }
    @Test fun clearingTheFieldSurvivesAnotherReaderVisitForEveryOrigin() = runTest {
        for (index in origins.indices) {
            val f = TravelerCommunitySearchFixture(); val m = model(f); advanceUntilIdle(); enter(m, index); advanceUntilIdle()
            m.query("bromo"); advanceUntilIdle(); reader(m, f, index); advanceUntilIdle(); m.back(); advanceUntilIdle()
            m.query(""); advanceUntilIdle(); reader(m, f, index); advanceUntilIdle(); m.back(); advanceUntilIdle()
            assertRequest(m, f, index, "")
        }
    }
    @Test fun failedBackReadAndRetryUseTheSameQueryForBothRepositories() = runTest {
        for (index in origins.indices) {
            val f = TravelerCommunitySearchFixture(); val m = model(f); advanceUntilIdle(); enter(m, index); advanceUntilIdle()
            m.query("bromo"); advanceUntilIdle(); reader(m, f, index); advanceUntilIdle()
            f.tripSearchFails = true; f.publicSearchFails = true; m.back(); advanceUntilIdle()
            assertEquals("bromo", m.state.value.query)
            assertNotNull(if (index == 2) m.state.value.publicPlans.failure else m.state.value.openTrips.failure)
            f.tripSearchFails = false; f.publicSearchFails = false; m.refresh(); advanceUntilIdle()
            assertRequest(m, f, index, "bromo")
        }
    }
    @Test fun cancelledOldReadsAndDebounceCannotOverwriteTheNewTabOrQuery() = runTest {
        val f = TravelerCommunitySearchFixture(); val m = model(f); advanceUntilIdle(); enter(m, 2); advanceUntilIdle()
        val publicGate = CompletableDeferred<Unit>()
        f.beforePublicSearch = { if (it == "old-public") withContext(NonCancellable) { publicGate.await() } }
        m.query("old-public"); advanceTimeBy(351); runCurrent(); m.communityTab(1); runCurrent()
        m.query("bromo"); advanceTimeBy(351); runCurrent(); publicGate.complete(Unit); advanceUntilIdle()
        assertRequest(m, f, 3, "bromo"); assertNull(m.state.value.publicPlans.value)
        val tripGate = CompletableDeferred<Unit>()
        f.beforeTripSearch = { if (it == "old-trip") withContext(NonCancellable) { tripGate.await() } }
        m.query("old-trip"); advanceTimeBy(351); runCurrent(); reader(m, f, 3); runCurrent(); m.back(); runCurrent()
        m.query("latest"); advanceTimeBy(351); runCurrent(); tripGate.complete(Unit); advanceUntilIdle()
        assertRequest(m, f, 3, "latest")
        m.query("pending"); m.communityTab(0); advanceUntilIdle(); assertRequest(m, f, 2, "")
        assertNotEquals("pending", f.publicQueries.last().query)
    }
    @Test fun actorGenerationChangeDiscardsOriginQueriesAndSavedNavigationNeverContainsThem() = runTest {
        for (index in origins.indices) {
            val f = TravelerCommunitySearchFixture(); val m = model(f); advanceUntilIdle(); enter(m, index); advanceUntilIdle()
            m.query("private-search-term"); advanceUntilIdle(); reader(m, f, index); advanceUntilIdle()
            assertFalse(m.savedStack().contains("private-search-term"))
            f.base.identityState.value = f.base.identityState.value.copy(generation = 2); advanceUntilIdle()
            enter(m, index); advanceUntilIdle(); assertRequest(m, f, index, "")
        }
    }
}
