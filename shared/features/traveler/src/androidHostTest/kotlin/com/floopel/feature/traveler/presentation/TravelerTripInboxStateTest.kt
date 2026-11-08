package com.floopel.feature.traveler.presentation

import androidx.lifecycle.viewModelScope
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.testing.TravelerTripInboxFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TravelerTripInboxStateTest {
    private fun runCase(block: suspend TestScope.(TravelerTripInboxFixture, TravelerViewModel) -> Unit) = runTest {
        val ui = StandardTestDispatcher(testScheduler); Dispatchers.setMain(ui)
        val f = TravelerTripInboxFixture(); val m = TravelerViewModel(f.runtime, worker = ui, now = { testScheduler.currentTime })
        try { advanceUntilIdle(); block(f, m) } finally { m.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    private fun inbox(m: TravelerViewModel) = m.open(TravelerRoute(Destination.INVITATIONS))
    @Test fun historyOnPageOneDoesNotHideTheOlderPendingRecipientRow() = runCase { f, m ->
        inbox(m); advanceUntilIdle(); assertEquals(20, m.state.value.recipientInvitations.value?.items?.size)
        m.more(); advanceUntilIdle()
        assertEquals(f.invitations, m.state.value.recipientInvitations.value?.items)
        assertEquals(listOf(null, f.invitations[19].id), f.cursors.toList()); assertNull(m.state.value.recipientInvitations.value?.nextCursor)
        assertTrue(f.invitationDecisions.isEmpty()); m.more(); advanceUntilIdle(); assertEquals(2, f.cursors.size)
    }
    @Test fun appendFailureKeepsRowsAndCursorAndRetryUsesExactlyThatCursor() = runCase { f, m ->
        inbox(m); advanceUntilIdle(); val before = m.state.value.recipientInvitations.value
        f.appendFailure = CatalogResult.HttpError(429, retryAfterSeconds = 2)
        m.more(); advanceUntilIdle(); assertEquals(before, m.state.value.recipientInvitations.value)
        assertNotNull(m.state.value.recipientInvitationsAppendFailure)
        m.retryRecipientInvitations(); runCurrent(); assertEquals(2, f.cursors.size)
        advanceTimeBy(2_001); f.appendFailure = null; m.retryRecipientInvitations(); advanceUntilIdle()
        assertEquals(listOf(null, before?.nextCursor, before?.nextCursor), f.cursors.toList())
        assertEquals(21, m.state.value.recipientInvitations.value?.items?.size); assertTrue(f.invitationDecisions.isEmpty())
    }
    @Test fun aFullFinalPageEndsWithAnEmptyReadAndRepeatedMoreIsIgnoredWhileBusy() = runCase { f, m ->
        f.invitations = f.invitations.take(20); inbox(m); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); f.beforeInvitations = { if (it != null) gate.await() }
        m.more(); m.more(); runCurrent(); assertEquals(2, f.cursors.size)
        gate.complete(Unit); advanceUntilIdle(); assertEquals(20, m.state.value.recipientInvitations.value?.items?.size)
        assertNull(m.state.value.recipientInvitations.value?.nextCursor)
    }
    @Test fun appendingOverlappingRowsKeepsServerOrderAndDistinctIds() = runCase { f, m ->
        inbox(m); advanceUntilIdle(); val first = f.invitations.take(20); val older = f.invitations.last()
        f.invitations = first + first.first() + older
        m.more(); advanceUntilIdle()
        assertEquals(first + older, m.state.value.recipientInvitations.value?.items)
        assertTrue(f.invitationDecisions.isEmpty())
    }
    @Test fun backDiscardsLateAppendAndOrganizerListNeverUsesRecipientCursor() = runCase { f, m ->
        inbox(m); advanceUntilIdle(); val gate = CompletableDeferred<Unit>()
        f.beforeInvitations = { withContext(NonCancellable) { gate.await() } }
        m.more(); runCurrent(); m.back(); runCurrent(); gate.complete(Unit); advanceUntilIdle()
        assertEquals(Destination.HOME, m.state.value.route.destination); assertEquals(20, m.state.value.recipientInvitations.value?.items?.size)
        m.open(TravelerRoute(Destination.INVITATIONS, f.base.tripId)); advanceUntilIdle(); m.more(); advanceUntilIdle()
        assertEquals(2, f.cursors.size); assertTrue(f.invitationDecisions.isEmpty())
    }
    @Test fun actorChangeClearsRowsAndCannotAdoptTheOldPage() = runCase { f, m ->
        inbox(m); advanceUntilIdle(); val gate = CompletableDeferred<Unit>()
        f.beforeInvitations = { withContext(NonCancellable) { gate.await() } }
        m.more(); runCurrent(); f.base.identity.logout(); runCurrent(); gate.complete(Unit); advanceUntilIdle()
        assertNull(m.state.value.recipientInvitations.value); assertEquals(Destination.LOGIN, m.state.value.route.destination)
        assertTrue(f.invitationDecisions.isEmpty())
    }
    @Test fun pendingApplicantReturnsOnlyToItsActualCatalogAndKeepsItsQuery() = runCase { f, m ->
        m.open(TravelerRoute(Destination.OPEN_TRIPS)); m.query("Magelang"); advanceUntilIdle()
        m.openTrip(f.base.tripId); advanceUntilIdle(); m.open(TravelerRoute(Destination.APPLY, f.base.tripId, "form")); advanceUntilIdle()
        m.apply("fixture"); advanceUntilIdle(); assertTrue(m.state.value.pendingOwnApplication)
        assertEquals(1, f.applyCalls.get()); assertTrue(m.state.value.applicationCatalogIndex != null)
        m.returnApplicationCatalog(); advanceUntilIdle()
        assertEquals(Destination.OPEN_TRIPS, m.state.value.route.destination); assertEquals("Magelang", m.state.value.query)
        assertTrue(f.decisions.isEmpty()); assertEquals(1, f.applyCalls.get())
    }
    @Test fun profileNotificationAndOrganizerEntriesCannotResetTheirOriginToACatalog() = runCase { f, m ->
        for (origin in listOf(Destination.PROFILE, Destination.NOTIFICATIONS, Destination.TRIP_APPLICATIONS)) {
            m.tab(Destination.PROFILE); m.open(TravelerRoute(origin, if (origin == Destination.TRIP_APPLICATIONS) f.base.tripId else ""))
            m.open(TravelerRoute(Destination.APPLICATION, f.application.id)); advanceUntilIdle()
            val stack = m.state.value.stack; assertNull(m.state.value.applicationCatalogIndex)
            m.returnApplicationCatalog(); advanceUntilIdle(); assertEquals(stack, m.state.value.stack)
        }
        m.tab(Destination.OPEN_TRIPS); m.open(TravelerRoute(Destination.NOTIFICATIONS))
        m.open(TravelerRoute(Destination.APPLICATION, f.application.id)); advanceUntilIdle()
        val fromNotification = m.state.value.stack
        assertNull(m.state.value.applicationCatalogIndex); m.returnApplicationCatalog(); advanceUntilIdle()
        assertEquals(fromNotification, m.state.value.stack)
        assertTrue(f.decisions.isEmpty()); assertEquals(0, f.applyCalls.get())
    }
    @Test fun currentMembershipTerminalAndOrganizerReadersNeverUseThePendingVariant() = runCase { f, m ->
        m.open(TravelerRoute(Destination.APPLICATION, f.application.id)); advanceUntilIdle(); assertTrue(m.state.value.pendingOwnApplication)
        for (item in listOf(f.application.copy(currentMember = true), f.application.copy(status = TripApplicationStatus.APPROVED),
            f.application.copy(viewer = TripApplicationViewer.ORGANIZER), f.application.copy(viewer = TripApplicationViewer.UNKNOWN))) {
            f.application = item; m.refresh(); advanceUntilIdle(); assertFalse(m.state.value.pendingOwnApplication)
        }
        assertTrue(f.decisions.isEmpty())
    }
}
