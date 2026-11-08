package com.floopel.traveler

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.testing.TravelerLocationFixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production VM/Compose with explicit domain doubles; photos/providers/backend are not inferred from these captures. */
@RunWith(AndroidJUnit4::class)
class TravelerLocationDesignTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    @Test fun threeLongOpenTripRowsKeepTheirGuttersAtLargerFontSize() {
        val f = TravelerLocationFixture()
        f.visibleTrips = (1..3).map { index -> f.base.openTrip.copy(id = "padding-trip-$index", name =
            "Open Trip Banten 0$index: Pantai Tanjung Kait & Pantai Tanjung Pasir dan Pulau Cangkir") }
        launch(f, 1.3f); visible("home-location-${f.magelang.id}"); ui.onNodeWithTag("home-location-${f.magelang.id}").performClick()
        val density = ui.activity.resources.displayMetrics.density
        for (trip in requireNotNull(f.visibleTrips)) {
            ui.onNodeWithTag("location-trip-${trip.id}").performScrollTo()
            val row = ui.onNodeWithTag("location-trip-${trip.id}").fetchSemanticsNode().boundsInRoot
            val viewport = ui.onNodeWithTag("traveler-viewport").fetchSemanticsNode().boundsInRoot
            val photo = ui.onNodeWithTag("location-trip-image-${trip.id}", true).fetchSemanticsNode().boundsInRoot
            val title = ui.onNodeWithTag("location-trip-title-${trip.id}", true).fetchSemanticsNode().boundsInRoot
            assertEquals(16f * density, row.left - viewport.left, 1f); assertEquals(16f * density, viewport.right - row.right, 1f)
            assertEquals(76f * density, photo.width, 1f); assertEquals(80f * density, photo.height, 1f)
            assertEquals(12f * density, title.left - photo.right, 1f); assertTrue(title.right <= row.right)
        }
        ui.onNodeWithTag("location-trip-padding-trip-1").performScrollTo()
        capture("location-three-trips-font130-padding")
        assertEquals(0, f.base.tripCreateCalls.get())
    }
    @Test fun openTripRowsKeepTheSixteenDpGuttersAndLongTitlesInsideTheirTextColumn() {
        val f = TravelerLocationFixture(); f.tripTitle = "Open Trip Bali 01: Air Terjun Tegenungan, Goa Gajah, Mandala Suci Wenara Wana dan Tampaksiring"
        launch(f); visible("home-location-${f.magelang.id}"); ui.onNodeWithTag("home-location-${f.magelang.id}").performClick()
        val id = f.base.openTrip.id
        ui.onNodeWithTag("location-trip-$id").performScrollTo()
        val density = ui.activity.resources.displayMetrics.density
        val viewport = ui.onNodeWithTag("traveler-viewport").fetchSemanticsNode().boundsInRoot
        val row = ui.onNodeWithTag("location-trip-$id").fetchSemanticsNode().boundsInRoot
        val photo = ui.onNodeWithTag("location-trip-image-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val title = ui.onNodeWithTag("location-trip-title-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(16f * density, row.left - viewport.left, 1f); assertEquals(16f * density, viewport.right - row.right, 1f)
        assertEquals(76f * density, photo.width, 1f); assertEquals(80f * density, photo.height, 1f)
        assertEquals(12f * density, title.left - photo.right, 1f); assertTrue(title.right <= row.right)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        ui.onNodeWithTag("location-trip-title-$id", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2, layouts.single().lineCount); assertTrue(layouts.single().isLineEllipsized(1))
        capture("location-trip-long-title-padding")
    }
    private fun launch(f: TravelerLocationFixture = TravelerLocationFixture(), scale: Float = 1f): TravelerLocationFixture {
        ui.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = scale)) {
                TravelerApp(f.runtime, {}, backHandler = { enabled, back -> BackHandler(enabled, back) })
            }
        }
        ui.waitUntil(8_000) { ui.onAllNodesWithText("Halo, Traveler").fetchSemanticsNodes().isNotEmpty() }
        return f
    }
    private fun allLocations() { ui.onNodeWithText("Jelajahi semua lokasi").performScrollTo().performClick() }
    private fun back() { ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() } }
    private fun search(text: String) {
        val field = ui.onNode(hasSetTextAction() and (hasTestTag("location-search") or hasAnyAncestor(hasTestTag("location-search"))), useUnmergedTree = true)
        field.performTextReplacement(text)
        field.performImeAction()
    }
    private fun visible(tag: String) {
        repeat(7) {
            ui.waitForIdle()
            val node = ui.onAllNodesWithTag(tag).fetchSemanticsNodes().singleOrNull()
            val view = ui.onNodeWithTag("traveler-viewport").getBoundsInRoot()
            val rect = if (node != null) ui.onNodeWithTag(tag).getUnclippedBoundsInRoot() else null
            if (rect != null && rect.bottom > rect.top && rect.top >= view.top && rect.bottom <= view.bottom) return
            ui.onNodeWithTag("traveler-viewport").performTouchInput {
                if (rect != null && rect.bottom > rect.top && rect.top < view.top) swipeDown() else swipeUp()
            }
        }
        throw AssertionError("$tag did not become fully visible")
    }

    @Test fun homeRatingAndLocationCardsUseTheirOwnFigmaGeometry() {
        val f = launch(); val density = ui.activity.resources.displayMetrics.density
        visible("home-spot-${f.spot.id}")
        val spot = ui.onNodeWithTag("home-spot-${f.spot.id}").fetchSemanticsNode().boundsInRoot
        assertEquals(304f * density, spot.width, 1f); assertEquals(104f * density, spot.height, 1f)
        ui.onNodeWithTag("catalog-rating-${f.spot.id}", useUnmergedTree = true).assert(hasContentDescription("Referensi AI: 4,8", substring = true))
        assertEquals(16f * density, ui.onNodeWithTag("catalog-rating-${f.spot.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height, 1f)
        capture("home-rating-compact")
        visible("home-location-${f.magelang.id}")
        val card = ui.onNodeWithTag("home-location-${f.magelang.id}").fetchSemanticsNode().boundsInRoot
        assertEquals(160f * density, card.width, 1f); assertEquals(208f * density, card.height, 1f)
        ui.onNodeWithTag("home-location-${f.magelang.id}").assert(hasText("Magelang")).assert(hasText("Jawa Tengah"))
        ui.onNodeWithText("Jelajahi lokasi").performScrollTo()
        capture("home-location-parent")
    }
    @Test fun countryRegionCityAndLocationReaderKeepTheirContextOnBack() {
        val f = launch(); allLocations()
        ui.onNodeWithText("Provinsi & wilayah").assertIsDisplayed(); capture("locations-regions")
        ui.onNodeWithTag("location-row-${f.centralJava.id}").performClick()
        ui.onNodeWithText("Kota & Kabupaten").assertIsDisplayed(); capture("locations-cities")
        ui.onNodeWithTag("location-row-${f.magelang.id}").performClick()
        ui.onNodeWithTag("location-detail").assertExists()
        ui.onNodeWithText("Jawa Tengah, Indonesia").assertIsDisplayed()
        ui.onNodeWithText("Tentang Spot").assertDoesNotExist(); capture("location-detail-identity-and-spots")
        back(); ui.onNodeWithText("Kota & Kabupaten").assertIsDisplayed()
        ui.onNodeWithText("Indonesia").performClick()
        ui.onNodeWithText("Provinsi & wilayah").assertIsDisplayed()
        back(); ui.onAllNodesWithText("Home").onLast().assertIsDisplayed()
    }
    @Test fun countryPickerCanSearchACountryOnTheSecondServerPage() {
        val f = launch(); allLocations(); ui.onNodeWithTag("location-row-${f.indonesia.id}").performClick()
        ui.onNodeWithTag("country-picker").assertExists(); ui.onNodeWithTag("location-row-${f.malaysia.id}").assertExists()
        search("Malay"); ui.onNodeWithTag("location-row-${f.indonesia.id}").assertDoesNotExist(); capture("locations-country-picker")
        ui.onNodeWithTag("location-row-${f.malaysia.id}").performClick()
        ui.onNodeWithTag("location-row-${f.selangor.id}").assertIsDisplayed()
        ui.onNodeWithTag("location-row-${f.malaysia.id}").assert(hasText("Negara dipilih · Ubah negara")); capture("locations-selected-malaysia")
    }
    @Test fun locationSearchReturnsToTheSameQueryAfterTheReader() {
        val f = launch(); allLocations(); search("Magelang")
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("location-row-${f.magelang.id}").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Hasil lokasi").assertIsDisplayed(); capture("locations-search")
        ui.onNodeWithTag("location-row-${f.magelang.id}").performClick(); back()
        ui.onNodeWithText("Hasil lokasi").assertIsDisplayed(); ui.onNodeWithTag("location-row-${f.cityMagelang.id}").assertIsDisplayed()
        assertEquals("search:Magelang", f.reads.last { it.startsWith("search:") })
    }
    @Test fun aCountryBreadcrumbAfterGlobalSearchUsesTheCountryShownOnScreen() {
        val f = launch(); allLocations(); search("Selangor")
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("location-row-${f.selangor.id}").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("location-row-${f.selangor.id}").performClick()
        ui.onNodeWithText("Kota & Kabupaten").assertIsDisplayed()
        ui.onNodeWithText("Malaysia").performClick()
        ui.onNodeWithTag("location-row-${f.malaysia.id}").assert(hasText("Negara dipilih · Ubah negara"))
        ui.onNodeWithTag("location-row-${f.selangor.id}").assertIsDisplayed(); capture("locations-cross-country-breadcrumb")
        back(); ui.onAllNodesWithText("Home").onLast().assertIsDisplayed()
    }
    @Test fun unavailableCountriesShowRetryAndRecoverAfterTheServerDelay() {
        val f = TravelerLocationFixture(); f.failCountries = true; launch(f); allLocations()
        ui.onNodeWithText("Layanan sedang tidak tersedia. Coba lagi nanti.").assertExists()
        ui.onNodeWithText("Coba lagi").assertExists(); capture("locations-country-error")
        f.failCountries = false
        ui.waitUntil(6_000) { runCatching { ui.onNodeWithText("Coba lagi").assertIsEnabled() }.isSuccess }
        ui.onNodeWithText("Coba lagi").performClick()
        ui.onNodeWithTag("location-row-${f.centralJava.id}").assertIsDisplayed(); capture("locations-country-recovered")
        assertEquals(0, f.base.createCalls.get()); assertEquals(0, f.base.publicationCalls.get())
    }
    @Test fun anApprovedCountryWithNoChildrenShowsAnEmptyResult() {
        val f = TravelerLocationFixture(); f.emptyChildren = true; launch(f); allLocations()
        ui.onNodeWithText("Belum ada hasil").assertExists(); ui.onNodeWithText("Coba lagi").assertDoesNotExist(); capture("locations-empty")
    }
    @Test fun aFailedSpotRailKeepsTheLocationAndItsRetryOnlyReads() {
        val f = TravelerLocationFixture(); f.failSpots = true; launch(f)
        visible("home-location-${f.magelang.id}"); ui.onNodeWithTag("home-location-${f.magelang.id}").performClick()
        ui.onNodeWithText("Jawa Tengah, Indonesia").assertExists()
        ui.onNodeWithText("Koneksi belum tersedia. Periksa internet lalu coba lagi.").assertExists(); capture("location-spot-rail-error")
        f.failSpots = false; ui.onNodeWithText("Coba lagi").performScrollTo().performClick()
        ui.onNodeWithText("Candi Borobudur").assertExists(); capture("location-spot-rail-recovered")
        assertEquals(0, f.base.publicationCalls.get()); assertEquals(0, f.base.tripCreateCalls.get())
        ui.onNodeWithText("Open Trip tersedia").performScrollTo(); capture("location-trip-rail")
        visible("location-article-${f.article.id}"); capture("location-article-rail")
    }
    @Test fun unknownAndSuppressedScoresNeverBecomeAFakeZeroRating() {
        val f = launch(TravelerLocationFixture(score = null, suppressed = true, comments = 0))
        visible("home-spot-${f.spot.id}")
        ui.onNodeWithTag("catalog-rating-${f.spot.id}", useUnmergedTree = true).assert(hasContentDescription("0 komentar"))
        capture("rating-comments-without-score")
    }
    @Test fun largeFontKeepsLongLocationNamesInsideTheRows() {
        val f = launch(scale = 2f); allLocations()
        ui.onNodeWithTag("location-row-${f.yogyakarta.id}").performScrollTo()
        val row = ui.onNodeWithTag("location-row-${f.yogyakarta.id}").fetchSemanticsNode().boundsInRoot
        val title = ui.onNodeWithText("Daerah Istimewa Yogyakarta", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(title.left >= row.left && title.right <= row.right && title.top >= row.top && title.bottom <= row.bottom)
        capture("location-long-name-font-200")
    }
    @Test fun spotDetailUsesTheDetailRatingAndRemainsReadableAtFont130() {
        val f = launch(scale = 1.3f); visible("home-spot-${f.spot.id}"); ui.onNodeWithTag("home-spot-${f.spot.id}").performClick()
        ui.onNodeWithTag("catalog-rating-${f.spot.id}").performScrollTo().assert(hasContentDescription("Referensi AI: 4,8", substring = true))
        ui.onNodeWithText("Tambahkan ke rencana").assertExists(); capture("spot-detail-rating-font-130")
    }
    @Test fun spotDetailRatingUsesTheNineteenDpLineAndFullContentWidth() {
        val f = launch(); visible("home-spot-${f.spot.id}"); ui.onNodeWithTag("home-spot-${f.spot.id}").performClick()
        ui.onNodeWithTag("catalog-rating-${f.spot.id}").performScrollTo()
        val line = ui.onNodeWithTag("catalog-rating-${f.spot.id}").fetchSemanticsNode().boundsInRoot
        val density = ui.activity.resources.displayMetrics.density
        val viewport = ui.onNodeWithTag("traveler-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(19f * density, line.height, 1f)
        assertEquals(viewport.width - 40f * density, line.width, 1f)
        capture("spot-detail-rating")
    }
    @Test fun locationContentAndQuestActionRemainReadableAtFont130() {
        val f = launch(scale = 1.3f); visible("home-location-${f.magelang.id}")
        ui.onNodeWithTag("home-location-${f.magelang.id}").performClick()
        ui.onNodeWithText("Quest belum diambil").performScrollTo()
        ui.onNodeWithText("Lihat quest").assertIsDisplayed(); capture("location-quest-rail-font-130")
        val button = ui.onNodeWithText("Lihat quest").fetchSemanticsNode().boundsInRoot
        val label = ui.onNodeWithText("Lihat quest", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(label.left >= button.left && label.right <= button.right && label.top >= button.top && label.bottom <= button.bottom)
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val output = ui.activity.getExternalFilesDir("audit")!!.resolve("location-rating-$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
