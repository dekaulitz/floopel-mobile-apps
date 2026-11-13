package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModelStore
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.testing.TravelerPendingInputFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TravelerDayTitleDraftTest {
    private val ui = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private var clock = 0L
    @BeforeTest fun before() { Dispatchers.setMain(ui) }
    @AfterTest fun after() { owners.forEach(ViewModelStore::clear); Dispatchers.resetMain() }
    private fun model(f: TravelerPendingInputFixture) = TravelerViewModel(f.runtime, worker = ui, now = { clock }).also {
        owners += ViewModelStore().apply { put("vm", it) }
    }
    private fun enter(m: TravelerViewModel, f: TravelerPendingInputFixture) = m.open(TravelerRoute(Destination.ITINERARY, f.base.planId))
    @Test fun failedConsentPreflightKeepsTheDraftAndExplicitRetrySendsExactlyOneTitlePut() = runTest {
        for (failure in listOf(CatalogResult.NetworkError, CatalogResult.HttpError(503))) {
            val f = TravelerPendingInputFixture(); val m = model(f); advanceUntilIdle(); enter(m, f); advanceUntilIdle()
            m.editDayTitle(1); m.dayTitleValue("Hari baru"); f.legalResult = failure; m.saveDayTitle(); advanceUntilIdle()
            assertEquals("Hari baru", m.state.value.dayTitleDraft?.value); assertTrue(f.titleWrites.isEmpty()); assertNotNull(m.state.value.writeFailure)
            assertFalse(m.state.value.unknownOutcome)
            f.legalResult = CatalogResult.Ready(emptyList()); m.refresh(); advanceUntilIdle(); assertTrue(f.titleWrites.isEmpty())
            m.saveDayTitle(); advanceUntilIdle()
            assertEquals(listOf(TravelerPendingInputFixture.TitleWrite(f.base.planId, 1, "Hari baru")), f.titleWrites)
            assertNull(m.state.value.dayTitleDraft); assertEquals("Hari baru", m.state.value.itinerary.value!!.dayTitles[1])
        }
    }
    @Test fun legalReviewAndReadBackRetainInputWithoutReplayingTheTitleWrite() = runTest {
        val f = TravelerPendingInputFixture(); val m = model(f); advanceUntilIdle(); enter(m, f); advanceUntilIdle()
        m.editDayTitle(1); m.dayTitleValue("Hari persetujuan")
        f.legalResult = CatalogResult.Ready(listOf(LegalDocument("terms", "fixture", "id", "<p>Fixture</p>", "fixture-hash")))
        m.saveDayTitle(); advanceUntilIdle(); assertEquals(Destination.LEGAL, m.state.value.route.destination)
        assertEquals("Hari persetujuan", m.state.value.dayTitleDraft?.value); assertTrue(f.titleWrites.isEmpty())
        f.legalResult = CatalogResult.Ready(emptyList()); m.back(); advanceUntilIdle(); m.refresh(); advanceUntilIdle()
        assertEquals("Hari persetujuan", m.state.value.dayTitleDraft?.value); assertTrue(f.titleWrites.isEmpty())
        m.saveDayTitle(); advanceUntilIdle(); assertEquals(1, f.titleWrites.size)
    }
    @Test fun validationThrottleAndUnknownResponsesKeepInputAndNeverAutomaticallyReplay() = runTest {
        for (failure in listOf(CatalogResult.HttpError(422), CatalogResult.HttpError(429, retryAfterSeconds = 2), CatalogResult.NetworkError)) {
            val f = TravelerPendingInputFixture(); val m = model(f); advanceUntilIdle(); enter(m, f); advanceUntilIdle()
            m.editDayTitle(2); m.dayTitleValue("Hari kedua"); f.titleResult = failure; m.saveDayTitle(); advanceUntilIdle()
            assertEquals("Hari kedua", m.state.value.dayTitleDraft?.value); assertNotNull(m.state.value.writeFailure)
            assertEquals(failure == CatalogResult.NetworkError, m.state.value.unknownOutcome)
            m.refresh(); advanceUntilIdle(); assertEquals(1, f.titleWrites.size)
            if (failure is CatalogResult.HttpError && failure.status == 429) { m.saveDayTitle(); advanceUntilIdle(); assertEquals(1, f.titleWrites.size); clock += 2_001 }
            m.dayTitleValue("Perbaikan hari kedua"); f.titleResult = null; m.saveDayTitle(); advanceUntilIdle()
            assertEquals(2, f.titleWrites.size); assertEquals(2, f.titleWrites.last().day)
            assertEquals("Perbaikan hari kedua", f.titleWrites.last().value); assertNull(m.state.value.dayTitleDraft)
        }
    }
    @Test fun pendingSaveFreezesItsInputAndDuplicateSubmitThenAcknowledgementClosesOnlyItsEditor() = runTest {
        val f = TravelerPendingInputFixture(); val m = model(f); advanceUntilIdle(); enter(m, f); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); f.beforeTitle = { gate.await() }
        m.editDayTitle(1); m.dayTitleValue("Bekukan ini"); m.saveDayTitle(); m.saveDayTitle(); runCurrent()
        m.dayTitleValue("Bukan yang dikirim"); m.cancelDayTitle(); assertEquals("Bekukan ini", m.state.value.dayTitleDraft?.value)
        assertEquals(1, f.titleWrites.size); gate.complete(Unit); advanceUntilIdle()
        assertNull(m.state.value.dayTitleDraft); assertEquals("Bekukan ini", m.state.value.itinerary.value!!.dayTitles[1])
    }
    @Test fun cancelDropsOnlyItsDraftWhileOtherItineraryAndDayOriginsNeverBorrowValues() = runTest {
        val f = TravelerPendingInputFixture(); val m = model(f); advanceUntilIdle(); enter(m, f); advanceUntilIdle()
        m.editDayTitle(1); m.dayTitleValue("Draft A"); m.open(TravelerRoute(Destination.ITINERARY, "other-plan")); advanceUntilIdle()
        m.editDayTitle(1); assertEquals("", m.state.value.dayTitleDraft?.value); m.dayTitleValue("Draft B"); m.cancelDayTitle()
        enter(m, f); advanceUntilIdle(); m.editDayTitle(1); assertEquals("Draft A", m.state.value.dayTitleDraft?.value)
        m.cancelDayTitle(); m.editDayTitle(1); assertEquals("Hari awal", m.state.value.dayTitleDraft?.value)
        m.cancelDayTitle(); m.editDayTitle(2); assertEquals("", m.state.value.dayTitleDraft?.value); assertTrue(f.titleWrites.isEmpty())
    }
    @Test fun actorChangeDuringPreflightCannotWriteOrResurrectTheOldDraft() = runTest {
        val f = TravelerPendingInputFixture(); val m = model(f); advanceUntilIdle(); enter(m, f); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); f.beforeLegal = { withContext(NonCancellable) { gate.await() } }
        m.editDayTitle(1); m.dayTitleValue("Old actor draft"); m.saveDayTitle(); runCurrent()
        f.base.identityState.value = f.base.identityState.value.copy(generation = 2); runCurrent(); gate.complete(Unit); advanceUntilIdle()
        assertNull(m.state.value.dayTitleDraft); assertTrue(f.titleWrites.isEmpty()); assertNull(m.state.value.writeFailure)
        f.beforeLegal = {}; m.refresh(); advanceUntilIdle(); m.editDayTitle(1); assertEquals("Hari awal", m.state.value.dayTitleDraft?.value)
    }
    @Test fun lateOldActorAcknowledgementCannotCloseOrRefreshTheNewActorEditor() = runTest {
        val f = TravelerPendingInputFixture(); val m = model(f); advanceUntilIdle(); enter(m, f); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); f.beforeTitle = { withContext(NonCancellable) { gate.await() } }
        m.editDayTitle(1); m.dayTitleValue("Old request"); m.saveDayTitle(); runCurrent(); assertEquals(1, f.titleWrites.size)
        f.base.identityState.value = f.base.identityState.value.copy(generation = 2); runCurrent(); m.refresh(); runCurrent()
        m.editDayTitle(1); m.dayTitleValue("New actor draft"); val reads = f.detailReads.size
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("New actor draft", m.state.value.dayTitleDraft?.value); assertEquals(reads, f.detailReads.size)
        assertFalse(m.state.value.saved); assertNull(m.state.value.writeFailure)
    }
}
