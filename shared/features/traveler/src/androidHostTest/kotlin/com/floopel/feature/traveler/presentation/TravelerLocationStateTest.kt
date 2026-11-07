package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModelStore
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.testing.TravelerLocationFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TravelerLocationStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun close() { owners.forEach(ViewModelStore::clear); Dispatchers.resetMain() }
    private fun model(f: TravelerLocationFixture, now: () -> Long = { 0L }) = TravelerViewModel(f.runtime, worker = dispatcher, now = now)
        .also { owners += ViewModelStore().apply { put("test", it) } }
    private fun browser(model: TravelerViewModel) = model.open(TravelerRoute(Destination.CATALOG, extra = CatalogKind.LOCATION.name))
    private fun noWrites(f: TravelerLocationFixture) {
        assertEquals(0, f.base.createCalls.get()); assertEquals(0, f.base.tripCreateCalls.get()); assertEquals(0, f.base.publicationCalls.get())
    }

    @Test fun countrySelectionReadsAllCountryPagesAndReplacesThePickerWithoutDuplicateNavigation() = runTest {
        val f = TravelerLocationFixture(); val m = model(f); advanceUntilIdle(); browser(m); advanceUntilIdle()
        assertEquals(f.indonesia.id, m.state.value.locationBrowser.value?.country?.id)
        assertEquals(listOf("countries:1", "countries:2"), f.reads.filter { it.startsWith("countries:") })
        m.chooseLocationCountry(); advanceUntilIdle()
        assertEquals(2, m.state.value.locationCountries.value?.size)
        m.query("Malaysia"); advanceUntilIdle(); assertTrue(f.reads.none { it.startsWith("search:") })
        m.selectLocationCountry(f.malaysia); advanceUntilIdle()
        assertEquals(f.malaysia.id, m.state.value.route.countryId)
        assertEquals(listOf(f.selangor.id), m.state.value.locationBrowser.value?.entries?.map { it.id })
        assertEquals(2, m.state.value.stack.size); assertTrue(m.savedStack().contains(f.malaysia.id))
        m.back(); advanceUntilIdle(); assertEquals(Destination.HOME, m.state.value.route.destination); noWrites(f)
    }
    @Test fun provinceCityReaderAndBreadcrumbKeepTheirIdsAndCountry() = runTest {
        val f = TravelerLocationFixture(); val m = model(f); advanceUntilIdle(); browser(m); advanceUntilIdle()
        m.browseLocation(f.centralJava); advanceUntilIdle()
        assertEquals(f.centralJava.id, m.state.value.locationBrowser.value?.region?.id)
        m.browseLocation(f.magelang); advanceUntilIdle()
        assertEquals(f.magelang.id, m.state.value.route.id); assertEquals(CatalogKind.LOCATION.name, m.state.value.route.extra)
        assertEquals(f.magelang.id, m.state.value.locationContent.id)
        assertNotNull(m.state.value.locationContent.spots.value); assertNotNull(m.state.value.locationContent.quests.value)
        assertNotNull(m.state.value.locationContent.trips.value); assertNotNull(m.state.value.locationContent.articles.value)
        m.back(); advanceUntilIdle(); assertEquals(f.centralJava.id, m.state.value.route.id)
        m.locationCountriesRoot(); advanceUntilIdle(); assertEquals(2, m.state.value.stack.size)
        assertNull(m.state.value.locationBrowser.value?.region); noWrites(f)
    }
    @Test fun searchSurvivesReaderBackAndCountryPickerCancellation() = runTest {
        val f = TravelerLocationFixture(); val m = model(f); advanceUntilIdle(); browser(m); advanceUntilIdle()
        m.query("Magelang"); advanceUntilIdle(); assertEquals(2, m.state.value.locationBrowser.value?.entries?.size)
        m.browseLocation(f.magelang); advanceUntilIdle(); m.back(); advanceUntilIdle()
        assertEquals("Magelang", m.state.value.query); assertEquals("Magelang", m.state.value.locationBrowser.value?.query)
        m.chooseLocationCountry(); advanceUntilIdle(); m.query("Malaysia"); m.back(); advanceUntilIdle()
        assertEquals("Magelang", m.state.value.query); assertEquals(2, m.state.value.locationBrowser.value?.entries?.size)
        noWrites(f)
    }
    @Test fun aCountryBreadcrumbAfterGlobalSearchNeverResolvesThePreviousDefaultCountry() = runTest {
        for (initialCountry in listOf("", "country-id", "country-my")) {
            val f = TravelerLocationFixture(); val m = model(f); advanceUntilIdle()
            m.open(TravelerRoute(Destination.CATALOG, extra = CatalogKind.LOCATION.name, countryId = initialCountry)); advanceUntilIdle()
            m.query("Selangor"); advanceUntilIdle()
            m.browseLocation(m.state.value.locationBrowser.value!!.entries.single()); advanceUntilIdle()
            assertEquals(f.malaysia.id, m.state.value.locationBrowser.value?.country?.id)
            m.locationCountriesRoot(); advanceUntilIdle()
            assertEquals(f.malaysia.id, m.state.value.route.countryId)
            assertEquals(f.malaysia.id, m.state.value.locationBrowser.value?.country?.id)
            assertEquals(listOf(f.selangor.id), m.state.value.locationBrowser.value?.entries?.map { it.id })
            assertEquals("browse:LOCATION:${f.malaysia.id}:1:", f.reads.last { it.startsWith("browse:LOCATION:") })
            assertEquals("", m.state.value.query); assertEquals(2, m.state.value.stack.size)
            assertEquals(Destination.HOME, m.state.value.tab)
            m.back(); advanceUntilIdle(); assertEquals(Destination.HOME, m.state.value.route.destination); noWrites(f)
        }
    }
    @Test fun aCancelledOldCountryReadCannotOverwriteTheNewSelection() = runTest {
        val f = TravelerLocationFixture(); val barrier = CompletableDeferred<Unit>()
        f.beforeBrowse = { if (it == f.indonesia.id) withContext(NonCancellable) { barrier.await() } }
        val m = model(f); runCurrent(); browser(m); runCurrent()
        m.chooseLocationCountry(); runCurrent(); m.selectLocationCountry(f.malaysia); runCurrent()
        assertEquals(f.malaysia.id, m.state.value.locationBrowser.value?.country?.id)
        barrier.complete(Unit); advanceUntilIdle()
        assertEquals(listOf(f.selangor.id), m.state.value.locationBrowser.value?.entries?.map { it.id }); noWrites(f)
    }
    @Test fun countryFailureHonorsRetryAfterAndRecoversOnlyThroughARead() = runTest {
        val f = TravelerLocationFixture(); var clock = 0L; f.failCountries = true
        val m = model(f) { clock }; advanceUntilIdle(); browser(m); advanceUntilIdle()
        assertEquals(503, m.state.value.locationBrowser.failure?.status); assertNull(m.state.value.locationBrowser.value)
        val attempts = f.reads.count { it.startsWith("countries:") }; f.failCountries = false
        m.refresh(); advanceUntilIdle(); assertEquals(attempts, f.reads.count { it.startsWith("countries:") })
        clock = 2_001; m.refresh(); advanceUntilIdle(); assertEquals(f.indonesia.id, m.state.value.locationBrowser.value?.country?.id)
        assertNull(m.state.value.locationBrowser.failure); noWrites(f)
    }
    @Test fun anEmptyCountryResultIsDistinctFromAFailedChildrenRead() = runTest {
        val f = TravelerLocationFixture(); f.emptyChildren = true; val m = model(f); advanceUntilIdle(); browser(m); advanceUntilIdle()
        assertTrue(m.state.value.locationBrowser.value?.entries?.isEmpty() == true); assertNull(m.state.value.locationBrowser.failure)
        f.emptyChildren = false; f.failChildren = true; m.refresh(); advanceUntilIdle()
        assertNotNull(m.state.value.locationBrowser.failure)
        f.failChildren = false; m.refresh(); advanceUntilIdle()
        assertEquals(2, m.state.value.locationBrowser.value?.entries?.size); assertNull(m.state.value.locationBrowser.failure)
    }
    @Test fun locationRailFailuresKeepTheLocationAndRecoverWithoutMutatingQuestOrTrip() = runTest {
        val f = TravelerLocationFixture(); f.failSpots = true; f.failQuests = true; f.failTrips = true; f.failArticles = true
        var clock = 0L; val m = model(f) { clock }; advanceUntilIdle(); m.openEntry(f.magelang); advanceUntilIdle()
        assertEquals(f.magelang.id, m.state.value.detail.value?.id)
        val content = m.state.value.locationContent
        assertNotNull(content.spots.failure); assertNotNull(content.quests.failure); assertNotNull(content.trips.failure); assertNotNull(content.articles.failure)
        f.failSpots = false; f.failQuests = false; f.failTrips = false; f.failArticles = false; clock = 2_001
        m.refresh(); advanceUntilIdle()
        assertEquals(f.magelang.id, m.state.value.locationContent.id); assertNotNull(m.state.value.locationContent.quests.value)
        assertTrue(f.reads.filter { it.startsWith("untaken:") }.all { it.startsWith("untaken:${f.magelang.id}:") }); noWrites(f)
    }
    @Test fun locationQuestPaginationNeverSwitchesToTheGlobalOrTakenCatalog() = runTest {
        val f = TravelerLocationFixture(); f.questPages = 2; val m = model(f); advanceUntilIdle()
        m.open(TravelerRoute(Destination.QUESTS, f.magelang.id)); advanceUntilIdle(); m.more(); advanceUntilIdle()
        assertEquals(listOf("untaken:${f.magelang.id}:1", "untaken:${f.magelang.id}:2"), f.reads.filter { it.startsWith("untaken:") })
        assertEquals(listOf("quest-1", "quest-2"), m.state.value.quests.value?.items?.map { it.id }); noWrites(f)
    }
    @Test fun openTripSearchAndCursorKeepTheSelectedLocation() = runTest {
        val f = TravelerLocationFixture(); f.tripNextCursor = "page-two"; val m = model(f); advanceUntilIdle()
        m.open(TravelerRoute(Destination.OPEN_TRIPS, f.magelang.id)); advanceUntilIdle(); m.query("Jelajah"); advanceUntilIdle(); m.more(); advanceUntilIdle()
        assertTrue(f.tripQueries.all { it.locationId == f.magelang.id }); assertEquals("page-two", f.tripQueries.last().cursor)
        assertEquals("Jelajah", f.tripQueries.last().query); noWrites(f)
    }
}
