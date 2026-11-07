package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModelStore
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.quest.*
import com.floopel.feature.traveler.testing.TravelerQuestDesignFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TravelerQuestDesignStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun close() { owners.forEach(ViewModelStore::clear); Dispatchers.resetMain() }
    private fun model(f: TravelerQuestDesignFixture) = TravelerViewModel(f.runtime, worker = dispatcher).also { owners += ViewModelStore().apply { put("model", it) } }
    private fun catalog(m: TravelerViewModel) = m.open(TravelerRoute(Destination.QUESTS))
    private fun detail(m: TravelerViewModel, f: TravelerQuestDesignFixture, active: Boolean = false) =
        m.open(TravelerRoute(Destination.QUEST, if (active) f.activeCard.slug else f.card.slug))

    @Test fun appliedLocationAndXpReachEveryPageAndDuplicateRowsAreNotAppended() = runTest {
        val f = TravelerQuestDesignFixture(); f.totalPages = 2; val m = model(f); advanceUntilIdle(); catalog(m); advanceUntilIdle()
        m.openQuestFilter(); m.questXpDraft("0", "250"); m.openQuestLocationPicker(); advanceUntilIdle(); m.selectQuestFilterLocation(f.location)
        m.applyQuestFilter(); advanceUntilIdle(); m.more(); advanceUntilIdle()
        val queries = f.catalogCalls.takeLast(2)
        assertEquals(listOf(1, 2), queries.map { it.second })
        assertTrue(queries.all { it.first.minXP == 0 && it.first.maxXP == 250 && it.first.locationId == f.location.id })
        assertEquals(2, m.state.value.quests.value?.items?.size); assertEquals(0, f.startCalls.get())
    }
    @Test fun filterDraftSurvivesBackAndDoesNotQueryUntilExplicitApply() = runTest {
        val f = TravelerQuestDesignFixture(); val m = model(f); advanceUntilIdle(); catalog(m); advanceUntilIdle(); val before = f.catalogCalls.size
        m.openQuestFilter(); m.questXpDraft("100", "250"); advanceUntilIdle(); assertEquals(before, f.catalogCalls.size)
        m.back(); advanceUntilIdle(); m.openQuestFilter(); assertEquals("100", m.state.value.questBrowse.draft?.minimum)
        assertNull(m.state.value.questBrowse.filter.minXP); m.applyQuestFilter(); advanceUntilIdle()
        assertEquals(100, f.catalogCalls.last().first.minXP)
    }
    @Test fun invalidOrOverflowingXpNeverChangesAppliedFilterAndResetOnlyChangesTheDraft() = runTest {
        val f = TravelerQuestDesignFixture(); val m = model(f); advanceUntilIdle(); catalog(m); advanceUntilIdle(); m.openQuestFilter()
        for ((minimum, maximum) in listOf("-1" to "200", "300" to "200", "2147483648" to "", "1.5" to "", "a" to "")) {
            m.questXpDraft(minimum, maximum); assertFalse(requireNotNull(m.state.value.questBrowse.draft).valid)
            m.applyQuestFilter(); assertEquals(Destination.QUEST_FILTER, m.state.value.route.destination)
        }
        m.resetQuestFilterDraft(); assertTrue(requireNotNull(m.state.value.questBrowse.draft).valid); assertNull(m.state.value.questBrowse.filter.minXP)
    }
    @Test fun modeAndTakenUseSeparateAuthoritativeReadsWithoutBorrowingRewardMetadata() = runTest {
        val f = TravelerQuestDesignFixture(); val m = model(f); advanceUntilIdle(); catalog(m); advanceUntilIdle()
        m.questMode("group"); advanceUntilIdle(); assertEquals("group", f.catalogCalls.last().first.participationMode); assertTrue(m.state.value.quests.value?.items?.isEmpty() == true)
        m.questCollection(true); advanceUntilIdle(); assertEquals("group", f.takenModes.last()); assertTrue(m.state.value.questBrowse.takenRows.value?.isEmpty() == true)
        m.questMode(null); advanceUntilIdle(); assertEquals(listOf(f.running), m.state.value.questBrowse.takenRows.value)
        m.continueQuest(f.running.id); advanceUntilIdle(); assertEquals(0, f.startCalls.get()); assertTrue(f.continueCalls.get() > 0)
    }
    @Test fun oldCatalogResponseCannotReplaceNewModeOrTakenCollection() = runTest {
        val f = TravelerQuestDesignFixture(); val gate = CompletableDeferred<Unit>(); var first = true
        f.beforeCatalog = { _, _ -> if (first) { first = false; withContext(NonCancellable) { gate.await() } } }
        val m = model(f); advanceUntilIdle(); catalog(m); runCurrent(); m.questMode("group"); runCurrent(); m.questCollection(true); runCurrent()
        gate.complete(Unit); advanceUntilIdle(); assertTrue(m.state.value.questBrowse.taken); assertNull(m.state.value.quests.value)
        assertEquals(emptyList(), m.state.value.questBrowse.takenRows.value)
    }
    @Test fun catalogResponseAfterAccountChangeCannotRestorePrivateProgress() = runTest {
        val f = TravelerQuestDesignFixture(); val gate = CompletableDeferred<Unit>(); f.beforeCatalog = { _, _ -> withContext(NonCancellable) { gate.await() } }
        val m = model(f); advanceUntilIdle(); catalog(m); runCurrent()
        f.base.identityState.value = TravelerIdentityView(generation = 2); runCurrent(); gate.complete(Unit); advanceUntilIdle()
        assertEquals(Destination.LOGIN, m.state.value.route.destination); assertNull(m.state.value.quests.value); assertNull(m.state.value.questBrowse.takenRows.value)
    }
    @Test fun readerFailureIsNotAnEmptyQuestAndRetryDoesNotStartParticipation() = runTest {
        val f = TravelerQuestDesignFixture(); f.readerFailure = CatalogResult.HttpError(503); val m = model(f); advanceUntilIdle(); detail(m, f); advanceUntilIdle()
        assertNotNull(m.state.value.quest.failure); assertNull(m.state.value.quest.value); assertTrue(m.state.value.questSpots.isEmpty())
        f.readerFailure = null; m.refresh(); advanceUntilIdle(); assertEquals(f.card.id, m.state.value.quest.value?.card?.id); assertEquals(0, f.startCalls.get())
    }
    @Test fun publicCheckpointsReadOnlyTheirSpotAndRetryNeverFetchesGameplayContent() = runTest {
        val f = TravelerQuestDesignFixture(); f.spotFailure = CatalogResult.NetworkError; val m = model(f); advanceUntilIdle(); detail(m, f); advanceUntilIdle()
        assertNotNull(m.state.value.questSpots[f.spot.id]?.failure); val readers = f.readerCalls.size
        f.spotFailure = null; m.loadQuestSpot(f.spot.id); advanceUntilIdle()
        assertEquals(f.spot.id, m.state.value.questSpots[f.spot.id]?.value?.id); assertEquals(readers, f.readerCalls.size)
        assertEquals(0, f.activityCalls.get()); assertEquals(0, f.startCalls.get()); m.loadQuestSpot("foreign"); advanceUntilIdle(); assertFalse("foreign" in f.detailCalls)
    }
    @Test fun staleCheckpointCannotAttachToAnotherReaderAfterBack() = runTest {
        val f = TravelerQuestDesignFixture(); val gate = CompletableDeferred<Unit>(); f.beforeSpot = { withContext(NonCancellable) { gate.await() } }
        val m = model(f); advanceUntilIdle(); detail(m, f); runCurrent(); m.back(); runCurrent(); gate.complete(Unit); advanceUntilIdle()
        assertEquals(Destination.HOME, m.state.value.route.destination); assertNull(m.state.value.questSpots[f.spot.id]?.value)
    }
    @Test fun admissionRequiresMatchingOwnerAndEveryExecutionAvailabilityAndQuotaCondition() = runTest {
        val f = TravelerQuestDesignFixture(); val identity = f.base.identityState.value
        assertTrue(f.readyReader.canTake(identity))
        val invalid = listOf(f.readyReader.copy(owner = null), f.readyReader.copy(owner = f.readyReader.owner?.copy(questId = "other")),
            f.readyReader.copy(remaining = 0), f.readyReader.copy(availability = "upcoming"), f.readyReader.copy(availability = "ended"),
            f.readyReader.copy(executionStatus = "suspended"), f.readyReader.copy(executionStatus = "pending"), f.readyReader.copy(card = f.card.copy(mode = "group")))
        invalid.forEach { assertFalse(it.canTake(identity)) }
        assertFalse(f.readyReader.canTake(identity.copy(executionConfigured = false))); assertFalse(f.readyReader.canTake(identity.copy(busy = true)))
        val m = model(f); advanceUntilIdle(); f.readyReader = invalid[1]; detail(m, f); advanceUntilIdle(); m.takeQuest(); advanceUntilIdle(); assertEquals(0, f.startCalls.get())
    }
    @Test fun doubleTapSubmitsOnceAndLateAdmissionCannotNavigateForANewActor() = runTest {
        val f = TravelerQuestDesignFixture(); val gate = CompletableDeferred<Unit>(); f.beforeTake = { withContext(NonCancellable) { gate.await() } }
        val m = model(f); advanceUntilIdle(); detail(m, f); advanceUntilIdle(); m.takeQuest(); m.takeQuest(); runCurrent(); assertEquals(1, f.startCalls.get())
        f.base.identityState.value = TravelerIdentityView(generation = 2); runCurrent(); gate.complete(Unit); advanceUntilIdle()
        assertEquals(Destination.LOGIN, m.state.value.route.destination)
    }
    @Test fun continueReadsOwnedParticipationEvenWhenPublicQuotaIsFullAndDoesNotAdmitAgain() = runTest {
        val f = TravelerQuestDesignFixture(); f.activeReader = f.activeReader.copy(remaining = 0, availability = "ended", executionStatus = "suspended")
        val m = model(f); advanceUntilIdle(); detail(m, f, true); advanceUntilIdle(); m.continueQuest(); advanceUntilIdle()
        assertEquals(0, f.startCalls.get()); assertTrue(f.continueCalls.get() > 0); assertEquals(Destination.QUEST_PROGRESS, m.state.value.route.destination)
    }
    @Test fun failedAppendRetainsRowsAndExplicitRetryUsesTheSamePageAndFilter() = runTest {
        val f = TravelerQuestDesignFixture(); f.totalPages = 2; val m = model(f); advanceUntilIdle(); catalog(m); advanceUntilIdle()
        f.catalogFailure = CatalogResult.NetworkError; m.more(); advanceUntilIdle(); assertEquals(2, m.state.value.quests.value?.items?.size); assertNotNull(m.state.value.quests.failure)
        f.catalogFailure = null; m.retryQuestCatalog(); advanceUntilIdle(); assertEquals(listOf(1, 2, 2), f.catalogCalls.map { it.second }); assertNull(m.state.value.quests.failure)
    }
    @Test fun failedRefreshAfterDetailRetriesPageOneDespiteCachedNextPage() = runTest {
        val f = TravelerQuestDesignFixture(); f.totalPages = 3; val m = model(f); advanceUntilIdle(); catalog(m); advanceUntilIdle()
        m.openQuestFilter(); m.questXpDraft("100", "250"); m.applyQuestFilter(); advanceUntilIdle()
        detail(m, f); advanceUntilIdle(); f.catalogFailure = CatalogResult.NetworkError; m.back(); advanceUntilIdle()
        assertEquals(1, m.state.value.quests.value?.page); assertNotNull(m.state.value.quests.failure)
        val failedFilter = f.catalogCalls.last().first
        m.more(); advanceUntilIdle(); assertEquals(1, f.catalogCalls.last().second)
        f.catalogFailure = null; m.retryQuestCatalog(); advanceUntilIdle()
        assertEquals(listOf(1, 1), f.catalogCalls.takeLast(2).map { it.second })
        assertEquals(failedFilter, f.catalogCalls.last().first); assertNull(m.state.value.quests.failure); assertEquals(0, f.startCalls.get())
    }
    @Test fun unknownProgressIsNotAValidZeroPercentParticipation() {
        val f = TravelerQuestDesignFixture(); assertTrue(f.running.validProgress())
        assertFalse(f.running.copy(total = 0).validProgress()); assertFalse(f.running.copy(completed = -1).validProgress()); assertFalse(f.running.copy(completed = 5).validProgress())
    }
}
