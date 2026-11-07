package com.floopel.traveler

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.domain.model.CatalogResult
import com.floopel.feature.traveler.domain.model.CoverProcessing
import com.floopel.feature.traveler.domain.model.QuestActivityKind
import com.floopel.feature.traveler.domain.model.TripMemberCapabilities
import com.floopel.feature.traveler.domain.model.TripVisibility
import kotlin.time.Instant
import com.floopel.feature.traveler.testing.TravelerFixture
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native Compose interaction tests with explicit domain doubles; no backend/provider success is implied. */
@RunWith(AndroidJUnit4::class)
class TravelerJourneyTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private lateinit var fixture: TravelerFixture
    private var sharedText: String? = null
    @Before fun launch() {
        fixture = TravelerFixture()
        ui.setContent { TravelerApp(fixture.runtime, { sharedText = it }, backHandler = { enabled, back -> BackHandler(enabled, back) }) }
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Halo, Traveler").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openPlan() {
        ui.onAllNodesWithText("Profile").onLast().performClick()
        ui.onNodeWithText("Itinerary saya").performScrollTo().performClick()
        ui.onNodeWithText("Rencana budaya simulasi").performClick()
        ui.onNodeWithTag("itinerary-title").assertTextEquals("Rencana budaya simulasi")
    }

    @Test fun communitySearchKeepsItsGeometryAcrossAllTabsAndTheOpenTripCatalog() {
        ui.onAllNodesWithText("Community").onLast().performClick()
        val publicSearch = ui.onNodeWithTag("catalog-search").fetchSemanticsNode().boundsInRoot
        val density = ui.activity.resources.displayMetrics.density
        assertEquals(48f * density, publicSearch.height, 1f)
        capture("community-public-search")
        ui.onNodeWithText("Open Trip publik").performClick()
        val openSearch = ui.onNodeWithTag("catalog-search").fetchSemanticsNode().boundsInRoot
        assertEquals(publicSearch, openSearch)
        capture("community-open-trip-search")
        ui.onNodeWithTag("community-trip-share").performScrollTo()
        capture("community-open-trip-actions")
        ui.onNodeWithTag("community-trip-share").performClick()
        assertTrue(sharedText.orEmpty().contains("Jelajah Magelang bersama"))
        assertTrue(sharedText.orEmpty().contains("14–16 Nov 2026"))
        ui.onNodeWithText("Curated Trip").performScrollTo().performClick()
        val curatedSearch = ui.onNodeWithTag("catalog-search").fetchSemanticsNode().boundsInRoot
        assertEquals(publicSearch, curatedSearch)
        capture("community-curated-search")
        ui.onAllNodesWithText("Open Trip").onLast().performClick()
        val rootSearch = ui.onNodeWithTag("catalog-search").fetchSemanticsNode().boundsInRoot
        assertEquals(publicSearch.left, rootSearch.left, 1f)
        assertEquals(publicSearch.width, rootSearch.width, 1f)
        assertEquals(publicSearch.height, rootSearch.height, 1f)
        ui.onNodeWithTag("trip-catalog-schedule").performScrollTo()
            .assertTextEquals("14–16 Nov 2026 · 2 dari 5 peserta + 1 organizer")
        ui.onNodeWithText("Bersama Arif Simulasi").assertExists()
        capture("open-trip-card")
    }

    @Test fun curatedCardUsesItsOwnPublicationLayoutAndFullWidthActions() {
        ui.onAllNodesWithText("Community").onLast().performClick()
        ui.onNodeWithText("Curated Trip").performClick()
        ui.onNodeWithText("Dikurasi Floopel").assertExists()
        ui.onNodeWithTag("curated-plan-copy").performScrollTo()
        val view = ui.onNodeWithTag("curated-plan-action").fetchSemanticsNode().boundsInRoot
        val copy = ui.onNodeWithTag("curated-plan-copy").fetchSemanticsNode().boundsInRoot
        assertEquals(view.width, copy.width, 1f)
        assertEquals(view.height, copy.height, 1f)
        capture("community-curated-actions")
        ui.onNodeWithTag("curated-plan-copy").performClick()
        ui.waitUntil(5_000) { fixture.cloneCalls.get() == 1 }
        assertEquals(fixture.curatedPlan.id, fixture.lastCloneId)
        ui.onNodeWithTag("itinerary-title").assertExists()
    }

    @Test fun publicCardActionsHaveEqualTargetsAndCopyTheSelectedPublication() {
        ui.onAllNodesWithText("Community").onLast().performClick()
        val headingBefore = ui.onNodeWithTag("community-heading").fetchSemanticsNode().boundsInRoot
        val navigationBefore = ui.onNodeWithTag("traveler-navigation").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("public-plan-copy").performScrollTo()
        val view = ui.onNodeWithTag("public-plan-action").fetchSemanticsNode().boundsInRoot
        val copy = ui.onNodeWithTag("public-plan-copy").fetchSemanticsNode().boundsInRoot
        val density = ui.activity.resources.displayMetrics.density
        assertEquals(48f * density, view.height, 1f)
        assertEquals(view.height, copy.height, 1f)
        assertEquals(view.top, copy.top, 1f)
        assertTrue(view.right < copy.left)
        capture("community-public-card-actions")
        ui.onNodeWithTag("traveler-viewport").performTouchInput { swipeUp() }
        val headingAfter = ui.onNodeWithTag("community-heading").fetchSemanticsNode().boundsInRoot
        assertTrue("Community heading must scroll with its publication", headingAfter.top < headingBefore.top)
        assertEquals(navigationBefore, ui.onNodeWithTag("traveler-navigation").fetchSemanticsNode().boundsInRoot)
        capture("community-public-card-scrolled")
        ui.onNodeWithTag("public-plan-copy").performClick()
        ui.waitUntil(5_000) { fixture.cloneCalls.get() == 1 }
        ui.onNodeWithTag("itinerary-title").assertExists()
        assertEquals(fixture.publicPlan.id, fixture.lastCloneId)
    }

    @Test fun profilePublicationKeepsItsOwnCardWhenFeedCopyIsAllowed() {
        ui.onAllNodesWithText("Community").onLast().performClick()
        ui.onNodeWithText("Arif Simulasi").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("profile-itinerary-action").fetchSemanticsNodes().size == 2 }
        ui.onNodeWithTag("public-plan-action").assertDoesNotExist()
        ui.onNodeWithTag("public-plan-copy").assertDoesNotExist()
        ui.onNodeWithText(fixture.publicPlan.description).assertDoesNotExist()
        ui.onAllNodesWithTag("profile-itinerary-action").onFirst().performScrollTo()
        capture("public-profile-itinerary-card")
        ui.onAllNodesWithTag("profile-itinerary-action").onFirst().performClick()
        ui.onNodeWithText(fixture.publicPlan.title).assertExists()
        assertEquals(0, fixture.cloneCalls.get())
    }

    @Test fun coversUseViewportWidthAndTitleKeepsItsOwnGutter() {
        openPlan()
        val viewport = ui.onNodeWithTag("traveler-viewport").fetchSemanticsNode().boundsInRoot
        val cover = ui.onNodeWithTag("itinerary-cover").fetchSemanticsNode().boundsInRoot
        val title = ui.onNodeWithTag("itinerary-title").fetchSemanticsNode().boundsInRoot
        val density = ui.activity.resources.displayMetrics.density
        assertEquals(viewport.left, cover.left, 1f)
        assertEquals(viewport.width, cover.width, 1f)
        assertEquals(20f * density, title.left - cover.left, 2f)
        capture("itinerary-builder")
    }

    @Test fun emptyPlanCannotEnterPublication() {
        ui.onAllNodesWithText("Profile").onLast().performClick()
        ui.onNodeWithText("Itinerary saya").performScrollTo().performClick()
        ui.onNodeWithText("Buat itinerary").performClick()
        ui.onNode(hasSetTextAction() and hasText("Judul itinerary")).performTextInput("Rencana kosong simulasi")
        ui.onAllNodesWithText("Buat itinerary").onLast().performClick()
        ui.waitUntil(5_000) { fixture.createCalls.get() == 1 }
        ui.onNodeWithText("Tinjau publikasi").performScrollTo().assertIsNotEnabled()
        assertEquals(0, fixture.publicationCalls.get())
        capture("empty-plan-guard")
    }

    @Test fun confirmedPublicTripRecoveryKeepsItsTypeAndRegistrationWindow() {
        val opens = Instant.parse("2026-11-07T01:00:00Z")
        val closes = Instant.parse("2026-11-13T01:00:00Z")
        fixture.trip = fixture.trip.copy(capabilities = TripMemberCapabilities(TripVisibility.PUBLIC, opens, closes, 5, 5, true, null, true))
        fixture.pendingCreatedTrip = fixture.trip
        openPlan()
        ui.onNodeWithText("Buat Trip dari itinerary").performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Trip ini sudah dibuat. Lanjutkan tanpa membuat Trip kedua.").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Open Trip publik").performScrollTo().assertIsOn().assertIsNotEnabled()
        ui.onNode(hasText("Pendaftaran dibuka (ISO 8601 dengan zona)")).performScrollTo().assertTextContains(opens.toString())
        ui.onNode(hasText("Pendaftaran ditutup (ISO 8601 dengan zona)")).performScrollTo().assertTextContains(closes.toString())
        assertEquals(0, fixture.tripCreateCalls.get())
        capture("public-trip-recovery")
    }

    @Test fun processingCoverBlocksPublishEvenAfterBothAcknowledgements() {
        fixture.coverProcessing = CoverProcessing.PROCESSING
        openPlan()
        ui.onNodeWithText("Tinjau publikasi").performScrollTo().performClick()
        ui.onNodeWithText("Saya sudah meninjau catatan yang akan dipublikasikan.").performScrollTo().performClick()
        ui.onNodeWithText("Tidak ada data pribadi yang tidak semestinya dibagikan.").performScrollTo().performClick()
        ui.onNodeWithText("Publikasikan").assertIsNotEnabled()
        assertEquals(0, fixture.publicationCalls.get())
        capture("publication-processing-guard")
    }

    @Test fun invalidArrivalIsRetainedAndZeroMinutesAreSavedAsZero() {
        openPlan()
        ui.onNodeWithText("Spot simulasi").performScrollTo().performClick()
        val arrival = ui.onNode(hasSetTextAction() and hasText("Waktu tiba"))
        arrival.performScrollTo().performTextReplacement("24:00")
        ui.onNode(hasSetTextAction() and hasText("Durasi (menit)")).performScrollTo().performTextReplacement("0")
        ui.onNodeWithText("Simpan Spot").performClick()
        ui.onNodeWithText("Gunakan hari/urutan mulai 1, waktu HH:mm yang sah, dan durasi bilangan bulat minimal 0 atau kosong.").performScrollTo().assertIsDisplayed()
        arrival.performScrollTo().assertTextContains("24:00")
        capture("stop-validation")
        arrival.performTextReplacement("08:00")
        ui.onNodeWithText("Simpan Spot").performClick()
        ui.waitUntil(5_000) { fixture.plan.stops.first().arriveTime == "08:00" }
        assertEquals(0, fixture.plan.stops.first().durationMinutes)
    }

    @Test fun addFeedbackReservesViewportAndReturnsToTheChosenPlan() {
        ui.onAllNodesWithText("Explorer").onLast().performClick()
        ui.onNodeWithText("Spot simulasi").performClick()
        ui.onNodeWithText("Tambahkan ke rencana").performScrollTo().performClick()
        ui.onNodeWithText("Rencana budaya simulasi").performClick()
        ui.onNode(hasSetTextAction() and hasText("Hari")).performTextReplacement("366")
        ui.onNodeWithText("Tambah Spot").assertIsEnabled()
        ui.onNode(hasSetTextAction() and hasText("Hari")).performTextReplacement("3")
        ui.onNodeWithText("Tambah Spot").performClick()
        ui.waitUntil(5_000) { fixture.stopCalls.get() == 1 }
        ui.onNodeWithText("Spot ditambahkan ke Rencana budaya simulasi · Hari 3").assertIsDisplayed()
        val viewport = ui.onNodeWithTag("traveler-viewport").fetchSemanticsNode().boundsInRoot
        val feedback = ui.onNodeWithTag("spot-feedback").fetchSemanticsNode().boundsInRoot
        assertTrue(viewport.bottom <= feedback.top + 1)
        capture("spot-added-footer")
        ui.onNodeWithText("Buka rencana").performClick()
        ui.onNodeWithTag("itinerary-title").assertTextEquals("Rencana budaya simulasi")
        assertEquals(3, fixture.plan.stops.last().day)
    }

    @Test fun removedMemberReadsOwnNoticeAndAcknowledgesOnlyTheInboxId() {
        ui.onNodeWithContentDescription("Notifikasi").performClick()
        assertEquals(0, fixture.ackCalls.get())
        ui.onNodeWithText("Keanggotaan Trip diakhiri").performScrollTo().performClick()
        ui.onNodeWithText("Keanggotaanmu telah diakhiri").assertIsDisplayed()
        ui.onNodeWithText("Kelola peserta").assertDoesNotExist()
        ui.onNodeWithText("Buka Trip").assertDoesNotExist()
        ui.waitUntil(5_000) { fixture.ackCalls.get() == 1 }
        assertEquals(fixture.inboxId, fixture.lastAcknowledged)
        assertNotEquals(fixture.noticeId, fixture.lastAcknowledged)
        capture("removed-member-notice")
    }

    @Test fun forbiddenTargetRemainsUnread() {
        fixture.noticeResult = CatalogResult.HttpError(403)
        ui.onNodeWithContentDescription("Notifikasi").performClick()
        ui.onNodeWithText("Keanggotaan Trip diakhiri").performScrollTo().performClick()
        ui.onNodeWithText("Halaman ini belum tersedia atau aksesmu telah berubah.").assertIsDisplayed()
        assertEquals(0, fixture.ackCalls.get())
        capture("notice-forbidden")
    }

    @Test fun uncertainGpsSubmissionOffersExplicitRetryWithoutAQuestionForm() {
        fixture.activityKind = QuestActivityKind.GPS
        fixture.gpsSubmissionPending = true
        ui.onAllNodesWithText("Quest").onFirst().performClick()
        ui.onNodeWithText("Jejak budaya simulasi").performClick()
        ui.onNodeWithText("Ambil Quest").performScrollTo().performClick()
        ui.onNodeWithText("Pelajari cerita Spot").performScrollTo().performClick()
        ui.onNodeWithText("Coba lagi").performScrollTo().assertIsEnabled()
        ui.onNode(hasSetTextAction()).assertDoesNotExist()
        capture("gps-uncertain-retry")
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = context.getExternalFilesDir("audit") ?: context.filesDir.resolve("audit")
        folder.mkdirs()
        folder.resolve("$name.png").outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
