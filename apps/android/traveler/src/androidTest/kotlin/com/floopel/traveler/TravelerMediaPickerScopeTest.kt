package com.floopel.traveler

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.testing.TravelerPhotoFixture
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** A picker result belongs to the route and actor that requested it, including failed reads. */
@RunWith(AndroidJUnit4::class)
class TravelerMediaPickerScopeTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val gate = CompletableDeferred<Unit>()
    private val calls = AtomicInteger()
    private var visible by mutableStateOf(true)
    private fun start(f: TravelerPhotoFixture = TravelerPhotoFixture(), cancel: Boolean = false): TravelerPhotoFixture {
        ui.setContent { if (visible) TravelerApp(f.runtime, {}, photoPicker = {
            calls.incrementAndGet(); withContext(NonCancellable) { gate.await() }
            if (cancel) null else throw IOException("Synthetic selected-file read failure")
        }, backHandler = { enabled, back -> BackHandler(enabled, back) }) }
        ui.waitUntil(8_000) { ui.onAllNodesWithText("Halo, Traveler").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("Profile").onLast().performClick(); ui.onNodeWithText("Itinerary saya").performScrollTo().performClick()
        ui.onNodeWithText(f.base.plan.itinerary.title).performClick(); ui.onNodeWithText("Buat Trip dari itinerary").performScrollTo().performClick()
        ui.onNodeWithText("Pilih banner Trip (opsional)").performScrollTo().performClick(); ui.waitUntil(5_000) { calls.get() == 1 }
        return f
    }
    private fun assertNoWrite(f: TravelerPhotoFixture) { assertEquals(0, f.base.bannerCalls.get()); assertEquals(0, f.base.tripCreateCalls.get()) }
    private fun noError() { ui.onNodeWithText("Foto tidak dapat dibaca. Pilih JPG, PNG, atau WebP berukuran maksimum 5 MB.").assertDoesNotExist() }
    @Test fun lateReadErrorAfterBackCannotAppearOnTheItinerary() {
        val f = start(); ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        gate.complete(Unit); ui.waitForIdle(); ui.onNodeWithTag("itinerary-title").assertExists(); noError(); assertNoWrite(f)
    }
    @Test fun sameRouteReadFailureStillShowsItsRecoverableError() {
        val f = start(); gate.complete(Unit)
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Foto tidak dapat dibaca. Pilih JPG, PNG, atau WebP berukuran maksimum 5 MB.").fetchSemanticsNodes().isNotEmpty() }
        assertNoWrite(f)
    }
    @Test fun actorChangeDiscardsAnOldPickerReadFailure() {
        val f = start(); ui.runOnUiThread { f.base.identityState.value = TravelerIdentityView(generation = 2) }
        gate.complete(Unit); ui.waitForIdle(); noError(); assertNoWrite(f)
    }
    @Test fun disposedRootCannotStoreLateErrorInItsRetainedViewModel() {
        val f = start(); ui.runOnUiThread { visible = false }; ui.waitForIdle()
        gate.complete(Unit); ui.waitForIdle(); ui.runOnUiThread { visible = true }; ui.waitForIdle(); noError(); assertNoWrite(f)
    }
    @Test fun nullCancellationStaysSilentAndDoesNotUpload() {
        val f = start(cancel = true); gate.complete(Unit); ui.waitForIdle(); noError(); assertNoWrite(f)
    }
}
