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
import com.floopel.feature.traveler.domain.repository.TravelerItineraryRepository
import com.floopel.feature.traveler.testing.TravelerFixture
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** UI boundary only. The accepted-unknown/24h clock/key sequence uses the actual adapter in host tests. */
@RunWith(AndroidJUnit4::class)
class TravelerItineraryRecoveryDesignTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val originalLocale = Locale.getDefault()
    @After fun restoreLocale() { Locale.setDefault(originalLocale) }
    private val f = TravelerFixture()
    private val reviews = AtomicInteger()
    private val releases = AtomicInteger()
    private val posts = AtomicInteger(1) // A was accepted before this UI fixture was restored.
    @Volatile private var pending = true
    @Volatile private var reviewed = false
    @Volatile private var reviewFails = false
    private fun launch(language: String = "id", scale: Float = 1f) {
        Locale.setDefault(Locale.forLanguageTag(language))
        val r = f.runtime
        val itineraries = object : TravelerItineraryRepository by r.itineraries {
            override suspend fun clone(id: String): ItineraryWriteResult<OwnedItineraryDetail> {
                if (pending) return ItineraryWriteResult.Expired
                posts.incrementAndGet(); return ItineraryWriteResult.Completed(f.plan)
            }
            override suspend fun reviewPendingMutation(): CatalogResult<List<OwnedItinerary>> {
                reviews.incrementAndGet(); reviewed = !reviewFails
                return if (reviewFails) CatalogResult.NetworkError else CatalogResult.Ready(listOf(f.plan.itinerary.copy(title = "Saved copy A")))
            }
            override suspend fun startNewAction(): CatalogResult<Unit> {
                assertTrue(reviewed); releases.incrementAndGet(); pending = false; reviewed = false
                return CatalogResult.Ready(Unit)
            }
        }
        val runtime = TravelerRuntime(r.identity, r.catalog, itineraries, r.distribution, r.trips, r.notifications, r.account,
            r.legal, r.registration, r.publicContent, r.savedSpots, r.questGroups, r.newOperationKey, r.startup)
        ui.runOnUiThread {
            WindowCompat.enableEdgeToEdge(ui.activity.window)
            WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView).run {
                isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
            }
        }
        ui.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
            TravelerApp(runtime, {}, language = language, backHandler = { enabled, back -> BackHandler(enabled, back) })
        } }
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("navigation-community").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("navigation-community").performClick()
        ui.onNodeWithTag("public-plan-copy").performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("itinerary-mutation-recovery").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, posts.get()); assertEquals(0, releases.get())
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val out = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("itinerary-recovery-audit"))
        out.mkdirs()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        out.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun expiredCopyRequiresReviewAndAnExplicitReleaseWithoutAnAutomaticPost() {
        launch(); capture("expired-itinerary")
        ui.onNodeWithTag("itinerary-recovery-review").performClick()
        ui.onNodeWithText("Saved copy A").assertExists(); ui.onNodeWithTag("itinerary-recovery-continue").assertIsEnabled()
        assertEquals(1, reviews.get()); assertEquals(0, releases.get()); capture("itinerary-review")
        ui.onNodeWithTag("itinerary-recovery-continue").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("itinerary-mutation-recovery").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithTag("itinerary-mutation-recovery").assertDoesNotExist()
        assertEquals(1, releases.get()); assertEquals(1, posts.get())
    }
    @Test fun failedReviewCannotEnableReleaseAndRetryDoesNotReplayTheCopy() {
        reviewFails = true; launch(); ui.onNodeWithTag("itinerary-recovery-review").performClick()
        ui.onNodeWithTag("itinerary-recovery-continue").assertDoesNotExist(); assertTrue(pending); capture("itinerary-review-failed")
        reviewFails = false
        ui.onNode(hasText("Coba lagi") and hasAnyAncestor(hasTestTag("itinerary-mutation-recovery"))).performClick()
        ui.onNodeWithTag("itinerary-recovery-continue").assertIsEnabled()
        assertEquals(2, reviews.get()); assertEquals(0, releases.get()); assertEquals(1, posts.get())
    }
    @Test fun nativeBackDismissesReviewAndPreservesThePendingIntent() {
        launch(); ui.onNodeWithTag("itinerary-recovery-review").performClick(); ui.onNodeWithTag("itinerary-recovery-continue").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.onNodeWithTag("itinerary-recovery-continue").assertDoesNotExist(); ui.onNodeWithTag("itinerary-recovery-review").assertExists()
        assertTrue(pending); assertEquals(0, releases.get()); assertEquals(1, posts.get())
    }
    @Test fun englishReviewAt130PercentKeepsTheDecisionReachable() {
        launch(language = "en", scale = 1.3f); ui.onNodeWithTag("itinerary-recovery-review").performClick()
        ui.onNodeWithText("Latest itineraries").assertExists(); ui.onNodeWithTag("itinerary-recovery-continue").assertIsDisplayed().assertIsEnabled()
        capture("itinerary-review-en-font130")
        ui.onNodeWithTag("itinerary-recovery-continue").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("itinerary-mutation-recovery").fetchSemanticsNodes().isEmpty() }
        assertEquals(1, releases.get()); assertEquals(1, posts.get())
    }
}
