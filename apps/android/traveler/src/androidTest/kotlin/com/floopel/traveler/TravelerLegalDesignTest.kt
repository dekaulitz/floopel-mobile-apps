package com.floopel.traveler

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.testing.TravelerFigmaFixture
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Exact-version synthetic documents only; never records a real legal consent. */
@RunWith(AndroidJUnit4::class)
class TravelerLegalDesignTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val terms = LegalDocument("terms", "2.0", "id", "<h2>Syarat dan Ketentuan simulasi</h2><p>Isi dokumen dari server simulasi.</p><h2>1. Tentang aplikasi</h2><p>Paragraf untuk pemeriksaan tampilan.</p>", "served-terms-hash")
    private val privacy = terms.copy(type = "privacy", hash = "served-privacy-hash", contentHtml = "<h1>Kebijakan Privasi simulasi</h1><p>Isi privasi dari server simulasi.</p>")
    @Volatile private var pending: List<LegalDocument> = emptyList()
    @Volatile private var readFailure = false
    private var beforeRead: suspend () -> Unit = {}
    private var beforeWrite: suspend () -> Unit = {}
    private val writes = AtomicInteger()
    private val repository = object : TravelerLegalRepository, TravelerLegalDocumentRepository {
        override suspend fun pending(locale: String): CatalogResult<List<LegalDocument>> {
            beforeRead(); return if (readFailure) CatalogResult.NetworkError else CatalogResult.Ready(pending)
        }
        override suspend fun current(type: String, locale: String) = CatalogResult.Ready(if (type == "terms") terms else privacy)
        override suspend fun accept(document: LegalDocument, key: String, identityGeneration: Long): CatalogResult<Unit> {
            writes.incrementAndGet(); beforeWrite(); pending = pending - document; return CatalogResult.Ready(Unit)
        }
    }
    private fun launch() {
        val r = TravelerFigmaFixture().runtime
        val runtime = TravelerRuntime(r.identity, r.catalog, r.itineraries, r.distribution, r.trips, r.notifications,
            r.account, repository, r.registration, r.publicContent, r.savedSpots, r.questGroups, r.newOperationKey,
            r.startup, comments = r.comments, settings = r.settings, legalDocuments = repository)
        ui.runOnUiThread {
            WindowCompat.enableEdgeToEdge(ui.activity.window)
            WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView).run {
                isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
            }
        }
        ui.setContent { TravelerApp(runtime, {}, backHandler = { enabled, back -> BackHandler(enabled, back) }) }
        ui.waitUntil(8_000) { ui.onAllNodesWithContentDescription("Notifikasi").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("navigation-profile").performClick(); ui.onNodeWithContentDescription("Pengaturan").performClick()
        ui.onNodeWithText("Persetujuan dokumen").performScrollTo().performClick()
    }
    private fun awaitDocuments() { ui.waitUntil(5_000) { ui.onAllNodesWithText("Syarat & Ketentuan").fetchSemanticsNodes().isNotEmpty() } }
    private fun capture(name: String) {
        ui.waitForIdle()
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("legal-audit"))
        directory.mkdirs()
        directory.resolve("$name.png").outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun settingsKeepsBothCurrentDocumentsReadableWithoutRequestingConsentAgain() {
        launch(); awaitDocuments()
        ui.onNodeWithText("Dokumen akun").assertExists(); ui.onNodeWithText("Kebijakan Privasi").assertExists()
        ui.onNodeWithTag("legal-accept").assertDoesNotExist(); ui.onNodeWithTag("legal-acknowledgement").assertDoesNotExist()
        val density = ui.activity.resources.displayMetrics.density
        val shield = ui.onNodeWithTag("legal-shield").fetchSemanticsNode().boundsInRoot
        assertEquals(20 * density, shield.left, 1f); assertEquals(32 * density, shield.width, 1f)
        capture("legal-current-read-only")
        ui.onNodeWithText("Syarat & Ketentuan").performClick()
        ui.onNodeWithText("Syarat dan Ketentuan simulasi").assertExists(); ui.onNodeWithText("Versi 2.0 · ID").assertExists()
        val titleLayouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        ui.onNodeWithText("Syarat dan Ketentuan simulasi").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        assertEquals(26f, titleLayouts.single().layoutInput.style.fontSize.value, .01f)
        ui.onNodeWithText("Isi dokumen dari server simulasi.").assertExists(); capture("legal-terms-reader")
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithText("Kebijakan Privasi").performClick(); ui.onNodeWithText("Isi privasi dari server simulasi.").assertExists()
        capture("legal-privacy-reader"); assertEquals(0, writes.get())
    }
    @Test fun pendingDocumentsUseTheFigmaListAndRequireExplicitConsentBeforeSaving() {
        pending = listOf(terms, privacy); launch(); awaitDocuments()
        ui.onNodeWithText("Tinjau pembaruan dokumen").assertExists()
        ui.onNodeWithTag("legal-accept").assertIsNotEnabled(); capture("legal-pending-unchecked")
        ui.onNodeWithTag("legal-acknowledgement").performScrollTo().performClick()
        ui.onNodeWithTag("legal-accept").assertIsEnabled(); capture("legal-pending-checked")
        val gate = CompletableDeferred<Unit>(); beforeWrite = { gate.await() }
        ui.onNodeWithTag("legal-accept").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Menyimpan persetujuan…").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("legal-accept").assertIsNotEnabled(); capture("legal-saving"); assertEquals(1, writes.get())
        gate.complete(Unit)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("legal-review").fetchSemanticsNodes().isEmpty() }
        assertEquals(2, writes.get()); ui.onNodeWithText("Pengaturan").assertExists()
    }
    @Test fun loadingAndFailedReadsCanRetryWithoutShowingAnEmptyConsentForm() {
        val gate = CompletableDeferred<Unit>(); beforeRead = { gate.await() }; launch()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("legal-loading").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("legal-accept").assertDoesNotExist(); capture("legal-loading")
        readFailure = true; gate.complete(Unit)
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Dokumen belum termuat. Periksa koneksi dan coba lagi.").fetchSemanticsNodes().isNotEmpty() }
        capture("legal-read-failed"); assertEquals(0, writes.get())
        readFailure = false; beforeRead = {}; ui.onNodeWithText("Coba lagi").performClick(); awaitDocuments()
        ui.onNodeWithText("Dokumen akun").assertExists(); assertEquals(0, writes.get())
    }
}
