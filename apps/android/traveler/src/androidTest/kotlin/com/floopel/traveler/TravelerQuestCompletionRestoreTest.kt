package com.floopel.traveler

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerIdentity
import com.floopel.feature.traveler.testing.TravelerFixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Root's real rememberSaveable producer/consumer and a fresh VM/runtime; domain doubles only.
 * Production Session/HTTP behavior is covered separately in the host QuestCompletionRestoreTest.
 * This is not MainActivity process death or live Quest/reward verification. */
@RunWith(AndroidJUnit4::class)
class TravelerQuestCompletionRestoreTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val instances = CopyOnWriteArrayList<CompletionFixture>()
    private lateinit var restoration: StateRestorationTester
    private val c = "00000000-0000-4000-8000-000000002001"
    private var restoredOwnerStatus = 200
    private var restoredPassportFails = false

    private fun launchAtPassport() {
        ui.runOnUiThread { WindowCompat.enableEdgeToEdge(ui.activity.window) }
        restoration = StateRestorationTester(ui)
        restoration.setContent {
            val fresh = remember { instances.isNotEmpty() }
            val fixture = remember { CompletionFixture(if (fresh) restoredOwnerStatus else 200,
                fresh && restoredPassportFails).also(instances::add) }
            val owner = remember { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
            DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                TravelerApp(fixture.runtime, {}, backHandler = { enabled, back -> BackHandler(enabled, back) })
            }
        }
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("navigation-profile").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("Quest").onFirst().performClick()
        ui.onNodeWithText("Jejak budaya simulasi").performScrollTo().performClick()
        ui.onNodeWithTag("quest-take").performClick()
        completed()
        ui.onNodeWithText("Passport").performScrollTo().performClick()
        ui.onNodeWithTag("passport-screen").assertIsDisplayed()
        ui.waitForIdle()
    }
    private fun restoreFresh() {
        restoration.emulateSavedInstanceStateRestore()
        ui.waitUntil(8_000) { instances.size == 2 && instances.last().restores.get() == 1 && instances.last().passportReads.get() > 0 }
        ui.waitForIdle()
        ui.onNodeWithTag("passport-screen").assertIsDisplayed()
        val f = instances.last()
        assertNull(f.base.identityState.value.running)
        assertFalse(f.base.identityState.value.completed)
        assertTrue(f.base.identityState.value.passport?.running.orEmpty().isEmpty())
        assertTrue(f.ownerReads.isEmpty())
        assertEquals(0, f.businessWrites.get())
    }
    private fun back() { ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }; ui.waitForIdle() }
    private fun completed() {
        ui.waitUntil(8_000) { ui.onAllNodesWithText("Quest selesai").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Quest selesai").assertIsDisplayed()
        ui.onNodeWithText("Completed owner fixture").assertExists()
        ui.onNodeWithText("2 dari 2 aktivitas selesai").assertExists()
        ui.onNode(hasText("Passport") and hasClickAction()).assertExists()
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val folder = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("quest-restore-audit"))
        folder.mkdirs()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        folder.resolve("$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun rootRestoresPassportWithFreshRuntimeAndBackReopensTheCompletedIntent() {
        launchAtPassport(); restoreFresh(); capture("fresh-passport")
        back(); completed(); capture("restored-completed")
        val f = instances.last()
        assertEquals(listOf(c), f.ownerReads); assertEquals(0, f.businessWrites.get())
        assertEquals(1, instances.first().businessWrites.get()) // Synthetic setup; no real admission/answer/claim.
    }
    @Test fun sameSessionControlRetainsTheCompletedTargetOnPassportBack() {
        launchAtPassport()
        val f = instances.single(); val reads = f.ownerReads.size
        back(); completed()
        assertEquals(reads + 1, f.ownerReads.size); assertEquals(c, f.ownerReads.last())
        assertEquals(1, f.businessWrites.get())
    }
    @Test fun failedRestoredPassportDoesNotEraseTheBackIntent() {
        restoredPassportFails = true
        launchAtPassport(); restoreFresh()
        assertEquals(500, instances.last().base.identityState.value.failure?.status)
        back(); completed()
        assertEquals(listOf(c), instances.last().ownerReads); assertEquals(0, instances.last().businessWrites.get())
    }
    @Test fun deniedRestoredOwnerShowsFailureWithoutACompletedPassportAction() {
        restoredOwnerStatus = 404
        launchAtPassport(); restoreFresh(); back()
        ui.waitUntil(8_000) { instances.last().base.identityState.value.failure?.status == 404 }
        ui.onNodeWithText("Quest selesai").assertDoesNotExist()
        ui.onNode(hasText("Passport") and hasClickAction()).assertDoesNotExist()
        assertNull(instances.last().base.identityState.value.running)
        assertEquals(listOf(c), instances.last().ownerReads); assertEquals(0, instances.last().businessWrites.get())
        capture("restored-owner-denied")
    }

    private inner class CompletionFixture(private val ownerStatus: Int, private val failPassport: Boolean) {
        val base = TravelerFixture()
        val restores = AtomicInteger()
        val passportReads = AtomicInteger()
        val ownerReads = CopyOnWriteArrayList<String>()
        val businessWrites = AtomicInteger()
        private val progress = TravelerPassport(0, 1, "Explorer", emptyList(), emptyList(), completions = listOf(
            TravelerPassportCompletion("receipt-fixture", "Completed owner fixture", "fixture", false, "awarded")))
        private fun loadCompleted() {
            base.identityState.value = base.identityState.value.copy(
                running = QuestRunning(c, "Completed owner fixture", "completed", 2, 2, mapOf("a" to "completed", "b" to "completed")),
                ownerStatus = "completed", completed = true, failure = null, busy = false)
        }
        private val identity = object : TravelerIdentity by base.identity {
            override suspend fun restore() { restores.incrementAndGet() }
            override suspend fun passport() {
                passportReads.incrementAndGet()
                base.identityState.value = base.identityState.value.copy(passport = progress,
                    failure = if (failPassport) IdentityFailure(500, null, null) else null)
            }
            override suspend fun takeQuest() { businessWrites.incrementAndGet(); loadCompleted() }
            override suspend fun continueQuest(id: String) {
                ownerReads += id
                if (id == c && ownerStatus == 200) loadCompleted() else base.identityState.value = base.identityState.value.copy(
                    running = null, completed = false, ownerStatus = null, failure = IdentityFailure(ownerStatus, null, null))
            }
            override suspend fun answer(value: String) { businessWrites.incrementAndGet(); error("No answer may be replayed") }
            override suspend fun submitGps() { businessWrites.incrementAndGet(); error("No GPS may be replayed") }
        }
        val runtime = base.runtime.let { r -> TravelerRuntime(identity, r.catalog, r.itineraries, r.distribution, r.trips,
            r.notifications, r.account, r.legal, r.registration, r.publicContent, r.savedSpots, r.questGroups,
            r.newOperationKey, r.startup) }
    }
}
