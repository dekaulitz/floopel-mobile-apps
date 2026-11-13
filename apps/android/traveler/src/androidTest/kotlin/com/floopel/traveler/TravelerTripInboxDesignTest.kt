package com.floopel.traveler

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.testing.TravelerTripInboxFixture
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Production Root/VM/Compose; writes and account changes use only domain fixtures. */
@RunWith(AndroidJUnit4::class)
class TravelerTripInboxDesignTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val originalLocale = Locale.getDefault()
    private var originalFont: Float? = null
    private val files = mutableListOf<File>()
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    @After fun cleanup() { Locale.setDefault(originalLocale); originalFont?.let { shell("settings put system font_scale $it") }; files.forEach { check(it.delete()) } }
    private fun launch(f: TravelerTripInboxFixture = TravelerTripInboxFixture(), scale: Float = 1f, language: String = "id"): TravelerTripInboxFixture {
        if (scale != 1f) {
            originalFont = Settings.System.getFloat(ui.activity.contentResolver, Settings.System.FONT_SCALE, 1f)
            shell("settings put system font_scale $scale"); ui.activityRule.scenario.recreate()
            ui.waitUntil(8_000) { ui.activity.resources.configuration.fontScale == scale }
        }
        Locale.setDefault(Locale.forLanguageTag(language))
        ui.setContent {
            val density = LocalDensity.current.density; val context = LocalContext.current; val configuration = LocalConfiguration.current
            val localizedConfig = remember(configuration, language) { Configuration(configuration).apply { setLocales(LocaleList.forLanguageTags(language)); fontScale = scale } }
            val localized = remember(context, localizedConfig) { context.createConfigurationContext(localizedConfig) }
            CompositionLocalProvider(LocalDensity provides Density(density, scale), LocalContext provides localized, LocalConfiguration provides localizedConfig) {
                TravelerApp(f.runtime, {}, language = language, backHandler = { enabled, back -> BackHandler(enabled, back) })
            }
        }
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("navigation-profile").fetchSemanticsNodes().isNotEmpty() }
        return f
    }
    private fun inbox(f: TravelerTripInboxFixture) {
        ui.onNodeWithTag("navigation-profile").performClick(); ui.onNodeWithText("Undangan trip").performScrollTo().performClick()
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("trip-invitation-${f.invitations.first().id}").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun back() { ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() } }
    private fun moreInvitations() {
        ui.onNodeWithTag("trip-invitation-list").performScrollToNode(hasTestTag("trip-invitations-more"))
        ui.onNodeWithTag("trip-invitations-more").performClick()
    }
    private fun invitation(id: String) {
        ui.onNodeWithTag("trip-invitation-list").performScrollToNode(hasTestTag("trip-invitation-$id"))
        ui.onNodeWithTag("trip-invitation-$id").assertIsDisplayed()
    }
    private fun noDecisions(f: TravelerTripInboxFixture) { assertTrue(f.decisions.isEmpty()); assertTrue(f.invitationDecisions.isEmpty()) }
    private fun pending(f: TravelerTripInboxFixture, existing: Boolean = false, query: String? = null) {
        if (existing) f.publicTrip = f.publicTrip.copy(applicationId = f.application.id, canApply = false)
        ui.onNodeWithTag("navigation-open_trips").performClick()
        if (query != null) {
            val field = ui.onNode(hasSetTextAction() and (hasTestTag("catalog-search") or hasAnyAncestor(hasTestTag("catalog-search"))), true)
            field.performTextReplacement(query); field.performImeAction()
        }
        ui.onNodeWithTag("trip-catalog-action").performScrollTo().performClick()
        if (existing) ui.onNodeWithTag("open-trip-application-status").performScrollTo().performClick()
        else {
            ui.onNodeWithTag("trip-application-open-form").performScrollTo().performClick()
            ui.onNodeWithTag("trip-application-send").performClick()
        }
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("trip-application-pending").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun capture(name: String) {
        val output = File(ui.activity.getExternalFilesDir(null), "trip-inbox-audit/$name.png"); output.parentFile?.mkdirs()
        output.outputStream().use { check(ui.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun banner(): CatalogPhoto {
        val file = File.createTempFile("trip-context-fixture-", ".png", ui.activity.cacheDir); files += file
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888); Canvas(bitmap).drawColor(Color.rgb(67, 123, 83))
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
        return CatalogPhoto(file.toURI().toString(), "Banner simulasi")
    }
    @Test fun recipientCanReachTheOlderPendingInvitationAfterTwentyHistoryRows() {
        val f = launch(); inbox(f); moreInvitations(); invitation(f.invitations.last().id)
        ui.onNodeWithText("Terima undangan").assertExists(); ui.onNodeWithTag("trip-invitations-more").assertDoesNotExist()
        assertEquals(listOf(null, f.invitations[19].id), f.cursors.toList()); noDecisions(f); capture("invitations-page-two")
    }
    @Test fun appendReadFailureKeepsHistoryAndRetriesTheSameCursor() {
        val f = launch(); inbox(f); f.appendFailure = CatalogResult.NetworkError
        moreInvitations(); invitation(f.invitations.first().id)
        ui.onNodeWithTag("trip-invitation-list").performScrollToNode(hasText("Coba lagi"))
        ui.onNodeWithText("Coba lagi").assertIsDisplayed(); capture("invitations-append-error")
        f.appendFailure = null; ui.onNodeWithText("Coba lagi").performClick()
        invitation(f.invitations.last().id)
        assertEquals(listOf(null, f.invitations[19].id, f.invitations[19].id), f.cursors.toList()); noDecisions(f)
    }
    @Test fun exactlyTwentyRowsEndThroughAnEmptyReadWithoutErasingTheRows() {
        val f = TravelerTripInboxFixture(); f.invitations = f.invitations.take(20); launch(f); inbox(f)
        moreInvitations(); invitation(f.invitations.last().id)
        ui.onNodeWithTag("trip-invitations-more").assertDoesNotExist()
        ui.onNodeWithText("Belum ada hasil").assertDoesNotExist(); noDecisions(f)
    }
    @Test fun lateAppendCannotAppearAfterBack() {
        val f = launch(); inbox(f); val gate = CompletableDeferred<Unit>()
        f.beforeInvitations = { withContext(NonCancellable) { gate.await() } }
        moreInvitations(); ui.waitUntil(5_000) { f.cursors.size == 2 }
        back(); gate.complete(Unit); ui.waitForIdle(); ui.onNodeWithTag("trip-invitations-more").assertDoesNotExist(); noDecisions(f)
    }
    @Test fun actorResetDuringAppendClearsThePreviousRecipientRows() {
        val f = launch(); inbox(f); val gate = CompletableDeferred<Unit>()
        f.beforeInvitations = { withContext(NonCancellable) { gate.await() } }
        moreInvitations(); ui.waitUntil(5_000) { f.cursors.size == 2 }
        ui.runOnUiThread { f.base.identityState.value = TravelerIdentityView(generation = 2) }; gate.complete(Unit); ui.waitForIdle()
        ui.onNodeWithTag("trip-invitation-list").assertDoesNotExist(); noDecisions(f)
    }
    @Test fun applyAcknowledgementUsesTheExactPendingHierarchyAndKnownTripContext() {
        val f = TravelerTripInboxFixture(); f.publicTrip = f.publicTrip.copy(banner = banner()); launch(f); pending(f)
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("trip-application-artwork-ready").fetchSemanticsNodes().isNotEmpty() }
        val d = ui.activity.resources.displayMetrics.density
        val viewport = ui.onNodeWithTag("traveler-viewport").fetchSemanticsNode().boundsInRoot
        val context = ui.onNodeWithTag("trip-application-context").fetchSemanticsNode().boundsInRoot
        val art = ui.onNodeWithTag("trip-application-artwork-ready").fetchSemanticsNode().boundsInRoot
        assertEquals(20f*d, context.left-viewport.left, 1f); assertEquals(360f*d, context.width, 1f); assertEquals(88f*d, context.height, 1f)
        assertEquals(160f*d, art.width, 1f); assertEquals(106.667f*d, art.height, 2f); assertEquals(context.center.x, art.center.x, 1f)
        val check = ui.onNodeWithTag("trip-application-check").fetchSemanticsNode().boundsInRoot
        val withdraw = ui.onNodeWithTag("trip-application-withdraw").fetchSemanticsNode().boundsInRoot
        val catalog = ui.onNodeWithTag("trip-application-catalog").fetchSemanticsNode().boundsInRoot
        assertEquals(48f*d, check.height, 1f); assertEquals(check.width, withdraw.width, 1f); assertEquals(check.width, catalog.width, 1f)
        assertEquals(16f*d, withdraw.top-check.bottom, 1f); assertEquals(16f*d, catalog.top-withdraw.bottom, 1f)
        ui.onNodeWithTag("trip-application-banner", true).assertExists(); ui.onNodeWithText("Catatan privat simulasi").assertDoesNotExist()
        assertEquals(1, f.applyCalls.get()); noDecisions(f); capture("pending-application")
    }
    @Test fun checkOnlyReadsAndCancelingWithdrawalDoesNotSubmit() {
        val f = launch(); pending(f, true); val reads = f.applicationReads.get()
        ui.onNodeWithTag("trip-application-check").performScrollTo().performClick(); ui.waitUntil(5_000) { f.applicationReads.get() > reads }
        ui.onNodeWithTag("trip-application-withdraw").performScrollTo().performClick(); ui.onNodeWithText("Batal").performClick()
        ui.onNodeWithTag("trip-application-pending").assertExists(); assertEquals(0, f.applyCalls.get()); noDecisions(f)
    }
    @Test fun serverCapabilityCanRemoveTheWithdrawalAction() {
        val f = TravelerTripInboxFixture(); f.application = f.application.copy(canWithdraw = false); launch(f); pending(f, true)
        ui.onNodeWithTag("trip-application-withdraw").assertDoesNotExist(); ui.onNodeWithTag("trip-application-check").assertIsEnabled(); noDecisions(f)
    }
    @Test fun refreshedMembershipLeavesThePendingVariantAndOffersTheActualTrip() {
        val f = launch(); pending(f, true); f.application = f.application.copy(status = TripApplicationStatus.APPROVED, currentMember = true, canWithdraw = false)
        ui.onNodeWithTag("trip-application-check").performScrollTo().performClick(); ui.waitForIdle()
        ui.onNodeWithTag("trip-application-pending").assertDoesNotExist(); ui.onNodeWithText("Buka Trip").assertExists(); noDecisions(f)
    }
    @Test fun toolbarBackReturnsToThePublicReaderWithoutAnotherApplication() {
        val f = launch(); pending(f); back()
        ui.onNodeWithTag("open-trip-application-status").performScrollTo().assertExists(); assertEquals(1, f.applyCalls.get()); noDecisions(f)
    }
    @Test fun catalogReturnKeepsTheOriginalSearch() {
        val f = launch(); pending(f, query = "Magelang")
        ui.onNodeWithTag("trip-application-catalog").performScrollTo().performClick()
        ui.onNode(hasSetTextAction() and (hasTestTag("catalog-search") or hasAnyAncestor(hasTestTag("catalog-search"))), true).assertTextContains("Magelang")
        assertEquals(1, f.applyCalls.get()); noDecisions(f)
    }
    @Test fun longTitleAt130PercentKeepsTheContextAndActionsInsideTheGutters() {
        val f = TravelerTripInboxFixture(); f.application = f.application.copy(tripName = "Jelajah Magelang bersama menikmati perjalanan budaya dan cerita warga di sepanjang kota")
        f.publicTrip = f.publicTrip.copy(name = f.application.tripName); launch(f, 1.3f); pending(f, true)
        ui.onNodeWithTag("trip-application-catalog").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("trip-application-withdraw").performScrollTo().assertIsDisplayed(); capture("pending-font130-long-title"); noDecisions(f)
    }
    @Test fun actualEnglishFont200KeepsCheckWithdrawalAndCatalogReachable() {
        val f = launch(scale = 2f, language = "en"); pending(f, true)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        ui.onNodeWithText("Waiting for a response").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(2f, layouts.single().layoutInput.density.fontScale, 0f)
        for (tag in listOf("trip-application-check", "trip-application-withdraw", "trip-application-catalog")) ui.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
        capture("pending-en-font200"); noDecisions(f)
    }
    @Test fun lateStatusAfterActorChangeCannotRestoreTheApplicantReader() {
        val f = launch(); pending(f, true); val gate = CompletableDeferred<Unit>(); val reads = f.applicationReads.get()
        f.beforeApplication = { withContext(NonCancellable) { gate.await() } }
        ui.onNodeWithTag("trip-application-check").performScrollTo().performClick(); ui.waitUntil(5_000) { f.applicationReads.get() > reads }
        ui.runOnUiThread { f.base.identityState.value = TravelerIdentityView(generation = 2) }; gate.complete(Unit); ui.waitForIdle()
        ui.onNodeWithTag("trip-application-pending").assertDoesNotExist(); noDecisions(f)
    }
    @Test fun organizerPendingProjectionKeepsItsOwnDecisionReader() {
        val f = TravelerTripInboxFixture(); f.application = f.application.copy(viewer = TripApplicationViewer.ORGANIZER, canApprove = true, canWithdraw = false)
        launch(f)
        f.publicTrip = f.publicTrip.copy(applicationId = f.application.id, canApply = false)
        ui.onNodeWithTag("navigation-open_trips").performClick(); ui.onNodeWithTag("trip-catalog-action").performScrollTo().performClick()
        ui.onNodeWithTag("open-trip-application-status").performScrollTo().performClick()
        ui.onNodeWithTag("trip-application-pending").assertDoesNotExist(); ui.onNodeWithText("Setujui").assertExists(); noDecisions(f)
    }
}
