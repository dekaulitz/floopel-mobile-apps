package com.floopel.traveler

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.testing.TravelerCommunitySearchFixture
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.abs

/** Exercises real search/navigation bindings with isolated read-only repositories. */
@RunWith(AndroidJUnit4::class)
class TravelerCommunitySearchBackTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val f = TravelerCommunitySearchFixture()
    private val originalLocale = Locale.getDefault()
    @After fun after() { Locale.setDefault(originalLocale) }
    private fun launch() {
        Locale.setDefault(Locale.forLanguageTag("id"))
        ui.runOnUiThread {
            WindowCompat.enableEdgeToEdge(ui.activity.window)
            WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView).run {
                isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
            }
        }
        ui.setContent { TravelerApp(f.runtime, {}, language = "id", backHandler = { enabled, back -> BackHandler(enabled, back) }) }
        ui.waitUntil(8_000) { ui.onAllNodesWithText("Quest").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun field() = ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("catalog-search")), useUnmergedTree = true)
    private fun assertQuery(value: String) = field().performScrollTo()
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
    private fun query(value: String, public: Boolean = false) {
        val previous = if (public) f.publicQueries.size else f.tripQueries.size
        field().performScrollTo().performTextReplacement(value)
        try {
            ui.waitUntil(5_000) {
                if (public) f.publicQueries.size > previous && f.publicQueries.last().query == value
                else f.tripQueries.size > previous && f.tripQueries.last().query == value
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Fixture queries: trips=${f.tripQueries}, public=${f.publicQueries}; " +
                ui.onRoot(useUnmergedTree = true).printToString().take(8_000), failure)
        }
        ui.waitForIdle()
    }
    private fun global() { ui.onNodeWithTag("navigation-open_trips").performClick(); assertQuery("") }
    private fun tripTab() = ui.onNodeWithText("Open Trip publik")
    private fun community(public: Boolean) {
        ui.onNodeWithTag("navigation-community").performClick()
        ui.onNodeWithTag("community-heading").assertExists()
        if (public) ui.onNodeWithText("Itinerary publik").performClick() else tripTab().performClick()
        assertQuery("")
    }
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); ui.waitForIdle() }
    private fun readerAndBack(public: Boolean = false) {
        ui.onNodeWithTag(if (public) "public-plan-action" else if (ui.onAllNodesWithTag("community-trip-action").fetchSemanticsNodes().isNotEmpty())
            "community-trip-action" else "trip-catalog-action").performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("catalog-search").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithText(if (public) f.base.publicSnapshot.title else f.base.openTrip.name).assertExists()
        back(); if (ui.onAllNodesWithTag("catalog-search").fetchSemanticsNodes().isEmpty()) back()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("catalog-search").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("community-search-audit"))
        directory.mkdirs()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        directory.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun globalOpenTripReaderBackRetainsFieldRequestAndResults() {
        launch(); global(); query("bromo"); readerAndBack(); assertQuery("bromo")
        assertEquals("bromo", f.tripQueries.last().query); assertNull(f.tripQueries.last().locationId)
        ui.onNodeWithText("Trip hasil bromo").assertExists(); capture("global-open-trip-back")
    }
    @Test fun locationOpenTripReaderBackRetainsQueryAndLocation() {
        launch(); ui.onNodeWithTag("home-content").performScrollToNode(hasTestTag("home-rail-LOCATION"))
        ui.onNodeWithTag("home-location-${f.location.id}").performClick()
        ui.onNodeWithTag("location-detail").performScrollToNode(hasText("Open Trip tersedia"))
        // Layout-only rows are flattened in semantics; select the action aligned with this heading.
        val headingY = ui.onNodeWithText("Open Trip tersedia").fetchSemanticsNode().boundsInRoot.center.y
        ui.onNode(hasText("Lihat semua") and SemanticsMatcher("same Open Trip header") {
            abs(it.boundsInRoot.center.y - headingY) < 2f
        }).performClick()
        try {
            ui.waitUntil(5_000) { f.tripQueries.any { it.locationId == f.location.id && it.limit == 20 } }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("Fixture queries: ${f.tripQueries}; " +
                ui.onRoot(useUnmergedTree = true).printToString().take(10_000), failure)
        }
        query("bromo"); readerAndBack(); assertQuery("bromo")
        assertEquals(f.location.id, f.tripQueries.last().locationId); assertEquals("bromo", f.tripQueries.last().query)
        capture("location-open-trip-back")
    }
    @Test fun communityOpenTripReaderBackRetainsItsTabQueryAndResults() {
        launch(); community(false); query("bromo"); readerAndBack(); assertQuery("bromo")
        ui.onNodeWithTag("community-trip-action").assertExists()
        assertEquals("bromo", f.tripQueries.last().query); assertNull(f.tripQueries.last().locationId)
        capture("community-open-trip-back")
    }
    @Test fun communityPublicPlanReaderBackRetainsItsTabQueryAndResults() {
        launch(); community(true); query("bromo", public = true); readerAndBack(public = true); assertQuery("bromo")
        ui.onNodeWithTag("public-plan-action").assertExists()
        assertEquals(TravelerCommunitySearchFixture.PublicQuery("bromo", null, 1), f.publicQueries.last())
        capture("community-public-plan-back")
    }
    @Test fun explicitTabResetAndClearDoNotBorrowOrResurrectOtherOriginQueries() {
        launch(); global(); query("global"); readerAndBack(); community(true); query("public", public = true)
        tripTab().performClick(); assertQuery("")
        query("community"); readerAndBack(); assertQuery("community")
        ui.onNodeWithText("Itinerary publik").performScrollTo().performClick(); assertQuery("")
        query("public-new", public = true); query("", public = true); readerAndBack(public = true); assertQuery("")
        global(); readerAndBack(); assertQuery(""); assertEquals("", f.tripQueries.last().query)
        capture("explicit-clear-and-reset")
    }
    private fun failureAndRetry(public: Boolean) {
        launch(); if (public) community(true) else global(); query("bromo", public)
        f.tripSearchFails = true; f.publicSearchFails = true; readerAndBack(public); assertQuery("bromo")
        ui.onNodeWithText("Coba lagi").performScrollTo().assertExists(); capture(if (public) "public-plan-back-error" else "open-trip-back-error")
        f.tripSearchFails = false; f.publicSearchFails = false; ui.onNodeWithText("Coba lagi").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText(if (public) "Rencana hasil bromo" else "Trip hasil bromo").fetchSemanticsNodes().isNotEmpty() }
        assertQuery("bromo"); assertEquals("bromo", if (public) f.publicQueries.last().query else f.tripQueries.last().query)
        assertEquals(0, f.navigation.unexpectedWrites.get())
    }
    @Test fun failedTripReadAndRetryKeepTheBoundQuery() = failureAndRetry(false)
    @Test fun failedPublicReadAndRetryKeepTheBoundQuery() = failureAndRetry(true)
    @Test fun actorGenerationResetClearsSearchBeforeAnotherReaderVisit() {
        launch(); global(); query("private-search-term"); readerAndBack()
        ui.runOnIdle { f.base.identityState.value = f.base.identityState.value.copy(generation = 2) }
        ui.waitForIdle()
        global(); readerAndBack(); assertQuery(""); assertEquals("", f.tripQueries.last().query)
        capture("actor-reset")
    }
}
