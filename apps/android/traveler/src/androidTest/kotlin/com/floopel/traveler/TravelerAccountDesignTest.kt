package com.floopel.traveler

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerErasureRepository
import com.floopel.feature.traveler.testing.TravelerFigmaFixture
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic accounts only. These tests never contact a backend or delete a real account. */
@RunWith(AndroidJUnit4::class)
class TravelerAccountDesignTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val writes = AtomicInteger()
    private val reads = AtomicInteger()
    private var authenticateResult: CatalogResult<Unit> = CatalogResult.Ready(Unit)
    private var mutationResult: CatalogResult<AccountErasureState>? = null
    private var beforeAuth: suspend () -> Unit = {}
    private var beforeMutation: suspend () -> Unit = {}
    private var status = AccountErasureState("active", null, false, true, null)
    private val erasure = object : TravelerErasureRepository {
        override suspend fun authenticate(email: String, password: String): CatalogResult<Unit> { beforeAuth(); return authenticateResult }
        override suspend fun state(): CatalogResult<AccountErasureState> { reads.incrementAndGet(); return CatalogResult.Ready(status) }
        override suspend fun request(key: String): CatalogResult<AccountErasureState> {
            writes.incrementAndGet(); beforeMutation(); mutationResult?.let { return it }
            status = AccountErasureState("pending", null, true, false, null); return CatalogResult.Ready(status)
        }
        override suspend fun cancel(key: String): CatalogResult<AccountErasureState> {
            writes.incrementAndGet(); beforeMutation(); mutationResult?.let { return it }
            status = AccountErasureState("active", null, false, true, null); return CatalogResult.Ready(status)
        }
        override fun clear() = Unit
    }
    private var selected = "id"
    private fun launch(scale: Float = 1f, f: TravelerFigmaFixture = TravelerFigmaFixture(), recovery: String? = null, deadline: String? = null) {
        if (recovery != null) {
            status = AccountErasureState(recovery, deadline, recovery == "pending", false, null)
            f.base.identityState.value = f.base.identityState.value.copy(signedIn = false, accountRecoveryRequired = true)
        }
        val r = f.runtime
        val runtime = TravelerRuntime(r.identity, r.catalog, r.itineraries, r.distribution, r.trips, r.notifications, r.account,
            r.legal, r.registration, r.publicContent, r.savedSpots, r.questGroups, r.newOperationKey, r.startup,
            comments = r.comments, settings = r.settings, erasure = erasure)
        ui.runOnUiThread {
            WindowCompat.enableEdgeToEdge(ui.activity.window)
            WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView).run {
                isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
            }
        }
        ui.setContent {
            var language by remember { mutableStateOf("id") }
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                TravelerApp(runtime, {}, language = language, selectLanguage = { selected = it; language = it }, backHandler = { enabled, back -> BackHandler(enabled, back) })
            }
        }
        try { ui.waitUntil(8_000) { if (recovery == null) ui.onAllNodesWithContentDescription("Notifikasi").fetchSemanticsNodes().isNotEmpty()
            else ui.onAllNodesWithTag("account-erasure").fetchSemanticsNodes().isNotEmpty() } }
        catch (error: AssertionError) { capture("launch-failure"); ui.onRoot().printToLog("AccountDesign"); throw error }
    }
    private fun profile() { ui.onNodeWithTag("navigation-profile").performClick() }
    private fun settings() { profile(); ui.onNodeWithContentDescription("Pengaturan").performClick() }
    private fun back() { ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() } }
    private fun authenticate() {
        ui.onNodeWithTag("erasure-email").performTextInput("fixture@example.invalid")
        ui.onNodeWithTag("erasure-password").performTextInput("fixture-password")
        ui.onNodeWithTag("erasure-password").performImeAction()
        ui.waitUntil(5_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(ui.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true
        }
        ui.onNodeWithTag("erasure-verify").performScrollTo().assertIsEnabled().assertIsDisplayed().performClick()
    }
    private fun verify() {
        authenticate()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("erasure-acknowledgement").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        if (ui.onAllNodesWithTag("erasure-artwork-loading").fetchSemanticsNodes().isNotEmpty()) {
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("erasure-artwork-ready").fetchSemanticsNodes().isNotEmpty() }
        }
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("account-audit"))
        directory.mkdirs()
        val node = if (name == "language-id") ui.onNodeWithTag("language-dialog") else ui.onRoot()
        val bitmap = node.captureToImage().asAndroidBitmap()
        directory.resolve("$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun restrictedPendingRecoveryRequiresReauthenticationAndShowsCancellationWithoutNormalContent() {
        launch(recovery = "pending")
        ui.onNodeWithText("Batalkan penghapusan").performScrollTo().performClick()
        ui.onNodeWithTag("erasure-email").assertExists(); assertEquals(0, writes.get())
        ui.onNodeWithText("Verifikasi untuk membatalkan").assertExists(); capture("erasure-cancel-verification")
        val verifyGate = CompletableDeferred<Unit>(); beforeAuth = { verifyGate.await() }
        authenticate()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Memverifikasi…").fetchSemanticsNodes().isNotEmpty() }
        capture("erasure-cancel-verifying"); assertEquals(0, writes.get()); verifyGate.complete(Unit)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("erasure-cancel").fetchSemanticsNodes().isNotEmpty() }
        val cancelGate = CompletableDeferred<Unit>(); beforeMutation = { cancelGate.await() }
        ui.onNodeWithTag("erasure-cancel").performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Membatalkan…").fetchSemanticsNodes().isNotEmpty() }
        capture("erasure-cancelling"); cancelGate.complete(Unit)
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Masuk kembali").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("erasure-artwork-ready").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist(); capture("erasure-cancelled")
        assertEquals(1, writes.get()); ui.onNodeWithText("Masuk kembali").performClick()
        ui.onNodeWithTag("account-erasure").assertDoesNotExist(); ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
    }
    @Test fun restrictedErasingRecoveryUsesTheFigmaArtworkAndNeverOffersCancellation() {
        launch(recovery = "erasing")
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Penghapusan sedang berjalan").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("erasure-artwork-ready").fetchSemanticsNodes().isNotEmpty() }
        val density = ui.activity.resources.displayMetrics.density
        val artwork = ui.onNodeWithTag("erasure-artwork-ready").fetchSemanticsNode().boundsInRoot
        assertEquals(190 * density, artwork.width, 1f); assertEquals(140 * density, artwork.height, 1f)
        ui.onNodeWithText("Batalkan penghapusan").assertDoesNotExist(); ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
        capture("erasure-running"); assertEquals(0, writes.get())
    }
    @Test fun profilePublicProfileAndItineraryKeepTheSamePinnedToolbarPosition() {
        launch(); profile()
        val personal = ui.onNodeWithTag("traveler-top-bar").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithText("Pengaturan").performScrollTo()
        assertEquals(personal, ui.onNodeWithTag("traveler-top-bar").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithText("Profil publik").performScrollTo().performClick()
        val public = ui.onNodeWithTag("traveler-top-bar").fetchSemanticsNode().boundsInRoot
        assertEquals(personal.top, public.top, 1f); assertEquals(personal.height, public.height, 1f)
        ui.onNodeWithText("Pribadi").performClick(); ui.onNodeWithText("Lihat itinerary").performScrollTo().performClick()
        val plan = ui.onNodeWithTag("traveler-top-bar").fetchSemanticsNode().boundsInRoot
        assertEquals(public.top, plan.top, 1f); assertEquals(public.height, plan.height, 1f)
        capture("itinerary-aligned-toolbar")
        back(); assertEquals(public.top, ui.onNodeWithTag("traveler-top-bar").fetchSemanticsNode().boundsInRoot.top, 1f)
    }
    @Test fun languageAndErasureMenusOpenRealFlowsWithoutSubmittingAnything() {
        launch(); settings(); ui.onNodeWithText("Bahasa").performScrollTo().performClick()
        ui.onNodeWithTag("language-dialog").assertExists(); capture("language-id")
        ui.onNodeWithText("English").performClick(); ui.runOnIdle { assertEquals("en", selected) }
        ui.onNode(hasText("English") and isSelected()).assertExists()
        ui.onNodeWithText("Bahasa Indonesia").performClick(); ui.onNodeWithText("Tutup").performClick()
        ui.onNodeWithText("Penghapusan akun").performScrollTo().performClick()
        ui.onNodeWithTag("account-erasure").assertExists(); ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
        capture("erasure-verification-empty"); assertEquals(0, writes.get())
        back(); ui.onNodeWithText("Pengaturan").assertExists(); assertEquals(0, writes.get())
    }
    @Test fun erasureReviewRequiresConsentAndAcceptedRequestClearsTheNormalContentTree() {
        launch(); settings(); ui.onNodeWithText("Penghapusan akun").performScrollTo().performClick(); verify()
        ui.onNodeWithTag("erasure-request").performScrollTo().assertIsNotEnabled(); capture("erasure-review-unchecked")
        assertEquals(0, writes.get()); ui.onNodeWithTag("erasure-acknowledgement").performScrollTo().performClick()
        ui.onNodeWithTag("erasure-request").assertIsEnabled(); capture("erasure-review-checked")
        val requestGate = CompletableDeferred<Unit>(); beforeMutation = { requestGate.await() }
        ui.onNodeWithTag("erasure-request").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Mengajukan…").fetchSemanticsNodes().isNotEmpty() }
        capture("erasure-requesting"); requestGate.complete(Unit)
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Penghapusan sedang menunggu").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, writes.get()); ui.onNodeWithTag("traveler-navigation").assertDoesNotExist(); capture("erasure-pending-no-deadline")
        ui.onNodeWithText("Periksa status").performClick(); ui.onNodeWithTag("erasure-email").assertExists()
        assertEquals(1, writes.get())
    }
    @Test fun twoHundredPercentTextKeepsVerificationAndReviewControlsReachable() {
        launch(2f); settings(); ui.onNodeWithText("Penghapusan akun").performScrollTo().performClick()
        ui.onAllNodesWithText("Lupa password?").onLast().performScrollTo().assertIsDisplayed()
        verify(); ui.onNodeWithTag("erasure-acknowledgement").performScrollTo().performClick()
        ui.onNodeWithTag("erasure-request").performScrollTo().assertIsDisplayed(); capture("erasure-font-200")
        assertEquals(0, writes.get())
    }
    @Test fun aLoadedPhotoUsesTwoHundredDpAndFailedOrAbsentPhotosUseTheUnavailableVariant() {
        val photo = ui.activity.cacheDir.resolve("spot-layout-fixture.png")
        val bitmap = android.graphics.Bitmap.createBitmap(600, 360, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(80, 140, 90))
        photo.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        launch(f = TravelerFigmaFixture(listOf(photo.toURI().toString(), "file:///missing-spot-layout-fixture.png")))
        ui.onAllNodesWithText("Spot simulasi").onFirst().performClick()
        val density = ui.activity.resources.displayMetrics.density
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("spot-gallery-hero").fetchSemanticsNodes().singleOrNull()?.boundsInRoot?.height == 200 * density }
        capture("spot-gallery-loaded")
        ui.onAllNodesWithTag("spot-gallery-thumbnail").onLast().performClick()
        ui.waitUntil(8_000) { ui.onNodeWithTag("spot-gallery-hero").fetchSemanticsNode().boundsInRoot.height == 140 * density }
        ui.onNodeWithText("Foto belum tersedia").assertExists(); capture("spot-gallery-error")
    }
    @Test fun anAbsentPhotoKeepsFourDecorativeSlotsAndNoInteractiveFakePhotos() {
        launch(f = TravelerFigmaFixture(emptyList())); ui.onAllNodesWithText("Spot simulasi").onFirst().performClick()
        ui.onAllNodesWithTag("spot-gallery-thumbnail").assertCountEquals(0)
        ui.onAllNodesWithTag("spot-gallery-placeholder").assertCountEquals(4)
        ui.onNodeWithText("Foto belum tersedia").assertExists(); capture("spot-gallery-absent")
    }
    @Test fun spotUsesTheNewGalleryAndInlineActionsAndOpeningReportDoesNotSubmit() {
        val f = TravelerFigmaFixture(); launch(f = f); ui.onAllNodesWithText("Spot simulasi").onFirst().performClick()
        val density = ui.activity.resources.displayMetrics.density
        val hero = ui.onNodeWithTag("spot-gallery-hero").fetchSemanticsNode().boundsInRoot
        val viewport = ui.onNodeWithTag("traveler-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(140 * density, hero.height, 1f); assertEquals(20 * density, hero.left - viewport.left, 1f)
        assertEquals(20 * density, viewport.right - hero.right, 1f)
        assertEquals(64 * density, ui.onAllNodesWithTag("spot-gallery-thumbnail").onFirst().fetchSemanticsNode().boundsInRoot.width, 1f)
        ui.onNodeWithTag("spot-save").performScrollTo().assertIsDisplayed(); ui.onNodeWithTag("spot-share").assertIsDisplayed()
        ui.onNodeWithTag("spot-save").assert(hasText("Tersimpan")); capture("spot-saved-actions")
        ui.onNodeWithTag("spot-save").performClick()
        ui.waitUntil(5_000) { f.savedWrites.get() == 1 }
        ui.onNodeWithTag("spot-save").assert(hasText("Simpan"))
        capture("spot-new-gallery-actions")
        ui.onNodeWithContentDescription("Opsi konten").performClick(); ui.onNodeWithText("Laporkan Spot").performClick()
        ui.onNodeWithTag("spot-report").assertExists(); ui.onNodeWithText("Informasi tidak tepat").performClick()
        ui.onNodeWithText("Kirim laporan").performScrollTo().assertIsNotEnabled(); back()
        ui.onNodeWithTag("spot-detail-content").assertExists()
    }
    private fun assertArtworkReady() {
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("erasure-artwork-ready").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun blocked(reason: String, heading: String, captureName: String) {
        status = AccountErasureState("active", null, false, false, reason)
        launch(); settings(); ui.onNodeWithText("Penghapusan akun").performScrollTo().performClick(); authenticate()
        ui.waitUntil(5_000) { ui.onAllNodesWithText(heading).fetchSemanticsNodes().isNotEmpty() }
        assertArtworkReady(); ui.onNodeWithTag("erasure-request").assertDoesNotExist()
        ui.onNodeWithTag("erasure-acknowledgement").assertDoesNotExist(); capture(captureName)
        assertEquals(0, writes.get()); back(); ui.onNodeWithText("Pengaturan").assertExists()
    }
    @Test fun activeTripsUseTheFigmaBlockedStateWithoutSubmission() = blocked("ACCOUNT_HAS_ACTIVE_TRIPS", "Pengajuan belum tersedia", "erasure-blocked-trip")
    @Test fun otherActiveProfilesUseTheirOwnFigmaMessageWithoutSubmission() = blocked("ACCOUNT_HAS_OTHER_ACTIVE_PROFILES", "Profil aktif perlu ditangani", "erasure-blocked-profile")
    @Test fun aKnownDeadlineUsesThePendingArtworkAndRequiresCancellationConsent() {
        launch(recovery = "pending", deadline = "2099-01-01T00:00:00Z")
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("erasure-cancel-acknowledgement").fetchSemanticsNodes().isNotEmpty() }
        assertArtworkReady(); ui.onNodeWithTag("erasure-cancel").assertIsNotEnabled(); capture("erasure-pending-known-deadline")
        ui.onNodeWithTag("erasure-cancel-acknowledgement").performClick(); ui.onNodeWithTag("erasure-cancel").assertIsEnabled().performClick()
        ui.onNodeWithText("Verifikasi untuk membatalkan").assertExists(); assertEquals(0, writes.get())
    }
    @Test fun failedVerificationUsesTheFigmaFeedbackAndRetryDoesNotSubmit() {
        authenticateResult = CatalogResult.HttpError(401)
        val gate = CompletableDeferred<Unit>(); beforeAuth = { gate.await() }
        launch(); settings(); ui.onNodeWithText("Penghapusan akun").performScrollTo().performClick(); authenticate()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Memverifikasi…").fetchSemanticsNodes().isNotEmpty() }
        assertArtworkReady(); capture("erasure-verifying"); gate.complete(Unit)
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Identitas belum terverifikasi").fetchSemanticsNodes().isNotEmpty() }
        capture("erasure-verification-failed"); ui.onNodeWithTag("erasure-feedback-action").performClick()
        ui.onNodeWithTag("erasure-email").assertExists(); assertEquals(0, writes.get())
    }
    @Test fun anUnknownRequestCanOnlyReadItsOutcomeAndConfirmedPendingClearsTheNormalContent() {
        launch(); settings(); ui.onNodeWithText("Penghapusan akun").performScrollTo().performClick(); verify()
        mutationResult = CatalogResult.NetworkError
        beforeMutation = { status = AccountErasureState("pending", null, true, false, null) }
        ui.onNodeWithTag("erasure-acknowledgement").performScrollTo().performClick()
        ui.onNodeWithTag("erasure-request").performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Periksa status penghapusan").fetchSemanticsNodes().isNotEmpty() }
        capture("erasure-unknown-request"); assertEquals(1, writes.get())
        ui.onNodeWithTag("erasure-feedback-action").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Penghapusan sedang menunggu").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist(); assertEquals(1, writes.get())
    }
    @Test fun expiredRequestProofRequiresVerificationAndNeverRepeatsTheRequest() {
        launch(); settings(); ui.onNodeWithText("Penghapusan akun").performScrollTo().performClick(); verify()
        mutationResult = CatalogResult.HttpError(401)
        ui.onNodeWithTag("erasure-acknowledgement").performScrollTo().performClick(); ui.onNodeWithTag("erasure-request").performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Verifikasi ulang diperlukan").fetchSemanticsNodes().isNotEmpty() }
        capture("erasure-proof-expired"); ui.onNodeWithTag("erasure-feedback-action").performClick()
        ui.onNodeWithTag("erasure-email").assertExists(); assertEquals(1, writes.get())
    }
    @Test fun cancellationFailureOffersAReadWithoutAnAutomaticSecondCancellation() {
        launch(recovery = "pending"); ui.onNodeWithTag("erasure-cancel").performScrollTo().performClick(); authenticate()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("erasure-cancel").fetchSemanticsNodes().isNotEmpty() }
        mutationResult = CatalogResult.HttpError(409)
        ui.onNodeWithTag("erasure-cancel").performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Pembatalan belum dikonfirmasi").fetchSemanticsNodes().isNotEmpty() }
        capture("erasure-cancel-failed"); val previousReads = reads.get(); ui.onNodeWithTag("erasure-feedback-action").performClick()
        ui.waitUntil(5_000) { reads.get() > previousReads }
        assertEquals(1, writes.get()); ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
    }

}
