package com.floopel.traveler

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
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
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises the production Home and reader, using synthetic repository responses only. */
@RunWith(AndroidJUnit4::class)
class TravelerHomeArticleDesignTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val f = TravelerFigmaFixture()
    private val originalLocale = Locale.getDefault()
    @After fun restoreLocale() { Locale.setDefault(originalLocale) }
    private val requests = CopyOnWriteArrayList<Int>()
    @Volatile private var result: CatalogResult<List<CatalogEntry>> = CatalogResult.Ready(emptyList())
    private var beforeRead: suspend () -> Unit = {}
    private fun entries(count: Int, long: Boolean = false) = (1..count).map { i ->
        f.article.copy(id = "article-$i", slug = "article-$i", title = "Artikel simulasi $i" + if (long) " dengan judul panjang untuk menguji pembesaran teks dan ringkasan kartu" else "",
            subtitle = "Ringkasan perjalanan simulasi $i" + if (long) " yang panjang untuk menguji pemotongan dua baris tanpa membuat halaman terpotong." else "",
            category = "article", bodyHtml = "<p>Isi artikel simulasi $i.</p>", locationName = "Magelang",
            story = StoryMetadata(null, null, 5, listOf("Budaya & perjalanan")), commentCount = null)
    }
    private fun launch(items: List<CatalogEntry> = entries(2), scale: Float = 1f, language: String = "id", preset: Boolean = false) {
        Locale.setDefault(Locale.forLanguageTag(language))
        if (!preset) result = CatalogResult.Ready(items)
        val r = f.runtime
        val catalog = object : TravelerCatalog by r.catalog {
            override suspend fun stories(locale: String, type: StoryType?, perPage: Int): CatalogResult<List<CatalogEntry>> {
                requests += perPage; beforeRead(); return result
            }
            override suspend fun detail(item: CatalogEntry, locale: String) = CatalogResult.Ready(
                (items.firstOrNull { it.id == item.id || it.slug == item.slug } ?: item).copy(detailLoaded = true))
            override suspend fun browse(kind: CatalogKind, locale: String, page: Int, storyType: StoryType?, locationId: String?, parentId: String?) =
                if (kind == CatalogKind.STORY) CatalogResult.Ready(CatalogPage(entries(12), 1, 1, 12))
                else r.catalog.browse(kind, locale, page, storyType, locationId, parentId)
        }
        val runtime = TravelerRuntime(r.identity, catalog, r.itineraries, r.distribution, r.trips, r.notifications, r.account,
            r.legal, r.registration, r.publicContent, r.savedSpots, r.questGroups, r.newOperationKey, r.startup,
            comments = r.comments, settings = r.settings)
        ui.runOnUiThread { WindowCompat.enableEdgeToEdge(ui.activity.window) }
        ui.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                TravelerApp(runtime, {}, language = language, backHandler = { enabled, back -> BackHandler(enabled, back) })
            }
        }
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("home-content").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("home-content").performScrollToKey("articles")
    }
    private fun card(id: Int) = ui.onNodeWithTag("home-article-article-$id")
    private fun capture(name: String) {
        ui.waitForIdle()
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("home-article-audit"))
        directory.mkdirs()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        directory.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun back() { ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() } }
    @Test fun zeroKeepsEmptyStateAndSeeAllWithoutSampleCards() {
        launch(emptyList()); ui.onNodeWithTag("home-article-slider").assertDoesNotExist()
        ui.onNode(hasText("Lihat semua") and hasAnyAncestor(hasTestTag("home-rail-STORY"))).assertHasClickAction()
        capture("home-articles-empty")
    }
    @Test fun oneRealArticleDoesNotDuplicateOrInventComments() {
        launch(entries(1)); card(1).assertIsDisplayed(); card(2).assertDoesNotExist()
        ui.onNodeWithText("Magelang · 5 menit baca · — komentar").assertExists()
        assertEquals(listOf(10), requests.toList()); capture("home-articles-one")
    }
    @Test fun twoCardsUse304WideFullBleed16By9Media12GapAndNextPeek() {
        val photo = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.resolve("home-article-synthetic.png")
        val color = android.graphics.Color.rgb(18, 124, 88)
        Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            .let { bitmap -> photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
        launch(entries(2).map { it.copy(coverUrl = photo.toURI().toString()) })
        ui.waitUntil(5_000) {
            val bitmap = ui.onNodeWithTag("home-article-media-article-1", true).captureToImage().asAndroidBitmap()
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) == color
        }
        val density = ui.activity.resources.displayMetrics.density
        val first = card(1).fetchSemanticsNode().boundsInRoot
        val second = card(2).fetchSemanticsNode().boundsInRoot
        val media = ui.onNodeWithTag("home-article-media-article-1", true).fetchSemanticsNode().boundsInRoot
        assertEquals(304f * density, first.width, 1f)
        assertEquals(first.left, media.left, 1f); assertEquals(first.right, media.right, 1f)
        assertEquals(171f * density, media.height, 1f)
        assertEquals(12f * density, second.left - first.right, 1f)
        assertEquals(52f * density, second.width, 1f)
        capture("home-articles-two")
        ui.onNodeWithTag("home-article-slider").performScrollToIndex(1); card(2).assertIsDisplayed()
        ui.onNodeWithTag("home-article-slider").performScrollToIndex(0); card(1).assertIsDisplayed()
    }
    @Test fun tenArticlesCanReachBothEndsInApiOrder() {
        launch(entries(10)); ui.onNodeWithTag("home-article-slider").performScrollToIndex(9); card(10).assertIsDisplayed()
        card(11).assertDoesNotExist(); capture("home-articles-last")
        ui.onNodeWithTag("home-article-slider").performScrollToIndex(0); card(1).assertIsDisplayed()
    }
    @Test fun excessAndDuplicateResponsesKeepFirstTenUniqueWithoutReordering() {
        val all = entries(12); launch(listOf(all[0], all[0]) + all.drop(1))
        val count = ui.onNodeWithTag("home-article-slider").fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].columnCount
        assertEquals(10, count)
        ui.onNodeWithTag("home-article-slider").performScrollToIndex(9); card(10).assertIsDisplayed(); card(11).assertDoesNotExist()
    }
    @Test fun cardOpensExistingReaderAndBackReturnsToSlider() {
        launch(); card(1).performClick(); ui.onNodeWithText("Isi artikel simulasi 1.").assertExists()
        back(); ui.onNodeWithTag("home-content").performScrollToKey("articles"); card(1).assertIsDisplayed()
    }
    @Test fun seeAllArchiveRemainsUncappedAboveTen() {
        launch(); ui.onNode(hasText("Lihat semua") and hasAnyAncestor(hasTestTag("home-rail-STORY"))).performClick()
        ui.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Artikel simulasi 12"))
        ui.onNodeWithText("Artikel simulasi 12").assertIsDisplayed(); back()
        ui.onNodeWithTag("home-content").performScrollToKey("articles"); card(1).assertExists()
    }
    @Test fun loadingRetainsSectionThenResolvesOnlyRealData() {
        val gate = CompletableDeferred<Unit>(); beforeRead = { gate.await() }; launch()
        ui.onNodeWithTag("home-article-slider").assertDoesNotExist(); capture("home-articles-loading")
        gate.complete(Unit); ui.waitUntil(5_000) { ui.onAllNodesWithTag("home-article-slider").fetchSemanticsNodes().isNotEmpty() }
        card(1).assertExists()
    }
    @Test fun failedReadOffersRetryAndRecoversWithoutSamples() {
        result = CatalogResult.NetworkError; launch(preset = true)
        ui.onNodeWithTag("home-article-slider").assertDoesNotExist(); capture("home-articles-error")
        result = CatalogResult.Ready(entries(1))
        ui.onNode(hasText("Coba lagi") and hasAnyAncestor(hasTestTag("home-rail-STORY"))).performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("home-article-slider").fetchSemanticsNodes().isNotEmpty() }; card(1).assertExists()
    }
    @Test fun profileFailureDoesNotBlockExistingGlobalArticlesAndEnglishMetadata() {
        f.profileResult = CatalogResult.NetworkError; launch(entries(1), language = "en")
        card(1).assertIsDisplayed(); ui.onNodeWithText("Magelang · 5 min read · — comments").assertExists()
        capture("home-articles-en-profile-failed")
    }
    @Test fun longTextAndMissingMediaRemainUsableAt130Percent() { launch(entries(2, true), 1.3f); card(1).assertHasClickAction(); capture("home-articles-font130") }
    @Test fun longTextAndMissingMediaRemainUsableAt200Percent() {
        launch(entries(2, true), 2f)
        ui.onNodeWithTag("home-content").performScrollToNode(hasTestTag("home-article-article-1"))
        card(1).assertHasClickAction(); capture("home-articles-font200")
    }
}
