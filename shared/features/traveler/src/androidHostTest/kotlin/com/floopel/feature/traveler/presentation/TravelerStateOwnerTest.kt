package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModelStore
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerCatalog
import com.floopel.feature.traveler.domain.repository.TravelerTripRepository
import com.floopel.feature.traveler.domain.repository.TravelerIdentity
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
    private fun model(fixture: TravelerFixture, runtime: TravelerRuntime = fixture.runtime, restoredStack: String = ""): TravelerViewModel =
        TravelerViewModel(runtime, restoredStack, worker = dispatcher).also { model -> owners += ViewModelStore().apply { put("test", model) } }

    @Test fun emptyPlanIsRejectedAgainAtPublicationConfirmation() = runTest {
        val f = TravelerFixture(); f.plan = f.plan.copy(stops = emptyList(), itinerary = f.plan.itinerary.copy(stops = 0))
        val model = model(f); advanceUntilIdle()
        model.open(TravelerRoute(Destination.PUBLICATION, f.planId)); advanceUntilIdle()
        model.publish(true, true, true); advanceUntilIdle()
        assertEquals(0, f.publicationCalls.get())
        assertEquals("EMPTY_ITINERARY", model.state.value.writeFailure?.code)
    }

    @Test fun coldGuestWaitsForRestoreAndCannotReadOrNavigateOutsideAuthentication() = runTest {
        val f = TravelerFixture(); f.identityState.value = TravelerIdentityView(generation = 1)
        val released = CompletableDeferred<Unit>()
        var discoveryCalls = 0
        val identity = object : TravelerIdentity by f.runtime.identity {
            override suspend fun restore() { released.await() }
        }
        val catalog = object : TravelerCatalog by f.catalog {
            override suspend fun discoverySpots(locale: String, center: GeoPoint?, locationId: String?): CatalogResult<List<CatalogEntry>> {
                discoveryCalls++; return CatalogResult.Ready(listOf(f.spot))
            }
        }
        val base = f.runtime
        val runtime = TravelerRuntime(identity, catalog, base.itineraries, base.distribution, base.trips, base.notifications,
            base.account, base.legal, base.registration, base.publicContent, base.savedSpots, base.questGroups, base.newOperationKey)
        val restored = """[{"destination":"HOME"},{"destination":"ITINERARY","id":"${f.planId}"}]"""
        val model = model(f, runtime, restored); runCurrent()
        assertFalse(model.state.value.identityRestored); assertEquals(0, discoveryCalls)
        model.refresh(); model.tab(Destination.COMMUNITY); assertEquals(0, discoveryCalls)
        released.complete(Unit); advanceUntilIdle()
        assertTrue(model.state.value.identityRestored)
        assertEquals(listOf(TravelerRoute(Destination.LOGIN)), model.state.value.stack)
        for (destination in listOf(Destination.HOME, Destination.COMMUNITY, Destination.OPEN_TRIPS, Destination.PUBLIC_PLAN)) {
            model.open(TravelerRoute(destination)); model.back(); model.refresh(); advanceUntilIdle()
            assertEquals(Destination.LOGIN, model.state.value.route.destination)
        }
        assertEquals(0, discoveryCalls); assertNull(model.state.value.itinerary.value)
        model.open(TravelerRoute(Destination.FORGOT)); advanceUntilIdle()
        assertEquals(Destination.FORGOT, model.state.value.route.destination)
        model.back(); assertEquals(Destination.LOGIN, model.state.value.route.destination)
        model.open(TravelerRoute(Destination.REGISTER)); advanceUntilIdle()
        assertEquals(Destination.REGISTER, model.state.value.route.destination)
    }

    @Test fun expiredSessionClearsContentAndBackCannotBypassLogin() = runTest {
        val f = TravelerFixture(); val model = model(f); advanceUntilIdle()
        model.tab(Destination.PROFILE); advanceUntilIdle()
        model.open(TravelerRoute(Destination.ITINERARY, f.planId)); advanceUntilIdle()
        assertNotNull(model.state.value.itinerary.value)
        f.identityState.value = TravelerIdentityView(generation = 2); advanceUntilIdle()
        assertEquals(listOf(TravelerRoute(Destination.LOGIN)), model.state.value.stack)
        assertEquals(Destination.HOME, model.state.value.tab); assertFalse(model.state.value.canGoBack)
        assertNull(model.state.value.itinerary.value); assertNull(model.state.value.profile.value)
        model.back(); model.tab(Destination.EXPLORER); model.refresh(); advanceUntilIdle()
        assertEquals(Destination.LOGIN, model.state.value.route.destination)
        model.login("fixture@example.test", "synthetic-password"); advanceUntilIdle()
        assertTrue(model.state.value.identity.signedIn)
        assertEquals(listOf(TravelerRoute(Destination.HOME)), model.state.value.stack)
        assertEquals(Destination.HOME, model.state.value.tab)
    }

    @Test fun validRestoredSessionKeepsTheProtectedRouteUntilItsReadCompletes() = runTest {
        val f = TravelerFixture(); f.identityState.value = TravelerIdentityView(generation = 1)
        val released = CompletableDeferred<Unit>()
        val identity = object : TravelerIdentity by f.runtime.identity {
            override suspend fun restore() {
                released.await(); f.identityState.value = TravelerIdentityView(signedIn = true, generation = 2)
            }
        }
        val base = f.runtime
        val runtime = TravelerRuntime(identity, base.catalog, base.itineraries, base.distribution, base.trips, base.notifications,
            base.account, base.legal, base.registration, base.publicContent, base.savedSpots, base.questGroups, base.newOperationKey)
        val restored = """[{"destination":"PROFILE"},{"destination":"ITINERARY","id":"${f.planId}"}]"""
        val model = model(f, runtime, restored); runCurrent()
        assertFalse(model.state.value.identityRestored); assertNull(model.state.value.itinerary.value)
        released.complete(Unit); advanceUntilIdle()
        assertTrue(model.state.value.identityRestored); assertTrue(model.state.value.identity.signedIn)
        assertEquals(Destination.ITINERARY, model.state.value.route.destination)
        assertEquals(Destination.PROFILE, model.state.value.tab)
        assertEquals(f.planId, model.state.value.itinerary.value?.itinerary?.id)
    }

    @Test fun feedCopyUsesItsOwnPublicIdAndHonorsTheServerCapability() = runTest {
        val f = TravelerFixture(); val model = model(f); advanceUntilIdle()
        model.clonePublicPlan(f.publicPlan.copy(allowClone = false)); model.clonePublicPlan(f.publicPlan.copy(allowClone = null))
        advanceUntilIdle(); assertEquals(0, f.cloneCalls.get())
        model.clonePublicPlan(f.publicPlan); model.clonePublicPlan(f.publicPlan); advanceUntilIdle()
        assertEquals(1, f.cloneCalls.get()); assertEquals(f.publicPlan.id, f.lastCloneId)
        assertEquals(Destination.ITINERARY, model.state.value.route.destination)
        model.cloneCuratedPlan(f.curatedPlan.copy(allowClone = null)); model.cloneCuratedPlan(f.spot.copy(allowClone = true))
        advanceUntilIdle(); assertEquals(1, f.cloneCalls.get())
        model.cloneCuratedPlan(f.curatedPlan); advanceUntilIdle()
        assertEquals(2, f.cloneCalls.get()); assertEquals(f.curatedPlan.id, f.lastCloneId)
        f.identityState.value = TravelerIdentityView(generation = 2); advanceUntilIdle()
        model.clonePublicPlan(f.publicPlan); advanceUntilIdle()
        assertEquals(2, f.cloneCalls.get()); assertEquals(Destination.LOGIN, model.state.value.route.destination)
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

    @Test fun processLossDuringBannerAfterJournalClearRecoversSavedCreationWithoutAnotherPost() = runTest {
        val f = TravelerFixture()
        val committed = mutableMapOf<String, TravelerTrip>()
        val bannerStarted = CompletableDeferred<Unit>()
        val trips = object : TravelerTripRepository by f.trips {
            override suspend fun create(itineraryId: String, draft: TripDraft, key: String): CatalogResult<TravelerTrip> =
                f.trips.create(itineraryId, draft, key).also { if (it is CatalogResult.Ready) committed[key] = it.value }
            override suspend fun createdTripForOperation(key: String) = CatalogResult.Ready(committed[key])
            override suspend fun uploadBanner(id: String, bytes: ByteArray, mimeType: String, key: String): CatalogResult<TripBannerStatus> {
                bannerStarted.complete(Unit); awaitCancellation()
            }
        }
        val base = f.runtime
        val runtime = TravelerRuntime(base.identity, base.catalog, base.itineraries, base.distribution, trips,
            base.notifications, base.account, base.legal, base.registration, base.publicContent, base.savedSpots, base.questGroups, base.newOperationKey)
        val first = model(f, runtime); advanceUntilIdle()
        first.open(TravelerRoute(Destination.TRIP_FORM, f.planId)); advanceUntilIdle()
        val savedBeforeSubmit = first.savedStack()
        val routeKey = first.state.value.route.operationKey
        assertTrue(routeKey.isNotBlank())
        val draft = TripDraft("Trip simulasi", "2026-11-14", "2026-11-15", "Asia/Jakarta")
        first.createTrip(draft, TravelerImage(byteArrayOf(1), "image/png")); runCurrent(); bannerStarted.await()
        assertNull(f.pendingCreatedTrip)
        owners.last().clear(); runCurrent()
        val restored = model(f, runtime, savedBeforeSubmit); advanceUntilIdle()
        assertEquals(routeKey, restored.state.value.route.operationKey)
        assertEquals(f.tripId, restored.state.value.tripRecovery.value?.trip?.id)
        restored.createTrip(draft); advanceUntilIdle()
        assertEquals(1, f.tripCreateCalls.get())
        assertEquals(Destination.TRIP, restored.state.value.route.destination)
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

    @Test fun builderOpenedFromSpotFeedbackReturnsToThatSpot() = runTest {
        val f = TravelerFixture(); val model = model(f); advanceUntilIdle()
        model.openEntry(f.spot); advanceUntilIdle()
        val origin = model.state.value.route
        model.open(TravelerRoute(Destination.PICK_PLAN, extra = f.spotId)); advanceUntilIdle()
        model.addSpot(f.planId, f.spotId, 3); advanceUntilIdle()
        model.open(TravelerRoute(Destination.ITINERARY, f.planId, day = 3)); advanceUntilIdle()
        model.back(); advanceUntilIdle()
        assertEquals(origin, model.state.value.route)
        assertEquals(1, f.stopCalls.get())
    }

    @Test fun deletingTheEditedStopReturnsToItsBuilder() = runTest {
        val f = TravelerFixture(); val model = model(f); advanceUntilIdle()
        model.open(TravelerRoute(Destination.ITINERARY, f.planId)); advanceUntilIdle()
        val stop = f.plan.stops.single()
        model.open(TravelerRoute(Destination.STOP_FORM, f.planId, stop.id)); advanceUntilIdle()
        model.removeStop(stop.id); advanceUntilIdle()
        assertEquals(Destination.ITINERARY, model.state.value.route.destination)
        assertEquals(f.planId, model.state.value.route.id)
        assertTrue(model.state.value.itinerary.value!!.stops.isEmpty())
    }

    @Test fun refreshReconcilesAnAlreadyLoadedQuestReaderWithoutSubmittingProofOrAnswer() = runTest {
        val f = TravelerFixture(); var activityReads = 0; var progressReads = 0
        val identity = object : TravelerIdentity by f.identity {
            override suspend fun activity(id: String) { activityReads++; f.identity.activity(id) }
            override suspend fun continueQuest(id: String) { progressReads++; f.identity.continueQuest(id) }
            override suspend fun submitGps() { fail("Reader retry must not submit proof") }
            override suspend fun answer(value: String) { fail("Reader retry must not answer") }
        }
        val base = f.runtime
        val runtime = TravelerRuntime(identity, base.catalog, base.itineraries, base.distribution, base.trips,
            base.notifications, base.account, base.legal, base.registration, base.publicContent, base.savedSpots, base.questGroups, base.newOperationKey)
        val model = model(f, runtime); advanceUntilIdle()
        model.open(TravelerRoute(Destination.QUEST_PROGRESS, "participation")); advanceUntilIdle()
        val beforeProgress = progressReads
        model.refresh(); advanceUntilIdle()
        assertEquals(beforeProgress + 1, progressReads)
        f.gpsSubmissionPending = true; f.activityKind = QuestActivityKind.GPS
        model.activity("activity"); advanceUntilIdle()
        val beforeActivity = activityReads
        model.refresh(); advanceUntilIdle()
        assertEquals(beforeActivity + 1, activityReads)
        assertTrue(model.state.value.identity.gpsSubmissionPending)
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

    @Test fun legacyCreationRouteGetsAStableIdentityAcrossRepeatedRestoration() {
        val f = TravelerFixture()
        val oldStack = """[{"destination":"HOME"},{"destination":"TRIP_FORM","id":"${f.planId}"}]"""
        val first = TravelerViewModel(f.runtime, oldStack, dispatcher, autoStart = false)
        val second = TravelerViewModel(f.runtime, oldStack, dispatcher, autoStart = false)
        owners += ViewModelStore().apply { put("first", first); put("second", second) }
        assertEquals(first.state.value.route.operationKey, second.state.value.route.operationKey)
        assertTrue(first.state.value.route.operationKey.isNotBlank())
    }

    @Test fun savedOperationResultTakesPrecedenceOverAnotherPendingCreateForTheSamePlan() = runTest {
        val f = TravelerFixture()
        f.routeCreatedTrip = f.trip
        f.pendingCreatedTrip = f.trip.copy(id = "another-trip")
        f.pendingTripDraft = TripDraft("Another pending Trip", "2026-12-01", "2026-12-02", "Asia/Jakarta")
        val model = model(f); advanceUntilIdle()
        model.open(TravelerRoute(Destination.TRIP_FORM, f.planId)); advanceUntilIdle()
        assertEquals(f.trip.id, model.state.value.tripRecovery.value?.trip?.id)
        assertNull(model.state.value.tripRecovery.value?.draft)
        model.createTrip(TripDraft("Ignored", "2026-11-14", "2026-11-15", "Asia/Jakarta")); advanceUntilIdle()
        assertEquals(f.trip.id, model.state.value.route.id)
        assertEquals(0, f.tripCreateCalls.get())
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
