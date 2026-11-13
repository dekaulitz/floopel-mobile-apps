package com.floopel.traveler

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.domain.model.CatalogResult
import com.floopel.feature.traveler.domain.model.QuestCatalogItem
import com.floopel.feature.traveler.domain.model.LocationReference
import com.floopel.feature.traveler.domain.model.LocationAncestor
import com.floopel.feature.traveler.testing.TravelerQuestDesignFixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native Figma geometry/interactions with domain repositories; no live gameplay/provider is implied. */
@RunWith(AndroidJUnit4::class)
class TravelerQuestDesignTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private fun launch(f: TravelerQuestDesignFixture = TravelerQuestDesignFixture(), scale: Float = 1f,
        share: (String) -> Unit = {}): TravelerQuestDesignFixture {
        ui.runOnUiThread { WindowCompat.enableEdgeToEdge(ui.activity.window) }
        ui.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                TravelerApp(f.runtime, share, backHandler = { enabled, back -> BackHandler(enabled, back) })
            }
        }
        ui.waitUntil(5_000) { ui.onAllNodesWithContentDescription("Notifikasi").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("Quest").onFirst().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("quest-catalog").fetchSemanticsNodes().isNotEmpty() }
        return f
    }
    private fun back() { ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() } }
    private fun ready(f: TravelerQuestDesignFixture) { ui.onNodeWithText(f.card.title).performScrollTo().performClick() }
    private fun capture(name: String) {
        ui.waitForIdle(); val folder = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("quest-design-audit"))
        folder.mkdirs(); val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        folder.resolve("$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun sharingForZeroOneAndMultipleLocationsForwardsTheExactLocalizedPayload() {
        val f = TravelerQuestDesignFixture()
        val locations = listOf(LocationReference("location-bandung", "Bandung", "bandung", "city"),
            LocationReference("location-jakarta", "Jakarta", "jakarta", "city"))
        val payloads = mutableListOf<String>()
        f.readyReader = f.readyReader.copy(card = f.card.copy(locations = emptyList()))
        f.extraCards = listOf(QuestCatalogItem(f.card.copy(id = "multiple-locations", slug = "multiple-locations",
            title = "Quest beberapa lokasi", locations = locations), "active", "available", false))
        f.pickerRows = listOf(f.location.copy(ancestors = listOf(LocationAncestor("region", "Jawa Barat", "region"),
            LocationAncestor("country", "Indonesia", "country"))))
        launch(f, share = { payloads += it })
        ui.onNodeWithTag("quest-catalog").performScrollToNode(hasText("Bandung \u00B7 Jakarta"))
        ui.onNodeWithText("Bandung \u00B7 Jakarta").assertExists()
        ui.onNodeWithTag("quest-catalog").performScrollToNode(hasTestTag("quest-filter-open"))
        ui.onNodeWithTag("quest-filter-open").performClick()
        ui.onNodeWithTag("quest-filter-location").performScrollTo().performClick()
        ui.onNodeWithText("Jawa Barat \u00B7 Indonesia").assertExists()
        back(); back()
        val expected = listOf(f.card.title + "\n", f.card.title + "\nBandung", f.card.title + "\nBandung \u00B7 Jakarta")
        expected.forEachIndexed { count, text ->
            if (count > 0) {
                back()
                f.readyReader = f.readyReader.copy(card = f.card.copy(locations = locations.take(count)))
            }
            ready(f)
            ui.onNodeWithContentDescription("Bagikan Quest").performClick()
            ui.runOnIdle { assertEquals(text, payloads.last()); assertEquals(count + 1, payloads.size) }
        }
        assertEquals(0, f.startCalls.get())
    }
    @Test fun availableTabsModeChipsAndRewardCardsFollowTheCatalogAndGroupEmptyState() {
        val f = launch(); val density = ui.activity.resources.displayMetrics.density
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
        assertEquals(48f * density, ui.onNodeWithTag("quest-toolbar").fetchSemanticsNode().boundsInRoot.height, 1f)
        assertEquals(48f * density, ui.onNodeWithTag("quest-tab-available").fetchSemanticsNode().boundsInRoot.height, 1f)
        ui.onNodeWithTag("quest-tab-available").assertIsSelected(); ui.onNodeWithText("Badge Jejak Budaya").assertExists(); capture("catalog-available")
        ui.onNodeWithTag("quest-mode-personal").performClick(); capture("catalog-personal")
        ui.onNodeWithTag("quest-mode-group").performClick(); ui.onNodeWithText("Belum ada Quest kelompok").assertIsDisplayed(); capture("catalog-group-empty")
        assertEquals("group", f.catalogCalls.last().first.participationMode)
        ui.onNodeWithText("Lihat semua jenis").performClick(); ui.onNodeWithText(f.card.title).assertExists(); assertEquals(0, f.startCalls.get())
    }
    @Test fun filterUsesNumericDraftAndAPickedRealLocationThenRetainsValuesAfterBack() {
        val f = launch(); ui.onNodeWithTag("quest-filter-open").performClick()
        ui.onNodeWithTag("quest-filter-min").performTextInput("100"); ui.onNodeWithTag("quest-filter-max").performTextInput("250")
        capture("filter-xp-draft")
        ui.onNodeWithTag("quest-filter-location").performScrollTo().performClick()
        ui.onNodeWithText("Magelang").performClick()
        ui.onNodeWithTag("quest-filter-apply").performScrollTo().performClick()
        ui.waitUntil(5_000) { f.catalogCalls.last().first.minXP == 100 }
        assertEquals(f.location.id, f.catalogCalls.last().first.locationId); assertEquals(250, f.catalogCalls.last().first.maxXP)
        ui.onNodeWithTag("quest-filter-open").assertTextEquals("Magelang · 100–250 XP"); capture("catalog-filtered")
        ready(f); back(); ui.onNodeWithTag("quest-filter-open").performClick()
        ui.onNodeWithTag("quest-filter-min").assertTextContains("100"); ui.onNodeWithTag("quest-filter-max").assertTextContains("250")
        ui.onNodeWithTag("quest-filter-min").performTextReplacement("300"); ui.onNodeWithTag("quest-filter-apply").performScrollTo().assertIsNotEnabled()
        assertEquals(0, f.startCalls.get())
    }
    @Test fun takenCardShowsOnlyOwnedModeTitleAndTheMappedLabeledProgress() {
        val f = launch(); ui.onNodeWithTag("quest-tab-taken").performClick()
        ui.onNodeWithText(f.activeCard.title).assertExists(); ui.onNodeWithText("PREVIEW HADIAH").assertDoesNotExist(); ui.onNodeWithTag("quest-filter-open").assertDoesNotExist()
        val progress = ui.onNodeWithTag("quest-owner-progress", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot; val density = ui.activity.resources.displayMetrics.density
        assertEquals(280f * density, progress.width, 1f); assertEquals(28f * density, progress.height, density)
        val pixels = ui.onNodeWithTag("quest-owner-progress", useUnmergedTree = true).captureToImage().toPixelMap()
        val beyondHalf = (pixels.width * .525f).toInt()
        assertFalse("The 2/4 fill must not extend beyond half the track", (0 until pixels.height).any { pixels[beyondHalf, it].toArgb() == 0xFF3730A3.toInt() })
        ui.onNodeWithContentDescription("2 dari 4 aktivitas selesai", useUnmergedTree = true).assertExists(); capture("catalog-taken")
        ui.onNodeWithTag("quest-taken-${f.running.id}").performClick()
        ui.waitUntil(5_000) { f.continueCalls.get() > 0 }; assertEquals(0, f.startCalls.get()); back()
        ui.onNodeWithTag("quest-tab-taken").assertIsSelected()
    }
    @Test fun detailRewardPreviewTravelerAvatarsAndCheckpointUseTheFigmaGeometry() {
        val f = launch(); ready(f); ui.onNodeWithText("PREVIEW HADIAH").performScrollTo().assertIsDisplayed(); capture("detail-ready-summary")
        ui.onNodeWithText("12 dari 20 kuota tersedia").performScrollTo().assertExists(); ui.onNodeWithTag("quest-take").assertIsEnabled()
        ui.onNodeWithTag("quest-checkpoint-activity-1").performScrollTo()
        ui.onNodeWithTag("quest-checkpoint-spot-${f.spot.id}").assertExists()
        val density = ui.activity.resources.displayMetrics.density
        val stars = ui.onNodeWithTag("checkpoint-stars", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(64f * density, stars.width, 1f); assertEquals(12f * density, stars.height, 1f)
        (0..4).forEach { index -> assertEquals(12f * density, ui.onNodeWithTag("checkpoint-star-$index", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width, 1f) }
        ui.onNodeWithContentDescription("Referensi AI: 4,8, 1200 komentar").assertExists(); capture("detail-ready-checkpoints")
        assertEquals(0, f.activityCalls.get()); ui.onNodeWithTag("quest-checkpoint-spot-${f.spot.id}").performClick()
        ui.onNodeWithTag("traveler-top-bar").assert(hasAnyDescendant(hasText("Spot detail")))
        ui.onNodeWithText(f.spot.title).assertExists()
        ui.onNodeWithTag("spot-detail-content").assertExists()
    }
    @Test fun activeDetailUsesBoundProgressAndVerifiedActivityStatusesWithAReadOnlyContinue() {
        val f = launch(); ui.onNodeWithText(f.activeCard.title).performScrollTo().performClick()
        ui.onNodeWithContentDescription("2 dari 4 aktivitas selesai").performScrollTo().assertExists(); capture("detail-active-progress")
        ui.onNodeWithTag("quest-detail").performScrollToNode(hasTestTag("quest-checkpoint-activity-1")); ui.onAllNodesWithText("Selesai").onFirst().assertExists(); capture("detail-active-checkpoints")
        ui.onNodeWithTag("quest-continue").performScrollTo().performClick(); ui.waitUntil(5_000) { f.continueCalls.get() > 0 }
        assertEquals(0, f.startCalls.get()); assertEquals(0, f.activityCalls.get())
    }
    @Test fun fullQuotaDisablesAdmissionWithoutHidingTheRewardPreview() {
        val f = TravelerQuestDesignFixture(); f.readyReader = f.readyReader.copy(remaining = 0); launch(f); ready(f)
        ui.onNodeWithTag("quest-take").performScrollTo().assertIsNotEnabled(); ui.onNodeWithText("PREVIEW HADIAH").assertExists(); capture("detail-quota-full")
        assertEquals(0, f.startCalls.get())
    }
    @Test fun failedCatalogAndPairedReaderOfferReadRetryWithoutAnEmptyOrSuccessfulPreview() {
        val f = TravelerQuestDesignFixture(); f.catalogFailure = CatalogResult.NetworkError; launch(f)
        ui.onNodeWithText("Belum ada Quest").assertDoesNotExist(); capture("catalog-error")
        f.catalogFailure = null; ui.onNodeWithText("Coba lagi").performClick(); ui.onNodeWithText(f.card.title).assertExists()
        f.readerFailure = CatalogResult.HttpError(503); ready(f)
        ui.onNodeWithTag("quest-reward-preview").assertDoesNotExist(); ui.onNodeWithTag("quest-take").assertDoesNotExist(); capture("detail-read-error")
        f.readerFailure = null; ui.onNodeWithText("Coba lagi").performClick(); ui.onNodeWithTag("quest-reward-preview").assertExists(); assertEquals(0, f.startCalls.get())
    }
    @Test fun largeFontKeepsFilterActionsAndCheckpointNavigationUsable() {
        val f = launch(scale = 2f); capture("catalog-font-200")
        ui.onNodeWithTag("quest-mode-personal").performScrollTo().performClick(); capture("catalog-personal-font-200")
        ui.onNodeWithTag("quest-mode-group").performScrollTo().performClick(); capture("catalog-group-font-200")
        ui.onNodeWithTag("quest-mode-all").performScrollTo().performClick()
        ui.onNodeWithTag("quest-filter-open").performScrollTo().performClick()
        ui.onNodeWithTag("quest-filter-min").performTextInput("0")
        ui.onNodeWithTag("quest-filter-apply").performScrollTo().assertIsDisplayed(); capture("filter-font-200")
        back(); ready(f); ui.onNodeWithTag("quest-detail").performScrollToNode(hasTestTag("quest-checkpoint-activity-1"))
        ui.onNodeWithTag("quest-checkpoint-spot-${f.spot.id}").performScrollTo().assertIsDisplayed(); capture("detail-font-200")
    }
    @Test fun selectedModesRemainLegibleAtIntermediateFontScale() {
        launch(scale = 1.3f); capture("catalog-font-130")
        ui.onNodeWithTag("quest-mode-personal").performClick(); ui.onNodeWithTag("quest-mode-personal").assertIsSelected(); capture("catalog-personal-font-130")
        ui.onNodeWithTag("quest-mode-group").performClick(); ui.onNodeWithTag("quest-mode-group").assertIsSelected(); capture("catalog-group-font-130")
    }
    @Test fun retryRepeatsFailedRefreshAndAppendPagesWithCachedRowsIntact() {
        val f = TravelerQuestDesignFixture(); f.totalPages = 2
        f.extraCards = (1..8).map { QuestCatalogItem(f.card.copy(id = "extra-$it", slug = "extra-$it", title = "Quest simulasi $it"), "active", "available", false) }
        launch(f); ready(f); f.catalogFailure = CatalogResult.NetworkError; back()
        ui.onNodeWithTag("quest-catalog").performScrollToNode(hasText("Coba lagi")); capture("catalog-refresh-error")
        f.catalogFailure = null; ui.onNodeWithText("Coba lagi").performClick()
        ui.waitUntil(5_000) { f.catalogCalls.size >= 3 }; assertEquals(listOf(1, 1), f.catalogCalls.takeLast(2).map { it.second })
        f.catalogFailure = CatalogResult.NetworkError
        ui.onNodeWithTag("quest-catalog").performScrollToNode(hasText("Quest simulasi 8"))
        ui.waitUntil(5_000) { f.catalogCalls.last().second == 2 }
        ui.onNodeWithTag("quest-catalog").performScrollToNode(hasText("Coba lagi")); capture("catalog-append-error")
        f.catalogFailure = null; ui.onNodeWithText("Coba lagi").performClick()
        ui.waitUntil(5_000) { f.catalogCalls.count { it.second == 2 } >= 2 }
        assertEquals(listOf(2, 2), f.catalogCalls.takeLast(2).map { it.second }); assertEquals(0, f.startCalls.get())
        ui.onNodeWithTag("quest-catalog").performScrollToNode(hasText(f.card.title)); ui.onNodeWithText(f.card.title).assertExists()
    }
}
