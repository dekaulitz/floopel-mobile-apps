package com.floopel.traveler

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.testing.TravelerNavigationFixture
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.abs

/** Current APK bindings with synthetic repositories; no backend or real notification acknowledgement. */
@RunWith(AndroidJUnit4::class)
class TravelerNavigationRegressionTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val f = TravelerNavigationFixture()
    private val originalLocale = Locale.getDefault()
    private val scale = mutableFloatStateOf(1f)
    private var language = "id"
    @After fun after() { Locale.setDefault(originalLocale) }
    private fun launch(locale: String = "id") {
        language = locale; Locale.setDefault(Locale.forLanguageTag(locale))
        ui.runOnUiThread {
            WindowCompat.enableEdgeToEdge(ui.activity.window)
            WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView).run {
                isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
            }
        }
        ui.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.floatValue)) {
            TravelerApp(f.runtime, {}, language = locale, backHandler = { enabled, back -> BackHandler(enabled, back) })
        } }
        ui.waitUntil(8_000) { ui.onAllNodesWithText("Quest").fetchSemanticsNodes().isNotEmpty() || ui.onAllNodesWithText("Quests").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); ui.waitForIdle() }
    private fun searchField() = ui.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("catalog-search")), useUnmergedTree = true)
    private fun query(text: String) {
        searchField().performScrollTo().performTextReplacement(text)
        ui.waitUntil(5_000) { f.reads.any { it == "explorer:$text" || it.startsWith("search:$text:") } }
        ui.waitForIdle()
    }
    private fun assertQuery(text: String) = searchField()
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
    private fun detailAndBack() {
        ui.onNodeWithText(f.spot.title).performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("catalog-search").fetchSemanticsNodes().isEmpty() }
        back()
        if (ui.onAllNodesWithTag("catalog-search").fetchSemanticsNodes().isEmpty()) back()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("catalog-search").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun globalArchive() { ui.onAllNodesWithText("Lihat semua").onFirst().performScrollTo().performClick(); ui.onNodeWithTag("all-spots").assertExists() }
    private fun openNotification() {
        ui.onNodeWithContentDescription(if (language == "id") "Notifikasi" else "Notifications").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("notification-card-${f.base.inboxId}").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("notification-card-${f.base.inboxId}").performClick()
    }
    private fun readyOwner() {
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("owner-moderation-status-${f.actionId}").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(5_000) { f.base.ackCalls.get() == 1 }; ui.waitForIdle()
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("navigation-regression-audit")); directory.mkdirs()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        directory.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun explorerResultBackKeepsTheBoundQuery() {
        launch(); ui.onAllNodesWithText("Explorer").onLast().performClick(); query("museum"); detailAndBack()
        assertQuery("museum"); capture("explorer-back")
        assertEquals("explorer:museum", f.reads.last { it.startsWith("explorer:") })
    }
    @Test fun globalArchiveResultBackKeepsQueryAndExplicitClearRemainsCleared() {
        launch(); globalArchive(); query("museum"); detailAndBack()
        assertQuery("museum"); capture("global-back")
        searchField().performTextClearance(); detailAndBack()
        assertQuery(""); assertTrue(f.reads.contains("browse:SPOT::1"))
    }
    @Test fun locationArchiveBackKeepsItsQueryAndGeographicScope() {
        launch(); ui.onNodeWithTag("home-content").performScrollToNode(hasTestTag("home-rail-LOCATION"))
        ui.onNodeWithTag("home-location-${f.location.id}").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("location-detail").fetchSemanticsNodes().isNotEmpty() }
        capture("scoped-location-entry")
        ui.onAllNodes(hasText("Lihat semua") and hasAnyAncestor(hasTestTag("location-detail")), useUnmergedTree = true)
            .onFirst().performScrollTo().performClick()
        ui.waitUntil(5_000) { f.reads.contains("browse:SPOT:${f.location.id}:1") }
        ui.onNodeWithTag("all-spots").assertExists(); query("museum"); detailAndBack()
        assertQuery("museum")
        assertTrue("Requests: ${f.reads}", f.reads.contains("search:museum:${f.location.id}:1"))
        ui.onNodeWithTag("all-spots").performScrollToNode(hasText("Spot di ${f.location.title}"))
        ui.onNodeWithText("Spot di ${f.location.title}").assertExists()
        capture("scoped-back")
    }
    @Test fun explorerAndArchiveOriginsKeepSeparateQueries() {
        launch(); ui.onAllNodesWithText("Explorer").onLast().performClick(); query("museum"); detailAndBack()
        ui.onAllNodesWithText("Home").onLast().performClick(); globalArchive()
        assertQuery(""); query("garden"); detailAndBack()
        assertQuery("garden"); back()
        ui.onAllNodesWithText("Explorer").onLast().performClick(); assertQuery("museum")
    }
    @Test fun searchBackFailureAndRetryKeepTheSameBoundQuery() {
        launch(); globalArchive(); query("museum"); f.searchFails = true; detailAndBack()
        assertQuery("museum")
        ui.onNodeWithText("Coba lagi").performScrollTo().assertExists(); capture("search-error")
        f.searchFails = false; ui.onNodeWithText("Coba lagi").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText(f.spot.title).fetchSemanticsNodes().isNotEmpty() }
        assertQuery("museum"); assertEquals(0, f.unexpectedWrites.get())
    }
    @Test fun moderationInboxOpensTheOwnerStatusAndPlanBackWithoutDuplicateAck() {
        launch(); openNotification(); readyOwner()
        ui.onNodeWithText("Publikasi disembunyikan").assertExists()
        val bounds = ui.onNodeWithTag("owner-moderation-status-${f.actionId}").getUnclippedBoundsInRoot()
        assertTrue(abs(bounds.left.value - 20f) < .6f)
        ui.onNodeWithTag("owner-moderation-status-${f.actionId}").assertWidthIsEqualTo(360.dp)
        ui.onNodeWithTag("owner-moderation-open-${f.actionId}").assertHeightIsAtLeast(48.dp); capture("moderation-status")
        ui.onNodeWithTag("owner-moderation-open-${f.actionId}").performClick()
        ui.onNodeWithTag("itinerary-title").assertExists(); back(); readyOwner()
        assertEquals(f.base.inboxId, f.base.lastAcknowledged); assertEquals(1, f.base.ackCalls.get()); assertEquals(0, f.unexpectedWrites.get())
        back(); ui.onNodeWithTag("notification-inbox").assertExists()
    }
    @Test fun appealInboxDisplaysItsAuthoritativeStateAndCheckIsReadOnly() {
        f.notification = f.notice.copy(kind = "itinerary.appeal_decided")
        f.actionsResult = CatalogResult.Ready(listOf(f.action.copy(appeal = TravelerAppeal("appeal", f.actionId, AppealStatus.UNDER_REVIEW, "2026-11-08T00:00:00Z", null))))
        launch(); openNotification(); readyOwner(); ui.onNodeWithText("Banding sedang ditinjau").assertExists(); capture("appeal-status")
        ui.onNodeWithTag("owner-moderation-check-${f.actionId}").performClick(); readyOwner()
        assertEquals(1, f.base.ackCalls.get()); assertEquals(0, f.unexpectedWrites.get())
    }
    @Test fun loadingAndFailedOwnerReadsDoNotAckAndRetryWaitsForVisibleStatus() {
        val gate = CompletableDeferred<Unit>(); f.beforeActions = { gate.await() }
        launch(); openNotification(); ui.waitUntil(5_000) { f.reads.contains("owner-actions") }
        assertEquals(0, f.base.ackCalls.get()); capture("moderation-loading")
        f.actionsResult = CatalogResult.NetworkError; gate.complete(Unit)
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Coba lagi").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, f.base.ackCalls.get()); capture("moderation-error")
        f.actionsResult = CatalogResult.Ready(listOf(f.action)); ui.onNodeWithText("Coba lagi").performClick(); readyOwner()
    }
    @Test fun deniedOwnerProjectionDoesNotAcknowledgeOrExposeStatus() {
        f.actionsResult = CatalogResult.HttpError(403); launch(); openNotification()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Kembali ke notifikasi").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("owner-moderation-status-${f.actionId}").assertDoesNotExist(); capture("moderation-denied")
        assertEquals(0, f.base.ackCalls.get()); ui.onNodeWithText("Kembali ke notifikasi").performClick()
        ui.onNodeWithTag("notification-inbox").assertExists(); assertEquals(0, f.unexpectedWrites.get())
    }
    @Test fun nullAndUnsafeTargetsNeverOpenTheOwnerProjectionOrAcknowledge() {
        f.notification = f.notice.copy(targetUrl = null); launch(); openNotification()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Detail notifikasi").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(f.reads.contains("owner-actions")); assertEquals(0, f.base.ackCalls.get()); back()
        f.notification = f.notice.copy(targetUrl = "https://fixture.invalid/me/itinerary-moderation")
        ui.onNodeWithTag("notification-card-${f.base.inboxId}").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Notifikasi tidak tersedia").fetchSemanticsNodes().isNotEmpty() }
        capture("unsafe-target"); assertFalse(f.reads.contains("owner-actions")); assertEquals(0, f.base.ackCalls.get())
    }
    @Test fun actorChangeDuringLoadingDoesNotRestoreTheOwnerPageOrAcknowledge() {
        val gate = CompletableDeferred<Unit>(); f.beforeActions = { gate.await() }; launch(); openNotification()
        ui.waitUntil(5_000) { f.reads.contains("owner-actions") }
        ui.runOnIdle { f.base.identityState.value = TravelerIdentityView(generation = 2) }; gate.complete(Unit)
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Selamat datang").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("owner-moderation").assertDoesNotExist(); assertEquals(0, f.base.ackCalls.get())
    }
    @Test fun englishStatusAt130And200PercentKeepsThePinnedToolbarAndReadTargets() {
        f.notification = f.notice.copy(kind = "itinerary.appeal_decided")
        f.actionsResult = CatalogResult.Ready(listOf(f.action.copy(appeal = TravelerAppeal("appeal", f.actionId, AppealStatus.UPHELD, "2026-11-08T00:00:00Z", null))))
        launch("en"); openNotification(); readyOwner(); ui.runOnUiThread { scale.floatValue = 1.3f }; ui.waitForIdle()
        ui.onNodeWithText("Decision upheld").assertExists(); capture("moderation-en-font130")
        ui.runOnUiThread { scale.floatValue = 2f }; ui.waitForIdle()
        val top = ui.onNodeWithTag("traveler-top-bar").getUnclippedBoundsInRoot().top
        ui.onNodeWithTag("owner-moderation-check-${f.actionId}").performScrollTo().assertHeightIsAtLeast(48.dp).performClick(); readyOwner()
        assertEquals(top, ui.onNodeWithTag("traveler-top-bar").getUnclippedBoundsInRoot().top); capture("moderation-en-font200")
        assertEquals(1, f.base.ackCalls.get()); assertEquals(0, f.unexpectedWrites.get())
    }
}
