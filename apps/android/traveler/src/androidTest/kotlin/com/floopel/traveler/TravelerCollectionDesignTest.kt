package com.floopel.traveler

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerCatalog
import com.floopel.feature.traveler.testing.TravelerFigmaFixture
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TravelerCollectionDesignTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val f = TravelerFigmaFixture()
    private val originalLocale = Locale.getDefault()
    @After fun restoreLocale() { Locale.setDefault(originalLocale) }
    private val collection = f.collection.copy(title = "Jejak budaya Magelang", subtitle = null,
        bodyHtml = "<p>Kumpulan Spot untuk menemukan hubungan tempat dan cerita dalam satu penjelajahan.</p>",
        locations = listOf(LocationReference("magelang", "Magelang · Jawa Tengah", "magelang", "city")),
        related = listOf(f.spot.copy(coverUrl = null, locationName = "Magelang · Jawa Tengah"),
            f.secondSpot.copy(coverUrl = null, locationName = "Magelang · Jawa Tengah", aiReference = null, commentCount = null)))
    @Volatile private var result: CatalogResult<CatalogEntry> = CatalogResult.Ready(collection)
    @Volatile private var emptyArchive = false
    private var beforeRead: suspend () -> Unit = {}
    private fun launch(scale: Float = 1f, language: String = "id", open: Boolean = true) {
        Locale.setDefault(Locale.forLanguageTag(language))
        val r = f.runtime
        val catalog = object : TravelerCatalog by r.catalog {
            override suspend fun collections(locale: String, perPage: Int) = CatalogResult.Ready(listOf(collection))
            override suspend fun browse(kind: CatalogKind, locale: String, page: Int, storyType: StoryType?, locationId: String?, parentId: String?) =
                if (kind == CatalogKind.COLLECTION) CatalogResult.Ready(CatalogPage(if (emptyArchive) emptyList() else listOf(collection), 1, 1))
                else r.catalog.browse(kind, locale, page, storyType, locationId, parentId)
            override suspend fun detail(item: CatalogEntry, locale: String): CatalogResult<CatalogEntry> {
                if (item.kind == CatalogKind.COLLECTION) { beforeRead(); return result }
                return r.catalog.detail(item, locale)
            }
        }
        val runtime = TravelerRuntime(r.identity, catalog, r.itineraries, r.distribution, r.trips, r.notifications, r.account,
            r.legal, r.registration, r.publicContent, r.savedSpots, r.questGroups, r.newOperationKey, r.startup,
            comments = r.comments, settings = r.settings)
        ui.runOnUiThread { WindowCompat.enableEdgeToEdge(ui.activity.window) }
        ui.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
            TravelerApp(runtime, {}, language = language, backHandler = { enabled, back -> BackHandler(enabled, back) })
        } }
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("home-content").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("home-content").performScrollToNode(hasTestTag("home-collection-${collection.id}"))
        if (open) ui.onNodeWithTag("home-collection-${collection.id}").performClick()
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        if (ui.onAllNodesWithTag("collection-read-state").fetchSemanticsNodes().isNotEmpty()) {
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("collection-artwork-ready", true).fetchSemanticsNodes().isNotEmpty() }
        }
        val out = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("collection-audit"))
        out.mkdirs()
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        out.resolve("$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun ready() = ui.waitUntil(5_000) { ui.onAllNodesWithTag("collection-title").fetchSemanticsNodes().isNotEmpty() }
    @Test fun readerUsesFigmaCoverGuttersMemberGeometryAndRealIntro() {
        launch(); ready(); val density = ui.activity.resources.displayMetrics.density
        val cover = ui.onNodeWithTag("collection-cover", true).fetchSemanticsNode().boundsInRoot
        assertEquals(20f*density, cover.left, 1f); assertEquals(360f*density, cover.width, 1f); assertEquals(184f*density, cover.height, 1f)
        val member = ui.onNodeWithTag("collection-member-${f.spot.id}").fetchSemanticsNode().boundsInRoot
        val media = ui.onNodeWithTag("collection-member-media-${f.spot.id}", true).fetchSemanticsNode().boundsInRoot
        assertEquals(32f*density, media.left, 1f); assertEquals(72f*density, media.width, 1f); assertEquals(80f*density, media.height, 1f)
        assertEquals(12f*density, media.top-member.top, 1f)
        val title = ui.onNodeWithText(f.spot.title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(media.top, title.top, 1f); assertEquals(12f*density, title.left - media.right, 1f)
        ui.onNodeWithText("Kumpulan Spot untuk menemukan hubungan tempat dan cerita dalam satu penjelajahan.").assertExists()
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist(); capture("collection-filled")
        val toolbar = ui.onNodeWithTag("traveler-top-bar").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("collection-content").performScrollToIndex(6)
        assertEquals(toolbar, ui.onNodeWithTag("traveler-top-bar").fetchSemanticsNode().boundsInRoot)
    }
    @Test fun unknownMetricsShowDashesAndMemberOpensSpot() {
        launch(); ready()
        ui.onNodeWithTag("collection-content").performScrollToNode(hasTestTag("collection-member-${f.secondSpot.id}"))
        ui.onNodeWithText("— · — komentar", useUnmergedTree = true).assertExists()
        capture("collection-unknown-metrics")
        ui.onNodeWithTag("collection-member-${f.secondSpot.id}").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("spot-detail-content").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun distinctCollectionIdentityAndMultiLocationCoverageArePreserved() {
        result = CatalogResult.Ready(collection.copy(title = "Penjelajahan Nusantara", locations = listOf(
            LocationReference("jawa", "Jawa Tengah", "jawa-tengah", "region"), LocationReference("bali", "Bali", "bali", "region"))))
        launch(); ready(); ui.onNodeWithText("Penjelajahan Nusantara").assertExists(); ui.onNodeWithText("Jawa Tengah · Bali").assertExists()
        capture("collection-multiple-locations")
    }
    @Test fun validCollectionWithoutMembersKeepsItsOwnMetadata() {
        result = CatalogResult.Ready(collection.copy(related = emptyList())); launch(); ready()
        ui.onNodeWithText(collection.title).assertExists(); ui.onNodeWithText("Belum ada Collection tersedia").assertDoesNotExist()
        ui.onNodeWithTag("collection-member-${f.spot.id}").assertDoesNotExist(); capture("collection-no-members")
    }
    @Test fun deferredLoadingDoesNotDisplayCachedOrExampleMembers() {
        val gate = CompletableDeferred<Unit>(); beforeRead = { gate.await() }; launch()
        ui.onNodeWithTag("collection-title").assertDoesNotExist(); ui.onNodeWithText("Memuat…").assertExists(); capture("collection-loading")
        gate.complete(Unit); ready()
    }
    @Test fun networkFailureRetriesAndUsesTheExactFigmaSearchArtwork() {
        result = CatalogResult.NetworkError; launch()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("collection-artwork-ready", true).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Coba muat kembali").assertIsDisplayed(); capture("collection-error")
        result = CatalogResult.Ready(collection); ui.onNodeWithText("Coba lagi").performClick(); ready()
    }
    @Test fun notFoundDoesNotRenderFakeCollectionOrOfferNetworkRetry() {
        result = CatalogResult.HttpError(404); launch()
        ui.onNodeWithText("Collection belum bisa dibuka").assertExists(); ui.onNodeWithText("Coba lagi").assertDoesNotExist()
        ui.onNodeWithTag("collection-title").assertDoesNotExist(); capture("collection-not-found")
        ui.onNodeWithText("Kembali ke Community").performClick(); ui.onNodeWithTag("navigation-community").assertIsSelected()
    }
    @Test fun emptyArchiveHasPinnedExploreActionAndRealEmptyIllustration() {
        emptyArchive = true; launch(open = false)
        ui.onNode(hasText("Lihat semua") and hasAnyAncestor(hasTestTag("home-rail-COLLECTION"))).performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("collection-artwork-ready", true).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Belum ada Collection tersedia").assertIsDisplayed(); ui.onNodeWithText("Jelajahi Spot").assertHasClickAction()
        capture("collection-empty")
        ui.onNodeWithText("Jelajahi Spot").performClick(); ui.onNodeWithTag("all-spots").assertExists()
    }
    @Test fun longTitleAt130PercentCanScrollToLastMember() {
        result = CatalogResult.Ready(collection.copy(title = "Collection simulasi dengan judul panjang untuk memeriksa pembesaran teks"))
        launch(scale = 1.3f); ready(); capture("collection-font130")
        ui.onNodeWithTag("collection-content").performScrollToNode(hasTestTag("collection-member-${f.secondSpot.id}"))
        ui.onNodeWithTag("collection-member-${f.secondSpot.id}").assertIsDisplayed()
    }
    @Test fun englishEmptyStateAt200PercentKeepsExploreReachable() {
        emptyArchive = true; launch(scale = 2f, language = "en", open = false)
        ui.onNode(hasText("Browse all") and hasAnyAncestor(hasTestTag("home-rail-COLLECTION"))).performClick()
        ui.onNodeWithText("Explore Spots").assertIsDisplayed(); capture("collection-en-font200")
    }
}
