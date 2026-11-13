package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModelStore
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.testing.TravelerNavigationFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TravelerCatalogSearchBackTest {
    private val ui = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    @BeforeTest fun before() { Dispatchers.setMain(ui) }
    @AfterTest fun after() { owners.forEach(ViewModelStore::clear); Dispatchers.resetMain() }
    private fun model(f: TravelerNavigationFixture) = TravelerViewModel(f.runtime, worker = ui).also {
        owners += ViewModelStore().apply { put("vm", it) }
    }
    private fun archive(id: String = "") = TravelerRoute(Destination.CATALOG, id, CatalogKind.SPOT.name, locationContextId = id)

    @Test fun explorerGlobalAndScopedArchiveRestoreTheirQueryAndExactContextAfterReaderBack() = runTest {
        for (source in listOf(TravelerRoute(Destination.EXPLORER), archive(), archive("location-a"))) {
            val f = TravelerNavigationFixture(); val m = model(f); advanceUntilIdle(); m.open(source); advanceUntilIdle()
            m.query("museum"); advanceUntilIdle(); m.openEntry(m.state.value.catalog.value!!.items.single()); advanceUntilIdle()
            m.back(); advanceUntilIdle()
            assertEquals(source, m.state.value.route); assertEquals("museum", m.state.value.query)
            val last = f.reads.last { it.startsWith("explorer:") || it.startsWith("search:") }
            assertEquals(if (source.destination == Destination.EXPLORER) "explorer:museum" else "search:museum:${source.id}:1", last)
            assertEquals(0, f.unexpectedWrites.get())
        }
    }
    @Test fun differentOriginsDoNotBorrowQueriesAndExplicitClearSurvivesAnotherDetailVisit() = runTest {
        val f = TravelerNavigationFixture(); val m = model(f); advanceUntilIdle()
        val a = archive("location-a"); val b = archive("location-b")
        m.open(a); advanceUntilIdle(); m.query("museum"); advanceUntilIdle()
        m.open(b); advanceUntilIdle(); assertEquals("", m.state.value.query)
        m.query("garden"); advanceUntilIdle(); m.openEntry(f.spot); advanceUntilIdle(); m.back(); advanceUntilIdle()
        assertEquals(b, m.state.value.route); assertEquals("garden", m.state.value.query)
        m.back(); advanceUntilIdle(); assertEquals(a, m.state.value.route); assertEquals("museum", m.state.value.query)
        m.query(""); advanceUntilIdle(); m.openEntry(f.spot); advanceUntilIdle(); m.back(); advanceUntilIdle()
        assertEquals("", m.state.value.query); assertTrue(f.reads.contains("browse:SPOT:location-a:1"))
    }
    @Test fun failedSearchBackAndRetryKeepTheQueryWhileAnOldCancelledSearchCannotReplaceIt() = runTest {
        val f = TravelerNavigationFixture(); val m = model(f); advanceUntilIdle(); m.open(archive("location-a")); advanceUntilIdle()
        m.query("museum"); advanceUntilIdle(); m.openEntry(f.spot); advanceUntilIdle(); f.searchFails = true
        m.back(); advanceUntilIdle(); assertEquals("museum", m.state.value.query); assertNotNull(m.state.value.catalog.failure)
        f.searchFails = false; m.refresh(); advanceUntilIdle(); assertNotNull(m.state.value.catalog.value)
        val gate = CompletableDeferred<Unit>(); f.beforeSearch = { if (it == "oldquery") withContext(NonCancellable) { gate.await() } }
        m.query("oldquery"); advanceTimeBy(351); runCurrent(); m.openEntry(f.spot); runCurrent(); m.back(); runCurrent()
        m.query("newquery"); advanceTimeBy(351); runCurrent(); gate.complete(Unit); advanceUntilIdle()
        assertEquals("newquery", m.state.value.query); assertEquals("search:newquery:location-a:1", f.reads.last { it.startsWith("search:") })
    }
    @Test fun changingIdentityClearsAllOriginQueriesWithoutPersistingTheSearchTerm() = runTest {
        val f = TravelerNavigationFixture(); val m = model(f); advanceUntilIdle()
        m.open(archive("location-a")); advanceUntilIdle(); m.query("private-search-term"); advanceUntilIdle()
        assertFalse(m.savedStack().contains("private-search-term"))
        f.base.identityState.value = f.base.identityState.value.copy(generation = 2); advanceUntilIdle()
        m.tab(Destination.EXPLORER); advanceUntilIdle(); assertEquals("", m.state.value.query)
        m.open(archive("location-a")); advanceUntilIdle(); assertEquals("", m.state.value.query)
    }
}
