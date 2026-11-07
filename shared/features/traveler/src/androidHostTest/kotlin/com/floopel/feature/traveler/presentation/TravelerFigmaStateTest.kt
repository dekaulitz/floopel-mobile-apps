package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModelStore
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.presentation.account.notificationTimestamp
import com.floopel.feature.traveler.presentation.account.profileXpProgress
import com.floopel.feature.traveler.testing.TravelerFigmaFixture
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TravelerFigmaStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    @BeforeTest fun setup() { Dispatchers.setMain(dispatcher) }
    @AfterTest fun close() { owners.forEach(ViewModelStore::clear); Dispatchers.resetMain() }
    private fun model(f: TravelerFigmaFixture) = TravelerViewModel(f.runtime, worker = dispatcher).also { owners += ViewModelStore().apply { put("test", it) } }
    private fun inbox(m: TravelerViewModel) = m.open(TravelerRoute(Destination.NOTIFICATIONS))
    private fun ownProfile(m: TravelerViewModel, f: TravelerFigmaFixture) = m.open(TravelerRoute(Destination.PUBLIC_PROFILE, f.profile.username))
    private fun spot(m: TravelerViewModel, f: TravelerFigmaFixture) = m.openEntry(f.spot)

    @Test fun filteringAndAppendingTheInboxAreReadsAndAppendFailurePreservesExistingItems() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle()
        f.notificationResult = CatalogResult.Ready(NotificationPage(listOf(f.unread), 1, 2, 3, 2)); f.nextNotificationResult = CatalogResult.NetworkError
        inbox(m); advanceUntilIdle(); m.more(); advanceUntilIdle()
        assertEquals(listOf(f.unread.id), m.state.value.notifications.value?.items?.map { it.id }); assertNotNull(m.state.value.notifications.failure)
        f.nextNotificationResult = CatalogResult.Ready(NotificationPage(listOf(f.unread, f.read), 2, 2, 3, 2)); m.more(); advanceUntilIdle()
        assertEquals(listOf(f.unread.id, f.read.id), m.state.value.notifications.value?.items?.map { it.id })
        m.notificationFilter(NotificationClass.SYSTEM); advanceUntilIdle()
        assertTrue(f.reads.contains("inbox:1:system")); assertEquals(0, f.base.ackCalls.get())
    }
    @Test fun aLateInboxLookupCannotNavigateAfterBack() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle(); inbox(m); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); f.beforeLookup = { withContext(NonCancellable) { gate.await() } }
        m.openNotification(f.unread.id); runCurrent(); assertEquals(Destination.NOTIFICATION_OPEN, m.state.value.route.destination)
        m.back(); runCurrent(); gate.complete(Unit); advanceUntilIdle()
        assertEquals(Destination.NOTIFICATIONS, m.state.value.route.destination); assertEquals(0, f.base.ackCalls.get())
    }
    @Test fun aLateInboxLookupCannotRestoreContentAfterAnActorChange() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle(); inbox(m); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); f.beforeLookup = { withContext(NonCancellable) { gate.await() } }
        m.openNotification(f.unread.id); runCurrent(); f.base.identityState.value = TravelerIdentityView(generation = 2); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(Destination.LOGIN, m.state.value.route.destination); assertNull(m.state.value.notification.value); assertEquals(0, f.base.ackCalls.get())
    }
    @Test fun aNullTargetPreviewNeverAcknowledgesEvenAfterVisibleRefreshAndBack() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle(); inbox(m); advanceUntilIdle()
        f.lookupResult = CatalogResult.Ready(f.unread.copy(targetUrl = null)); m.openNotification(f.unread.id); advanceUntilIdle()
        assertEquals(Destination.NOTIFICATION_DETAIL, m.state.value.route.destination)
        m.targetVisible(m.state.value.route); m.refresh(); advanceUntilIdle(); m.targetVisible(m.state.value.route)
        m.returnToNotifications(); advanceUntilIdle(); assertEquals(0, f.base.ackCalls.get())
    }
    @Test fun unavailableMalformedAndFailedInboxTargetsNeverAcknowledge() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle(); inbox(m); advanceUntilIdle()
        for (result in listOf(CatalogResult.Ready(f.unread.copy(targetUrl = "https://untrusted.invalid/path")), CatalogResult.HttpError(403), CatalogResult.HttpError(404), CatalogResult.NetworkError)) {
            f.lookupResult = result; m.openNotification(f.unread.id); advanceUntilIdle()
            m.targetVisible(m.state.value.route); assertEquals(0, f.base.ackCalls.get()); m.returnToNotifications(); advanceUntilIdle()
        }
    }
    @Test fun onlyAVisibleAuthorizedDestinationAcknowledgesTheOriginalInboxIdentifierOnce() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle(); inbox(m); advanceUntilIdle()
        m.openNotification(f.unread.id); advanceUntilIdle()
        val route = m.state.value.route
        assertEquals(Destination.NOTICE, route.destination); assertEquals(f.base.noticeId, route.id); assertEquals(f.base.inboxId, route.inboxId)
        assertEquals(0, f.base.ackCalls.get()); m.targetVisible(route); m.targetVisible(route); advanceUntilIdle()
        assertEquals(1, f.base.ackCalls.get()); assertEquals(f.base.inboxId, f.base.lastAcknowledged)
    }
    @Test fun anInboxItemForADifferentIdentifierIsRejectedBeforeNavigation() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle(); inbox(m); advanceUntilIdle()
        f.lookupResult = CatalogResult.Ready(f.read); m.openNotification(f.unread.id); advanceUntilIdle()
        assertEquals(Destination.NOTIFICATION_OPEN, m.state.value.route.destination); assertEquals(502, m.state.value.notification.failure?.status); assertEquals(0, f.base.ackCalls.get())
    }
    @Test fun ownerTabsReadPrivateWorkingPlansAndSavedSpotsOnlyForTheMatchingOwner() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle(); ownProfile(m, f); advanceUntilIdle()
        assertTrue(m.state.value.ownsPublicProfile); m.publicProfileTab("private"); advanceUntilIdle()
        assertEquals(listOf(f.base.planId), m.state.value.publicPrivatePlans.value?.map { it.id }); assertTrue(f.reads.contains("private-plans"))
        m.publicProfileTab("saved"); advanceUntilIdle(); assertEquals(1, m.state.value.savedSpots.value?.spots?.size)
        m.removeProfileSavedSpot(f.spot.id); advanceUntilIdle(); assertEquals(1, f.savedWrites.get()); assertEquals(0, m.state.value.savedSpots.value?.spots?.size)
        f.reads.clear(); m.open(TravelerRoute(Destination.PUBLIC_PROFILE, "arif_simulasi", "private")); advanceUntilIdle()
        assertFalse(m.state.value.ownsPublicProfile); assertFalse(f.reads.contains("private-plans")); assertNotNull(m.state.value.publicPlans.value)
        m.publicProfileTab("saved"); advanceUntilIdle(); assertTrue(f.reads.none { it.startsWith("saved-spots:") })
    }
    @Test fun switchingToBadgeDuringIdentityLoadingRestartsReadsAndCannotLeaveLoadingStuck() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); val requests = AtomicInteger()
        f.beforePublicProfile = { if (requests.incrementAndGet() == 1) withContext(NonCancellable) { gate.await() } }
        ownProfile(m, f); runCurrent(); assertTrue(m.state.value.publicProfile.loading)
        m.publicProfileTab("badges"); runCurrent(); gate.complete(Unit); advanceUntilIdle()
        assertEquals("badges", m.state.value.route.extra); assertFalse(m.state.value.publicProfile.loading); assertFalse(m.state.value.profile.loading)
        assertEquals(3, m.state.value.publicBadges.value?.items?.size)
    }
    @Test fun aProgressReadAndRapidTabChangesRemainBoundToTheLatestSelection() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        f.beforePublicProgress = { if (calls.incrementAndGet() == 1) withContext(NonCancellable) { gate.await() } }
        ownProfile(m, f); runCurrent(); m.publicProfileTab("badges"); runCurrent(); m.publicProfileTab(""); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals("", m.state.value.route.extra); assertFalse(m.state.value.publicProgress.loading); assertNotNull(m.state.value.publicProgress.value); assertNotNull(m.state.value.publicPlans.value)
    }
    @Test fun anUnavailableAccountProfileDoesNotBlockPublicProfileContentOrGrantOwnerControls() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle(); f.profileResult = CatalogResult.NetworkError
        ownProfile(m, f); advanceUntilIdle(); assertFalse(m.state.value.ownsPublicProfile); assertNotNull(m.state.value.publicPlans.value)
        m.publicProfileTab("badges"); advanceUntilIdle(); assertEquals(3, m.state.value.publicBadges.value?.items?.size)
    }
    @Test fun anEmptyScopedSpotListNeverFallsBackToAnotherLocation() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle()
        m.open(TravelerRoute(Destination.CATALOG, "empty-location", CatalogKind.SPOT.name)); advanceUntilIdle()
        assertEquals("empty-location", m.state.value.route.id); assertEquals(0, m.state.value.catalog.value?.items?.size)
        assertTrue(f.reads.contains("browse:SPOT:empty-location:1")); m.query("Borobudur"); advanceUntilIdle()
        assertTrue(f.reads.contains("search:Borobudur:empty-location:1")); m.spotScope(false); advanceUntilIdle()
        assertEquals("", m.state.value.route.id); assertEquals("empty-location", m.state.value.route.locationContextId)
        assertTrue(f.reads.contains("search:Borobudur:null:1")); m.spotScope(true); advanceUntilIdle(); assertEquals("empty-location", m.state.value.route.id)
    }
    @Test fun togglingSpotScopeWhileTheLocationIsLoadingRestartsTheContextRead() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        f.beforeLocation = { if (calls.incrementAndGet() == 1) withContext(NonCancellable) { gate.await() } }
        m.open(TravelerRoute(Destination.CATALOG, f.location.id, CatalogKind.SPOT.name)); runCurrent(); m.spotScope(false); runCurrent()
        gate.complete(Unit); advanceUntilIdle(); assertFalse(m.state.value.spotScopeLocation.loading); assertEquals(f.location.id, m.state.value.spotScopeLocation.value?.id)
    }
    @Test fun relatedSpotReadsCarryTheSpotIdentifierInsteadOfItsGeographicLocation() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle(); spot(m, f); advanceUntilIdle()
        assertEquals(f.spot.id, m.state.value.spotContent.id); assertTrue(f.reads.contains("quests:${f.spot.id}")); assertTrue(f.reads.contains("articles:${f.spot.id}"))
        assertEquals(f.spot.id, f.tripQueries.last().spotId); assertNull(f.tripQueries.last().locationId)
    }
    @Test fun closingACommentSheetPreservesItsDraftAndReplyWithoutPersistingEitherInNavigation() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle(); spot(m, f); advanceUntilIdle()
        m.openSpotComments(); advanceUntilIdle(); m.spotCommentDraft("Draft simulasi pribadi"); m.replyToSpotComment(f.comment)
        m.closeSpotComments(); assertFalse(m.state.value.spotContent.comments.open); m.openSpotComments(); advanceUntilIdle()
        assertEquals("Draft simulasi pribadi", m.state.value.spotContent.comments.draft); assertEquals(f.comment.id, m.state.value.spotContent.comments.replyTo?.id)
        assertFalse(m.savedStack().contains("Draft simulasi pribadi")); assertEquals(0, f.commentCreates.get())
    }
    @Test fun commentSubmissionIsNotDuplicatedAndUsesTheConfirmedServerTotal() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle(); spot(m, f); advanceUntilIdle(); m.openSpotComments(); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); f.beforeCreateComment = { gate.await() }
        m.spotCommentDraft("Komentar simulasi"); m.sendSpotComment(); m.sendSpotComment(); runCurrent(); assertEquals(1, f.commentCreates.get())
        gate.complete(Unit); advanceUntilIdle(); assertEquals("", m.state.value.spotContent.comments.draft); assertEquals(1201L, m.state.value.detail.value?.commentCount)
    }
    @Test fun unknownCommentOutcomesRetainTheDraftAndBlockReplayingTheSameIntent() = runTest {
        for (throwException in listOf(false, true)) {
            val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle(); spot(m, f); advanceUntilIdle(); m.openSpotComments(); advanceUntilIdle()
            f.commentResult = CatalogResult.NetworkError; f.commentThrows = throwException
            m.spotCommentDraft("Pengiriman simulasi tidak pasti"); m.sendSpotComment(); advanceUntilIdle(); m.sendSpotComment(); advanceUntilIdle()
            assertEquals(1, f.commentCreates.get()); assertEquals("Pengiriman simulasi tidak pasti", m.state.value.spotContent.comments.draft)
        }
    }
    @Test fun aReplyLikeUpdatesTheLoadedReplyThreadFromTheServerResponse() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle(); spot(m, f); advanceUntilIdle(); m.openSpotComments(); advanceUntilIdle()
        m.spotCommentReplies(f.comment.id); advanceUntilIdle(); m.likeSpotComment(f.reply); advanceUntilIdle()
        val confirmed = m.state.value.spotContent.comments.replyPages[f.comment.id]?.value?.items?.single()
        assertEquals(true, confirmed?.liked); assertEquals(4, confirmed?.likeCount)
    }
    @Test fun profileWritesKeepTheConfirmedIdentityAndNeverInventMissingPreferences() = runTest {
        val f = TravelerFigmaFixture(); val m = model(f); advanceUntilIdle(); m.open(TravelerRoute(Destination.PROFILE_EDIT)); advanceUntilIdle()
        m.saveProfile(f.profile.copy(username = "traveler_baru")); advanceUntilIdle()
        assertEquals(1, f.profileWrites.get()); assertEquals("traveler_baru", m.state.value.profile.value?.username)
        f.preferences = TravelerSettings(null, false, true, null); m.open(TravelerRoute(Destination.PROFILE_PREFERENCES)); advanceUntilIdle()
        m.saveProfilePreferences(TravelerSettings(true, true, false, true)); advanceUntilIdle()
        assertEquals(TravelerSettings(null, true, false, null), f.savedPreferenceInput)
        m.open(TravelerRoute(Destination.PROFILE_INTERESTS)); advanceUntilIdle(); m.saveProfileInterests(setOf("interest-1", "interest-2")); advanceUntilIdle()
        assertEquals(0, f.interestWrites.get()); m.saveProfileInterests(setOf("interest-1", "interest-2", "unknown")); advanceUntilIdle(); assertEquals(0, f.interestWrites.get())
    }
    @Test fun profileProgressUsesAuthoritativeLevelBoundsAndRejectsUnknownOrOverflowingValues() {
        assertEquals(250L to 1000L, profileXpProgress(1250, 1000, 750))
        assertNull(profileXpProgress(1250, null, 750)); assertNull(profileXpProgress(1250, 1000, null))
        assertNull(profileXpProgress(100, 101, 1)); assertNull(profileXpProgress(Long.MAX_VALUE, 0, 1))
    }
    @Test fun notificationTimeUsesTheDeviceZoneAndLocalizedMonthAcrossMidnight() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Jakarta"))
            assertEquals("8 Nov 2026 · 01.10", notificationTimestamp(Instant.parse("2026-11-07T18:10:00Z"), "id-ID"))
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("7 Nov 2026 · 18.10", notificationTimestamp(Instant.parse("2026-11-07T18:10:00Z"), "id-ID"))
        } finally { TimeZone.setDefault(previous) }
    }
}
