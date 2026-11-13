package com.floopel.traveler

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowCompat
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.testing.TravelerFigmaFixture
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Figma geometry and native interactions with synthetic domain repositories; no live provider is implied. */
@RunWith(AndroidJUnit4::class)
class TravelerFigmaDesignTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private fun launch(f: TravelerFigmaFixture = TravelerFigmaFixture(), scale: Float = 1f): TravelerFigmaFixture {
        ui.runOnUiThread {
            WindowCompat.enableEdgeToEdge(ui.activity.window)
            WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView).run {
                isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
            }
        }
        ui.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                TravelerApp(f.runtime, {}, backHandler = { enabled, back -> BackHandler(enabled, back) })
            }
        }
        ui.waitUntil(8_000) { ui.onAllNodesWithContentDescription("Notifikasi").fetchSemanticsNodes().isNotEmpty() }
        return f
    }
    private fun back() { ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() } }
    private fun profile() { ui.onAllNodesWithText("Profile").onLast().performClick(); ui.onNodeWithTag("profile-avatar").assertExists() }
    private fun publicProfile() {
        profile(); ui.onNodeWithText("Profil publik").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("public-profile-manage").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun openSpot() { ui.onAllNodesWithText("Spot simulasi").onFirst().performClick(); ui.onNodeWithText("Tentang Spot").performScrollTo() }
    private fun capture(name: String) {
        ui.waitForIdle()
        val folder = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("figma-audit"))
        folder.mkdirs()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        folder.resolve("$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun scrollSpotTo(text: String) {
        ui.onNodeWithTag("spot-detail-content").performScrollToNode(hasText(text))
        ui.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    @Test fun inboxUses48DpFiltersAndGovernedReadStatusWithASeparateUnavailableDevicePage() {
        val f = launch(); ui.onNodeWithContentDescription("Notifikasi").performClick()
        ui.onNodeWithText("Undangan perjalanan simulasi").assertExists(); ui.onNodeWithText("Sudah dibaca").assertExists()
        val density = ui.activity.resources.displayMetrics.density
        assertEquals(48f * density, ui.onNodeWithTag("notification-filter-all").fetchSemanticsNode().boundsInRoot.height, 1f)
        capture("notifications-all")
        ui.onNodeWithTag("notification-filter-system").performClick()
        ui.onNodeWithText("Undangan perjalanan simulasi").assertDoesNotExist(); ui.onNodeWithText("Pembaruan sistem simulasi").assertExists()
        assertEquals(0, f.base.ackCalls.get()); capture("notifications-system")
        ui.onNodeWithContentDescription("Pengaturan notifikasi perangkat").performClick()
        ui.onNodeWithText("Notifikasi perangkat belum tersedia").assertIsDisplayed(); capture("notifications-device-unavailable")
        ui.onNodeWithText("Lihat notifikasi").performClick(); ui.onNodeWithTag("notification-inbox").assertExists()
    }
    @Test fun emptyAndFailedInboxUseTheFigmaIllustrationAndFunctionalActions() {
        val f = TravelerFigmaFixture(); f.notificationResult = CatalogResult.Ready(NotificationPage(emptyList(), 1, 20, 0, 0)); launch(f)
        ui.onNodeWithContentDescription("Notifikasi").performClick(); ui.onNodeWithText("Belum ada notifikasi").assertIsDisplayed(); capture("notifications-empty")
        ui.onNodeWithText("Kembali ke Home").performClick(); f.notificationResult = CatalogResult.NetworkError
        ui.onNodeWithContentDescription("Notifikasi").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Notifikasi belum bisa dimuat").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Notifikasi belum bisa dimuat").assertIsDisplayed(); capture("notifications-error")
        f.notificationResult = CatalogResult.Ready(NotificationPage(listOf(f.unread), 1, 20, 1, 1))
        ui.onNodeWithText("Coba lagi").performClick(); ui.onNodeWithText("Undangan perjalanan simulasi").assertExists(); assertEquals(0, f.base.ackCalls.get())
    }
    @Test fun aNullDestinationPreviewStaysUnreadAfterAnActualRenderedFrameAndBack() {
        val f = TravelerFigmaFixture(); f.lookupResult = CatalogResult.Ready(f.unread.copy(targetUrl = null)); launch(f)
        ui.onNodeWithContentDescription("Notifikasi").performClick(); ui.onNodeWithText("Undangan perjalanan simulasi").performClick()
        ui.onNodeWithText("Detail notifikasi").assertIsDisplayed(); capture("notifications-null-target-preview")
        assertEquals(0, f.base.ackCalls.get()); ui.onNodeWithText("Kembali ke notifikasi").performClick(); ui.waitForIdle(); assertEquals(0, f.base.ackCalls.get())
    }
    @Test fun notificationLookupShowsOpeningAndUnavailableWithoutAcknowledgement() {
        val f = TravelerFigmaFixture(); val gate = CompletableDeferred<Unit>(); f.beforeLookup = { gate.await() }; f.lookupResult = CatalogResult.HttpError(404); launch(f)
        ui.onNodeWithContentDescription("Notifikasi").performClick(); ui.onNodeWithText("Undangan perjalanan simulasi").performClick()
        ui.onNodeWithText("Memeriksa pembaruanmu").assertIsDisplayed(); capture("notifications-opening")
        gate.complete(Unit); ui.waitForIdle(); ui.onNodeWithText("Notifikasi tidak tersedia").assertIsDisplayed(); capture("notifications-unavailable"); assertEquals(0, f.base.ackCalls.get())
    }
    @Test fun personalProfileHasThe72DpIdentityAndCompactActionsWithItsPinnedHeader() {
        launch(); profile(); val density = ui.activity.resources.displayMetrics.density
        val avatar = ui.onNodeWithTag("profile-avatar").fetchSemanticsNode().boundsInRoot
        assertEquals(72f * density, avatar.width, 1f); assertEquals(72f * density, avatar.height, 1f)
        ui.onNodeWithText("Edit profil").assertIsDisplayed(); ui.onNodeWithText("250 / 1.000 XP menuju level berikutnya").assertExists(); capture("profile-personal")
        ui.onNodeWithText("Edit profil").performClick(); ui.onNodeWithText("Simpan profil").performScrollTo().assertIsNotEnabled(); capture("profile-editor")
    }
    @Test fun ownerPublicProfileHasAllFourStatesAndPrivateItinerariesOpenTheWorkingPlan() {
        val f = launch(); publicProfile(); val density = ui.activity.resources.displayMetrics.density
        assertEquals(64f * density, ui.onNodeWithTag("public-profile-avatar").fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals(48f * density, ui.onNodeWithTag("profile-tab-itineraries").fetchSemanticsNode().boundsInRoot.height, 1f); capture("public-profile-owner-published")
        ui.onNodeWithText("Pribadi").performClick(); ui.onNodeWithText("Hanya kamu yang dapat melihat itinerary ini.").assertExists(); capture("public-profile-owner-private")
        ui.onNodeWithText("Lihat itinerary").performScrollTo().performClick(); ui.onNodeWithTag("itinerary-title").assertTextEquals("Rencana budaya simulasi")
        // The itinerary flow returns through its native list; reopen the owner's public identity.
        back(); back()
        ui.onAllNodesWithText("Profile").onLast().performClick(); ui.onNodeWithText("Profil publik").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("public-profile-manage").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("profile-tab-saved").performClick(); ui.onNodeWithText("Hanya kamu yang dapat melihat Saved Spot.").assertExists(); capture("public-profile-owner-saved")
        ui.onNodeWithContentDescription("Hapus dari tempat tersimpan").performClick(); ui.waitUntil(5_000) { f.savedWrites.get() == 1 }
        ui.onNodeWithTag("profile-tab-badges").performClick(); ui.onNodeWithText("Badge simulasi 1").assertExists(); capture("public-profile-owner-badges")
    }
    @Test fun allSpotArchiveGroupsResultsAndShowsTheExactThousandCommentBoundary() {
        val f = launch(); ui.onAllNodesWithText("Lihat semua").onFirst().performScrollTo().performClick()
        ui.onNodeWithText("Semua Spot").assertExists(); ui.onNodeWithText("Spot dari semua lokasi").assertExists()
        ui.onNodeWithText("Warisan · 1").assertExists(); ui.onNodeWithText("Alam · 1").assertExists()
        ui.onNodeWithText("Semua lokasi").assertIsSelected(); ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
        val density = ui.activity.resources.displayMetrics.density
        val photo = ui.onNodeWithTag("all-spot-image-${f.spot.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val row = ui.onNodeWithTag("all-spot-row-${f.spot.id}").fetchSemanticsNode().boundsInRoot
        assertEquals(88 * density, photo.width, 1f); assertEquals(16 * density, photo.left, 1f)
        assertEquals(8 * density, photo.top - row.top, 1f); assertEquals(104 * density, row.height, 2f)
        ui.onNodeWithText("1.000 komentar", useUnmergedTree = true).assertExists(); capture("all-spots-global")
    }
    @Test fun allSpotsKeepsTheLocationScopeChipAndBackContextWhenChangingScope() {
        val f = launch(); openSpot()
        ui.onNodeWithTag("spot-location-link").performScrollTo().performClick()
        ui.onNodeWithTag("location-detail").assertExists()
        ui.onAllNodesWithText("Lihat semua").onFirst().performScrollTo().performClick()
        val locationChip = hasText("Magelang") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        ui.onNodeWithText("Spot di Magelang").assertExists(); ui.onNode(locationChip).assertIsSelected()
        assertTrue(f.reads.any { it == "browse:SPOT:${f.location.id}:1" }); capture("all-spots-scoped")
        ui.onNodeWithText("Semua lokasi").performClick()
        ui.onNodeWithText("Spot dari semua lokasi").assertExists(); ui.onNodeWithText("Semua lokasi").assertIsSelected()
        ui.onNode(locationChip).assertIsNotSelected(); capture("all-spots-global-from-location")
        ui.onNode(locationChip).performClick(); ui.onNodeWithText("Spot di Magelang").assertExists()
        back(); ui.onNodeWithTag("location-detail").assertExists()
    }
    @Test fun spotDetailUsesTheGalleryAndHorizontalRelationsWithAVerticalOpenTripList() {
        val f = launch(); openSpot(); ui.onNodeWithTag("spot-comments-open").performScrollTo(); capture("spot-detail-summary")
        ui.onNode(hasText("Magelang") and hasAnyAncestor(hasTestTag("spot-location-link")), useUnmergedTree = true).assertExists()
        val density = ui.activity.resources.displayMetrics.density
        ui.onNodeWithTag("spot-detail-content").performScrollToKey("nearby")
        ui.onNodeWithTag("nearby-spot-image-${f.secondSpot.id}", useUnmergedTree = true).assertIsDisplayed()
        val nearby = ui.onNodeWithTag("nearby-spot-image-${f.secondSpot.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(72 * density, nearby.width, 1f); assertEquals(80 * density, nearby.height, 1f)
        scrollSpotTo("Quest dengan Spot ini")
        scrollSpotTo("Ada di Open Trip")
        assertEquals(f.spot.id, f.tripQueries.last().spotId); capture("spot-detail-quest-trips")
        ui.onNodeWithTag("spot-trip-image-${f.base.openTrip.id}", useUnmergedTree = true).performScrollTo()
        val trip = ui.onNodeWithTag("spot-trip-image-${f.base.openTrip.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(72 * density, trip.width, 1f); assertEquals(96 * density, trip.height, 1f)
        ui.onNodeWithText("Lihat katalog Open Trip").performScrollTo().assertHasClickAction()
        scrollSpotTo("Dalam Collection"); capture("spot-detail-collections")
        scrollSpotTo("Artikel terkait"); capture("spot-detail-articles")
    }
    @Test fun commentDraftSurvivesNativeSheetCloseAndReplyLikesUseTheConfirmedCount() {
        val f = launch(); openSpot(); ui.onNodeWithTag("spot-comments-open").performScrollTo().performClick()
        val bounds = ui.onNodeWithTag("spot-comments-sheet").fetchSemanticsNode().boundsInWindow
        val metrics = ui.activity.resources.displayMetrics
        assertTrue("The comment sheet must anchor to the bottom of the device", bounds.bottom >= metrics.heightPixels - 50 * metrics.density)
        assertTrue("The comment sheet must leave the Figma scrim above it", bounds.top > 100 * metrics.density)
        ui.onNodeWithText("Komentar untuk pengujian UI.").assertExists(); capture("spot-comments")
        ui.onNodeWithTag("spot-comment-draft").performTextInput("Draft komentar simulasi")
        ui.onNodeWithContentDescription("Tutup komentar").performClick()
        ui.onNodeWithTag("spot-comments-open").performScrollTo().performClick(); ui.onNodeWithTag("spot-comment-draft").assertTextContains("Draft komentar simulasi")
        assertEquals(0, f.commentCreates.get()); capture("spot-comments-draft-restored")
        ui.onNodeWithText("Lihat 1 balasan").performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Balasan simulasi.").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithContentDescription("Sukai komentar").onLast().performClick()
        ui.onNodeWithText("4").assertExists(); capture("spot-comments-reply-like")
    }
    @Test fun theProfileAndNotificationControlsRemainUsableAt130PercentFont() {
        launch(scale = 1.3f); profile(); ui.onNodeWithText("Edit profil").assertIsDisplayed(); capture("profile-font-130")
        ui.onNodeWithText("Pengaturan").performScrollTo().performClick(); ui.onNodeWithText("Notifikasi & privasi").performScrollTo().performClick()
        ui.onNodeWithText("Notifikasi perangkat").performScrollTo().performClick(); ui.onNodeWithText("Lihat notifikasi").performScrollTo().assertIsDisplayed(); capture("notifications-device-font-130")
    }
    @Test fun anUnknownCommentOutcomeIsVisibleInsideTheModalAndCannotResendTheSameDraft() {
        val f = TravelerFigmaFixture(); f.commentResult = CatalogResult.NetworkError; launch(f); openSpot()
        ui.onNodeWithTag("spot-comments-open").performScrollTo().performClick()
        ui.onNodeWithTag("spot-comment-draft").performTextInput("Komentar simulasi tidak pasti")
        ui.onNodeWithContentDescription("Kirim komentar").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("spot-comment-unknown").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("Kirim komentar").assertIsNotEnabled(); assertEquals(1, f.commentCreates.get()); capture("spot-comments-unknown")
    }

    @Test fun allFiveRootTabsUseTheSameFigmaNavigationAndSubpagesUseBack() {
        launch()
        val ids = listOf("home", "community", "explorer", "open_trips", "profile")
        val density = ui.activity.resources.displayMetrics.density
        val first = ui.onNodeWithTag("traveler-navigation").fetchSemanticsNode().boundsInRoot
        // Child dp/sp measurements round separately at non-integer device densities.
        assertEquals(77f * density, first.height, density)
        ids.forEach { selected ->
            ui.onNodeWithTag("navigation-$selected").performClick()
            assertEquals(first, ui.onNodeWithTag("traveler-navigation").fetchSemanticsNode().boundsInRoot)
            ids.forEach { id ->
                val item = ui.onNodeWithTag("navigation-$id")
                if (id == selected) item.assertIsSelected() else item.assertIsNotSelected()
                assertEquals(first.width / 5, item.fetchSemanticsNode().boundsInRoot.width, 1f)
            }
            ui.onAllNodesWithTag("navigation-indicator", useUnmergedTree = true).fetchSemanticsNodes().forEach {
                assertEquals(56f * density, it.boundsInRoot.width, 1f); assertEquals(34f * density, it.boundsInRoot.height, 1f)
            }
            ui.onAllNodesWithTag("navigation-icon", useUnmergedTree = true).fetchSemanticsNodes().forEach {
                assertEquals(24f * density, it.boundsInRoot.width, 1f); assertEquals(24f * density, it.boundsInRoot.height, 1f)
            }
            capture("navigation-$selected")
        }
        ui.onNodeWithText("Profil publik").performClick(); ui.onNodeWithTag("traveler-navigation").assertDoesNotExist(); back()
        ui.onNodeWithContentDescription("Pengaturan").performClick(); ui.onNodeWithTag("traveler-navigation").assertDoesNotExist(); back()
        ui.onNodeWithTag("navigation-home").performClick()
        ui.onNodeWithContentDescription("Notifikasi").performClick(); ui.onNodeWithTag("traveler-navigation").assertDoesNotExist(); back()
        openSpot(); ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
    }

    @Test fun navigationLabelsStayReadableAndIconsStayAlignedAtDoubledFontSize() {
        launch(scale = 2f)
        capture("navigation-font-200")
        listOf("Home", "Community", "Explorer", "Open Trip", "Profile").forEach { label ->
            val node = ui.onNode(hasText(label) and hasAnyAncestor(hasTestTag("traveler-navigation")), useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("The full navigation label must fit: $label; " + layouts.joinToString { "size=${it.size}, lines=${it.lineCount}, widthOverflow=${it.didOverflowWidth}, heightOverflow=${it.didOverflowHeight}" }, layouts.isNotEmpty() && layouts.none { it.hasVisualOverflow })
            node.assertIsDisplayed()
        }
        val icons = ui.onAllNodesWithTag("navigation-icon", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(5, icons.size); icons.forEach { assertEquals(icons.first().boundsInRoot.top, it.boundsInRoot.top, 1f) }
        capture("navigation-font-200")
        ui.onNodeWithTag("navigation-community").performClick(); ui.onNodeWithTag("navigation-community").assertIsSelected()
    }
}
