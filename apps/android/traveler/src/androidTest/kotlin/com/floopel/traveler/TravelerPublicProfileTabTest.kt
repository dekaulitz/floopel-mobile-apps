package com.floopel.traveler

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.testing.TravelerFigmaFixture
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class TravelerPublicProfileTabTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val fixture = TravelerFigmaFixture()
    private val profileReads = AtomicInteger()
    private val publicReads = AtomicInteger()
    private val progressReads = AtomicInteger()
    private fun launch() {
        fixture.beforeProfile = { profileReads.incrementAndGet() }
        fixture.beforePublicProfile = { publicReads.incrementAndGet() }
        fixture.beforePublicProgress = { progressReads.incrementAndGet() }
        ui.runOnUiThread { WindowCompat.enableEdgeToEdge(ui.activity.window) }
        ui.setContent { TravelerApp(fixture.runtime, {}, backHandler = { enabled, back -> BackHandler(enabled, back) }) }
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("navigation-profile").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("navigation-profile").performClick()
        ui.onNodeWithText("Profil publik").performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Rute publik simulasi").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun readCounts() = listOf(profileReads.get(), publicReads.get(), progressReads.get())
    private fun capture(name: String) {
        ui.waitForIdle()
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("passport-audit"))
        directory.mkdirs()
        val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
        directory.resolve("$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun switchingTabsLoadsOnlyThePanelAndPreservesTheHeaderDuringAndAfterLoading() {
        launch()
        val counts = readCounts()
        val avatar = ui.onNodeWithTag("public-profile-avatar").fetchSemanticsNode().boundsInRoot
        val manage = ui.onNodeWithTag("public-profile-manage").fetchSemanticsNode().boundsInRoot
        val tab = ui.onNodeWithTag("profile-tab-badges").fetchSemanticsNode().boundsInRoot
        val gate = CompletableDeferred<Unit>(); fixture.beforeBadge = { gate.await() }
        ui.onNodeWithTag("profile-tab-badges").performClick()
        ui.onNodeWithTag("profile-tab-badges").assertIsSelected()
        ui.onNodeWithText("Memuat…").assertExists()
        assertEquals(avatar, ui.onNodeWithTag("public-profile-avatar").fetchSemanticsNode().boundsInRoot)
        assertEquals(manage, ui.onNodeWithTag("public-profile-manage").fetchSemanticsNode().boundsInRoot)
        assertEquals(tab, ui.onNodeWithTag("profile-tab-badges").fetchSemanticsNode().boundsInRoot)
        assertEquals(counts, readCounts()); capture("profile-tab-loading-stable")
        gate.complete(Unit)
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Badge simulasi 1").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(tab, ui.onNodeWithTag("profile-tab-badges").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithTag("profile-tab-saved").performClick(); ui.onNodeWithText("Spot simulasi").assertExists()
        assertEquals(tab, ui.onNodeWithTag("profile-tab-badges").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithTag("profile-tab-itineraries").performClick(); ui.onNodeWithText("Pribadi").performClick()
        ui.onNodeWithText("Rencana budaya simulasi").assertExists()
        assertEquals(counts, readCounts()); capture("profile-tab-private-stable")
        assertEquals(0, fixture.profileWrites.get()); assertEquals(0, fixture.savedWrites.get())
    }
    @Test fun aShortOrEmptyTabDoesNotClampTheScrollAndMoveTheTabsDown() {
        launch()
        val density = ui.activity.resources.displayMetrics.density
        ui.onNodeWithTag("public-profile-content").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 70f * density) }
        val before = ui.onNodeWithTag("profile-tab-badges").fetchSemanticsNode().boundsInRoot
        val gate = CompletableDeferred<Unit>(); fixture.beforeBadge = { gate.await() }
        ui.onNodeWithTag("profile-tab-badges").performClick()
        assertEquals(before, ui.onNodeWithTag("profile-tab-badges").fetchSemanticsNode().boundsInRoot)
        gate.complete(Unit)
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Badge simulasi 1").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before, ui.onNodeWithTag("profile-tab-badges").fetchSemanticsNode().boundsInRoot)
        capture("profile-tab-scroll-anchor")
    }
    @Test fun aCancelledTabResponseCannotReplaceTheCurrentPanelAndRetryDoesNotReloadTheHeader() {
        launch(); val counts = readCounts()
        val gate = CompletableDeferred<Unit>(); fixture.beforeBadge = { gate.await() }
        ui.onNodeWithTag("profile-tab-badges").performClick(); ui.onNodeWithTag("profile-tab-saved").performClick()
        gate.complete(Unit); ui.onNodeWithText("Spot simulasi").assertExists()
        ui.onNodeWithTag("profile-tab-saved").assertIsSelected(); ui.onNodeWithText("Badge simulasi 1").assertDoesNotExist()
        fixture.beforeBadge = { error("Synthetic offline badge service") }
        ui.onNodeWithTag("profile-tab-badges").performClick(); ui.onNodeWithText("Coba lagi").assertExists()
        fixture.beforeBadge = {}
        ui.onNodeWithText("Coba lagi").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Badge simulasi 1").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(counts, readCounts())
    }
}
