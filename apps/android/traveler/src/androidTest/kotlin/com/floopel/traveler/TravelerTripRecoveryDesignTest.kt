package com.floopel.traveler

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerTripRepository
import com.floopel.feature.traveler.testing.TravelerFixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic domain boundary; durable-key and actor/snapshot rules are tested with the actual adapter on host. */
@RunWith(AndroidJUnit4::class)
class TravelerTripRecoveryDesignTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val f = TravelerFixture()
    private val cancels = AtomicInteger()
    private val reviews = AtomicInteger()
    private val releases = AtomicInteger()
    @Volatile private var reviewFails = false
    private var reviewed = false
    private fun launch() {
        val r = f.runtime
        val trips = object : TravelerTripRepository by r.trips {
            override suspend fun lifecycle(id: String, action: TripLifecycleAction, key: String): CatalogResult<Unit> {
                assertEquals(TripLifecycleAction.CANCEL, action)
                cancels.incrementAndGet(); f.trip = f.trip.copy(status = TripStatus.CANCELLED)
                return CatalogResult.NetworkError
            }
            override suspend fun reviewPendingMutation(): CatalogResult<List<TravelerTrip>> {
                reviews.incrementAndGet(); reviewed = !reviewFails
                return if (reviewFails) CatalogResult.NetworkError else CatalogResult.Ready(listOf(f.trip))
            }
            override suspend fun startNewAction(): CatalogResult<Unit> {
                assertTrue(reviewed); releases.incrementAndGet(); reviewed = false
                return CatalogResult.Ready(Unit)
            }
        }
        val runtime = TravelerRuntime(r.identity, r.catalog, r.itineraries, r.distribution, trips, r.notifications, r.account,
            r.legal, r.registration, r.publicContent, r.savedSpots, r.questGroups, r.newOperationKey, r.startup)
        ui.runOnUiThread { WindowCompat.enableEdgeToEdge(ui.activity.window) }
        ui.setContent { TravelerApp(runtime, {}, backHandler = { enabled, back -> BackHandler(enabled, back) }) }
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("home-content").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Lanjutkan trip").performScrollTo().performClick()
        ui.onNodeWithText("Batalkan Trip").performScrollTo().performClick()
        ui.onNode(hasText("Konfirmasi") and hasClickAction()).performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("trip-mutation-recovery").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Dibatalkan").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Batalkan Trip").assertDoesNotExist()
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val out = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("trip-recovery-audit"))
        out.mkdirs()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        out.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun terminalReaderCanReviewAndExplicitlyReleaseWithoutReplayingTheCancel() {
        launch(); assertEquals(1, cancels.get()); assertEquals(0, releases.get()); capture("terminal-reader-pending")
        ui.onNodeWithTag("trip-recovery-review").performClick()
        ui.onNodeWithTag("trip-recovery-continue").assertIsEnabled(); assertEquals(1, reviews.get()); assertEquals(0, releases.get())
        capture("review-confirmation")
        ui.onNodeWithTag("trip-recovery-continue").performClick()
        ui.onNodeWithTag("trip-mutation-recovery").assertDoesNotExist()
        assertEquals(1, cancels.get()); assertEquals(1, releases.get()); assertEquals(0, f.tripCreateCalls.get())
    }
    @Test fun reviewFailureCannotEnableReleaseAndRetryOnlyReviews() {
        reviewFails = true; launch(); ui.onNodeWithTag("trip-recovery-review").performClick()
        ui.onNodeWithTag("trip-recovery-continue").assertDoesNotExist(); assertEquals(0, releases.get()); capture("review-failed")
        ui.onNodeWithTag("trip-recovery-review").assertDoesNotExist()
        reviewFails = false; ui.onNode(hasText("Coba lagi") and hasAnyAncestor(hasTestTag("trip-mutation-recovery"))).performClick()
        ui.onNodeWithTag("trip-recovery-continue").assertIsEnabled(); assertEquals(2, reviews.get()); assertEquals(1, cancels.get()); assertEquals(0, releases.get())
    }
    @Test fun nativeBackDismissesReviewWithoutReleasingTheIntent() {
        launch(); ui.onNodeWithTag("trip-recovery-review").performClick(); ui.onNodeWithTag("trip-recovery-continue").assertExists()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        ui.onNodeWithTag("trip-recovery-continue").assertDoesNotExist(); ui.onNodeWithTag("trip-recovery-review").assertExists()
        assertEquals(0, releases.get()); assertEquals(1, cancels.get())
    }
}
