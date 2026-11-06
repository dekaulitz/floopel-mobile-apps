package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModelStore
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerCatalog
import com.floopel.feature.traveler.testing.TravelerFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TravelerStateOwnerTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    @BeforeTest fun setUp() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun tearDown() { owners.forEach(ViewModelStore::clear); Dispatchers.resetMain() }
    private fun model(fixture: TravelerFixture, runtime: TravelerRuntime = fixture.runtime): TravelerViewModel =
        TravelerViewModel(runtime, worker = dispatcher).also { model -> owners += ViewModelStore().apply { put("test", model) } }

    @Test fun emptyPlanIsRejectedAgainAtPublicationConfirmation() = runTest {
        val f = TravelerFixture(); f.plan = f.plan.copy(stops = emptyList(), itinerary = f.plan.itinerary.copy(stops = 0))
        val model = model(f); advanceUntilIdle()
        model.open(TravelerRoute(Destination.PUBLICATION, f.planId)); advanceUntilIdle()
        model.publish(true, true, true); advanceUntilIdle()
        assertEquals(0, f.publicationCalls.get())
        assertEquals("EMPTY_ITINERARY", model.state.value.writeFailure?.code)
    }

    @Test fun inviteeDoesNotRequestWorkingPlan() = runTest {
        val f = TravelerFixture(); f.trip = f.trip.copy(role = TripCallerRole.INVITEE)
        val model = model(f); advanceUntilIdle()
        model.open(TravelerRoute(Destination.TRIP, f.tripId)); advanceUntilIdle()
        assertEquals(TripCallerRole.INVITEE, model.state.value.trip.value?.role)
        assertEquals(0, f.workingPlanCalls.get()); assertNull(model.state.value.plan.value)
    }

    @Test fun confirmedTripRecoveryAndBannerFailureNeverCreateAnotherTrip() = runTest {
        val f = TravelerFixture(); f.pendingCreatedTrip = f.trip; f.bannerResult = CatalogResult.NetworkError
        val model = model(f); advanceUntilIdle()
        model.open(TravelerRoute(Destination.TRIP_FORM, f.planId)); advanceUntilIdle()
        assertEquals(f.tripId, model.state.value.tripRecovery.value?.trip?.id)
        model.createTrip(TripDraft("Trip simulasi", "2026-11-14", "2026-11-15", "Asia/Jakarta"), TravelerImage(byteArrayOf(1, 2), "image/png"))
        advanceUntilIdle()
        assertEquals(0, f.tripCreateCalls.get()); assertEquals(1, f.bannerCalls.get())
        assertEquals(Destination.TRIP, model.state.value.route.destination); assertEquals(f.tripId, model.state.value.route.id)
        assertTrue(model.state.value.mediaPartial)
    }

    @Test fun tripDateValidationRejectsInvalidCalendarAndReversedIntervals() {
        assertFalse(com.floopel.feature.traveler.presentation.trip.validTripDates("2026-02-30", "2026-03-01"))
        assertFalse(com.floopel.feature.traveler.presentation.trip.validTripDates("2026-12-02", "2026-12-01"))
        assertTrue(com.floopel.feature.traveler.presentation.trip.validTripDates("2026-12-01", "2026-12-01"))
        assertFalse(com.floopel.feature.traveler.presentation.trip.validRegistrationWindow("2026-12-02T08:00:00+07:00", "2026-12-01T08:00:00+07:00"))
    }

    @Test fun inboxAndReaderFetchesDoNotAcknowledgeUntilVisible() = runTest {
        val f = TravelerFixture(); val model = model(f); advanceUntilIdle()
        model.open(TravelerRoute(Destination.NOTIFICATIONS)); advanceUntilIdle()
        assertEquals(0, f.ackCalls.get())
        model.openNotification(f.inboxId); advanceUntilIdle()
        val route = model.state.value.route
        assertEquals(Destination.NOTICE, route.destination); assertEquals(f.noticeId, route.id)
        assertEquals(0, f.ackCalls.get())
        model.targetVisible(route); model.targetVisible(route); advanceUntilIdle()
        assertEquals(1, f.ackCalls.get()); assertEquals(f.inboxId, f.lastAcknowledged)
        assertNotEquals(route.id, f.lastAcknowledged)
    }

    @Test fun unavailableReaderAndLateVisibilityAfterLogoutDoNotAcknowledge() = runTest {
        val f = TravelerFixture(); f.noticeResult = CatalogResult.HttpError(403)
        val model = model(f); advanceUntilIdle()
        model.openNotification(f.inboxId); advanceUntilIdle()
        model.targetVisible(model.state.value.route); advanceUntilIdle()
        assertEquals(0, f.ackCalls.get())
        f.noticeResult = CatalogResult.Ready(f.notice); model.refresh(); advanceUntilIdle()
        val previous = model.state.value.route
        f.identityState.value = TravelerIdentityView(generation = 2); advanceUntilIdle()
        model.targetVisible(previous); advanceUntilIdle()
        assertEquals(0, f.ackCalls.get()); assertNull(model.state.value.notice.value)
    }

    @Test fun revokedReaderHidesPreviouslyLoadedPrivateContent() = runTest {
        val f = TravelerFixture(); val model = model(f); advanceUntilIdle()
        model.openNotification(f.inboxId); advanceUntilIdle()
        assertNotNull(model.state.value.notice.value)
        f.noticeResult = CatalogResult.HttpError(403)
        model.refresh(); advanceUntilIdle()
        assertNull(model.state.value.notice.value)
        model.targetVisible(model.state.value.route); advanceUntilIdle()
        assertEquals(0, f.ackCalls.get())
    }

    @Test fun twoCreateTapsIssueOneMutation() = runTest {
        val f = TravelerFixture(); val model = model(f); advanceUntilIdle()
        model.open(TravelerRoute(Destination.ITINERARY_FORM)); advanceUntilIdle()
        model.saveItinerary(ItineraryDraft("Rencana baru", "Deskripsi"))
        model.saveItinerary(ItineraryDraft("Rencana baru", "Deskripsi")); advanceUntilIdle()
        assertEquals(1, f.createCalls.get()); assertEquals(Destination.ITINERARY, model.state.value.route.destination)
    }

    @Test fun builderBackReturnsToOwnedListWithoutReplayingPublication() = runTest {
        val f = TravelerFixture(); val model = model(f); advanceUntilIdle()
        model.open(TravelerRoute(Destination.ITINERARY, f.planId)); advanceUntilIdle()
        model.open(TravelerRoute(Destination.PUBLICATION, f.planId)); advanceUntilIdle()
        model.publish(true, true, true); advanceUntilIdle()
        model.back(); advanceUntilIdle(); model.back(); advanceUntilIdle()
        assertEquals(Destination.ITINERARIES, model.state.value.route.destination)
        assertEquals(1, f.publicationCalls.get())
    }

    @Test fun addSpotFeedbackRetainsTheSelectedPlanAndDay() = runTest {
        val f = TravelerFixture(); val model = model(f); advanceUntilIdle()
        model.openEntry(f.spot); advanceUntilIdle()
        model.open(TravelerRoute(Destination.PICK_PLAN, extra = f.spotId)); advanceUntilIdle()
        model.addSpot(f.planId, f.spotId, 4); advanceUntilIdle()
        assertEquals(Destination.DETAIL, model.state.value.route.destination)
        assertEquals(SpotAdded(f.planId, f.plan.itinerary.title, 4), model.state.value.feedback)
        assertEquals(4, f.plan.stops.last().day); assertEquals(1, f.stopCalls.get())
    }

    @Test fun tabRestorationRetainsOnlyNavigationContext() {
        val f = TravelerFixture()
        val serialized = """[{"destination":"PROFILE"},{"destination":"ITINERARIES"}]"""
        val model = TravelerViewModel(f.runtime, serialized, dispatcher, autoStart = false)
        owners += ViewModelStore().apply { put("test", model) }
        assertEquals(Destination.PROFILE, model.state.value.tab)
        assertFalse(model.savedStack().contains("Traveler Simulasi"))
        assertFalse(model.savedStack().contains("passport"))
    }

    @Test fun aCancelledDetailCannotReplaceANewerDestination() = runTest {
        val f = TravelerFixture(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val catalog = object : TravelerCatalog by f.catalog {
            override suspend fun detail(item: CatalogEntry, locale: String): CatalogResult<CatalogEntry> {
                if (item.id == "slow") { started.complete(Unit); withContext(NonCancellable) { release.await() } }
                return CatalogResult.Ready(item.copy(detailLoaded = true))
            }
        }
        val runtime = f.runtime.let { TravelerRuntime(it.identity, catalog, it.itineraries, it.distribution, it.trips,
            it.notifications, it.account, it.legal, it.registration, it.publicContent, it.savedSpots, it.questGroups, it.newOperationKey) }
        val model = model(f, runtime); advanceUntilIdle()
        model.openEntry(f.spot.copy(id = "slow")); runCurrent(); started.await()
        model.openEntry(f.spot.copy(id = "fast")); runCurrent(); release.complete(Unit); advanceUntilIdle()
        assertEquals("fast", model.state.value.detail.value?.id)
    }
}
