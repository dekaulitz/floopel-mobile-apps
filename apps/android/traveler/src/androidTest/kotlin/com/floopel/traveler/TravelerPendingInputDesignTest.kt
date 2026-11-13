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
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.testing.TravelerPendingInputFixture
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Production Compose/ViewModel bindings, synthetic identities and repository writes only. */
@RunWith(AndroidJUnit4::class)
class TravelerPendingInputDesignTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val f = TravelerPendingInputFixture()
    private val originalLocale = Locale.getDefault()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    @After fun after() { gates.forEach { it.complete(Unit) }; Locale.setDefault(originalLocale) }
    private fun gate() = CompletableDeferred<Unit>().also(gates::add)
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
    private fun capture(name: String) {
        ui.waitForIdle()
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("pending-input-audit")); directory.mkdirs()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        directory.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun exact(tag: String, value: String) = ui.onNodeWithTag(tag)
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(value)))
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK); ui.waitForIdle() }
    private fun activity() {
        ui.onAllNodesWithText("Quest").onFirst().performClick()
        ui.onNodeWithText("Jejak budaya simulasi").performClick()
        ui.onNodeWithText("Ambil Quest").performScrollTo().performClick()
        ui.onNodeWithText("Pelajari cerita Spot").performScrollTo().performClick()
        ui.onNodeWithTag(if (f.textQuestion) "quest-answer-input" else "quest-answer-option-a").assertExists()
    }
    private fun submit() = ui.onNodeWithTag("quest-answer-submit").performScrollTo().performClick()
    private fun waitAnswer(value: String) { ui.waitUntil(5_000) { f.base.identityState.value.explanation == "Hasil untuk $value" }; ui.waitForIdle() }
    @Test fun radioInputStaysOnSubmittedAWhilePendingAndFeedbackIsHiddenForDraftB() {
        val gate = gate(); f.beforeAnswer = { gate.await() }; launch(); activity()
        ui.onNodeWithTag("quest-answer-option-a").performClick(); submit()
        ui.waitUntil(5_000) { f.base.identityState.value.busy }
        ui.onNodeWithTag("quest-answer-option-b").assertIsNotEnabled().performClick()
        ui.onNodeWithTag("quest-answer-option-a").assertIsSelected(); ui.onNodeWithTag("quest-answer-option-b").assertIsNotSelected()
        ui.onNodeWithTag("quest-answer-submit").assertIsNotEnabled(); assertEquals(listOf("a"), f.answers); capture("radio-pending-a")
        gate.complete(Unit); waitAnswer("a"); ui.onNodeWithText("Hasil untuk a").assertExists(); capture("radio-result-a")
        ui.onNodeWithTag("quest-answer-option-b").performClick(); ui.onNodeWithText("Hasil untuk a").assertDoesNotExist()
        f.answerOutcome = "correct"; f.beforeAnswer = {}; submit(); waitAnswer("b")
        assertEquals(listOf("a", "b"), f.answers); ui.onNodeWithText("Hasil untuk b").assertExists()
    }
    @Test fun textInputStaysOnSubmittedAWhilePendingThenAllowsAnExplicitB() {
        f.textQuestion = true; val gate = gate(); f.beforeAnswer = { gate.await() }; launch(); activity()
        ui.onNodeWithTag("quest-answer-input").performTextReplacement("Jawaban A"); submit()
        ui.waitUntil(5_000) { f.base.identityState.value.busy }; ui.onNodeWithTag("quest-answer-input").assertIsNotEnabled()
        runCatching { ui.onNodeWithTag("quest-answer-input").performTextReplacement("Jawaban B") }
        exact("quest-answer-input", "Jawaban A"); assertEquals(listOf("Jawaban A"), f.answers); capture("text-pending-a")
        gate.complete(Unit); waitAnswer("Jawaban A")
        ui.onNodeWithTag("quest-answer-input").performTextReplacement("Jawaban B"); ui.onNodeWithText("Hasil untuk Jawaban A").assertDoesNotExist()
        f.beforeAnswer = {}; f.answerOutcome = "correct"; submit(); waitAnswer("Jawaban B")
        exact("quest-answer-input", "Jawaban B"); assertEquals(listOf("Jawaban A", "Jawaban B"), f.answers); capture("text-result-b")
    }
    @Test fun lastActivityCompletionAppearsOnlyAfterTheCorrectAcknowledgement() {
        f.answerOutcome = "correct"; f.completeOnCorrect = true; val gate = gate(); f.beforeAnswer = { gate.await() }; launch(); activity()
        ui.onNodeWithTag("quest-answer-option-a").performClick(); submit(); ui.waitUntil(5_000) { f.base.identityState.value.busy }
        ui.onNodeWithText("Progres telah dikonfirmasi. Hadiah mengikuti hasil verifikasi server.").assertDoesNotExist()
        gate.complete(Unit); waitAnswer("a"); ui.onNodeWithText("Jawaban diterima").assertExists()
        ui.onNodeWithText("Progres telah dikonfirmasi. Hadiah mengikuti hasil verifikasi server.").assertExists()
        assertEquals(1, f.answers.size); capture("last-activity-acknowledged")
    }
    @Test fun backWhilePendingDoesNotReplayAndRevisitingKeepsAnswerAttribution() {
        val gate = gate(); f.beforeAnswer = { gate.await() }; launch(); activity()
        ui.onNodeWithTag("quest-answer-option-a").performClick(); submit(); ui.waitUntil(5_000) { f.base.identityState.value.busy }
        back(); ui.onNodeWithTag("quest-answer-submit").assertDoesNotExist(); gate.complete(Unit); waitAnswer("a")
        ui.onNodeWithText("Pelajari cerita Spot").performScrollTo().performClick()
        ui.onNodeWithTag("quest-answer-option-a").assertIsSelected(); ui.onNodeWithText("Hasil untuk a").assertExists()
        assertEquals(1, f.answers.size); capture("answer-back-revisit")
    }
    @Test fun actorChangeDiscardsTheLateAnswerAndItsSelectedInput() {
        val gate = gate(); f.beforeAnswer = { withContext(NonCancellable) { gate.await() } }; launch(); activity()
        ui.onNodeWithTag("quest-answer-option-a").performClick(); submit(); ui.waitUntil(5_000) { f.base.identityState.value.busy }
        ui.runOnIdle { f.base.identityState.value = f.base.identityState.value.copy(generation = 2, busy = false, question = null, submittedAnswer = null, answerOutcome = null, explanation = null) }
        ui.onNodeWithTag("quest-activity-retry").performClick(); gate.complete(Unit); ui.waitForIdle()
        ui.onNodeWithTag("quest-answer-option-a").assertIsNotSelected(); ui.onNodeWithText("Hasil untuk a").assertDoesNotExist()
        assertEquals(1, f.answers.size); capture("answer-actor-reset")
    }
    private fun dayEditor(value: String) {
        ui.onNodeWithTag("navigation-profile").performClick()
        ui.onNodeWithText("Itinerary saya").performScrollTo().performClick(); ui.onNodeWithText("Rencana budaya simulasi").performClick()
        ui.onAllNodesWithText("Ubah").onFirst().performScrollTo().performClick()
        ui.onNodeWithTag("day-title-input").performTextReplacement(value)
    }
    private fun saveDay() = ui.onNodeWithTag("day-title-save").performClick()
    private fun failureVisible() {
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Coba lagi").fetchSemanticsNodes().isNotEmpty() }; ui.waitForIdle()
    }
    private fun saved(value: String) {
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("day-title-editor").fetchSemanticsNodes().isEmpty() }
        ui.waitUntil(5_000) { ui.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(value).assertExists()
    }
    @Test fun failedLegalPreflightKeepsTheDraftAndReadyRetryWritesOnce() {
        launch(); dayEditor("Hari baru"); f.legalResult = CatalogResult.NetworkError; saveDay(); failureVisible()
        ui.onAllNodesWithText("Coba lagi").onLast().assertExists(); exact("day-title-input", "Hari baru"); ui.onNodeWithTag("day-title-input").assertIsEnabled()
        assertTrue(f.titleWrites.isEmpty()); capture("day-preflight-network")
        f.legalResult = CatalogResult.Ready(emptyList()); saveDay(); saved("Hari baru")
        assertEquals(listOf(TravelerPendingInputFixture.TitleWrite(f.base.planId, 1, "Hari baru")), f.titleWrites)
    }
    @Test fun preflight503AndExplicitCancelDoNotWriteOrReuseTheDiscardedDraft() {
        launch(); dayEditor("Draft dibatalkan"); f.legalResult = CatalogResult.HttpError(503); saveDay(); failureVisible()
        exact("day-title-input", "Draft dibatalkan"); capture("day-preflight-503")
        ui.onNodeWithTag("day-title-cancel").performClick(); ui.onAllNodesWithText("Ubah").onFirst().performScrollTo().performClick()
        exact("day-title-input", "Hari awal"); assertTrue(f.titleWrites.isEmpty())
    }
    @Test fun legalReviewBackRetainsTheDraftUntilAnExplicitSave() {
        launch(); dayEditor("Hari setelah tinjauan")
        f.legalResult = CatalogResult.Ready(listOf(LegalDocument("terms", "fixture", "id", "<p>Dokumen fixture</p>", "fixture-hash")))
        saveDay(); ui.waitUntil(5_000) { ui.onAllNodesWithTag("day-title-editor").fetchSemanticsNodes().isEmpty() }
        f.legalResult = CatalogResult.Ready(emptyList()); back()
        if (ui.onAllNodesWithTag("day-title-editor").fetchSemanticsNodes().isEmpty()) back()
        exact("day-title-input", "Hari setelah tinjauan"); assertTrue(f.titleWrites.isEmpty()); capture("day-legal-back")
        saveDay(); saved("Hari setelah tinjauan"); assertEquals(1, f.titleWrites.size)
    }
    @Test fun validationResponseKeepsAnEditableDraftForExplicitCorrection() {
        launch(); dayEditor("Judul validasi"); f.titleResult = CatalogResult.HttpError(422); saveDay(); failureVisible()
        exact("day-title-input", "Judul validasi"); ui.onNodeWithTag("day-title-input").assertIsEnabled(); capture("day-validation")
        assertEquals(1, f.titleWrites.size); ui.onNodeWithTag("day-title-input").performTextReplacement("Judul diperbaiki")
        f.titleResult = null; saveDay(); saved("Judul diperbaiki"); assertEquals(2, f.titleWrites.size)
    }
    @Test fun unknownWriteKeepsTheDraftAndShowsUncertaintyWithoutAutomaticReplay() {
        launch(); dayEditor("Judul belum pasti"); f.titleResult = CatalogResult.NetworkError; saveDay(); failureVisible()
        exact("day-title-input", "Judul belum pasti")
        ui.onAllNodesWithText("Hasil penyimpanan belum dapat dipastikan. Periksa informasi terbaru sebelum mengirim ulang.").onLast().assertExists()
        assertEquals(1, f.titleWrites.size); capture("day-unknown")
        ui.onNodeWithTag("day-title-cancel").performClick(); assertEquals(1, f.titleWrites.size)
    }
    @Test fun retryAfterKeepsInputEditableAndRequiresAnExplicitSaveAfterItsDeadline() {
        launch(); dayEditor("Judul dibatasi"); f.titleResult = CatalogResult.HttpError(429, retryAfterSeconds = 1); saveDay(); failureVisible()
        exact("day-title-input", "Judul dibatasi"); ui.onNodeWithTag("day-title-input").assertIsEnabled()
        ui.onNodeWithTag("day-title-save").assertIsNotEnabled(); assertEquals(1, f.titleWrites.size); capture("day-throttled")
        f.titleResult = null
        ui.waitUntil(5_000) { ui.onAllNodes(hasTestTag("day-title-save") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, f.titleWrites.size); saveDay(); saved("Judul dibatasi"); assertEquals(2, f.titleWrites.size)
    }
}
