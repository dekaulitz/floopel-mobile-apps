package com.floopel.traveler

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerIdentity
import com.floopel.feature.traveler.testing.TravelerFixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** VM/Compose interaction with domain doubles. Production Session/mapper is tested on the host;
 * these fake GPS transitions never invoke coordinates, providers or the live backend. */
@RunWith(AndroidJUnit4::class)
class TravelerQuestStateTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    private class Fixture(private val failFirstActivity: Boolean = false, private val unknownFirstSubmit: Boolean = false) {
        val base = TravelerFixture()
        val reads = AtomicInteger()
        val collects = AtomicInteger()
        val submits = AtomicInteger()
        val answers = AtomicInteger()
        val collectTargets = mutableListOf<String>()
        val submitTargets = mutableListOf<String>()
        private val outcomes = mutableMapOf<String, String>()
        private var proofActivity: String? = null
        private var pendingActivity: String? = null
        private val reader = QuestReader(QuestCard("quest", "quest-simulasi", "Jejak budaya simulasi", 200, null, 2, emptyList(), "personal"),
            "Fixture UI Quest", "Fixture UI", null, null, null, "active", "available",
            listOf(QuestStep("gps-a", "GPS A simulasi", "gps", 1), QuestStep("gps-b", "GPS B simulasi", "gps", 2)), emptyList())
        private fun progress() {
            base.identityState.value = base.identityState.value.copy(
                running = QuestRunning("participation", reader.card.title, "active", outcomes.size, 2,
                    listOf("gps-a", "gps-b").associateWith { if (it in outcomes) "completed" else "available" }),
                ownerStatus = "active", question = null, gpsOutcome = null, gpsReady = false,
                gpsSubmissionPending = false, gpsCollectionBlocked = pendingActivity != null, failure = null)
        }
        val identity = object : TravelerIdentity by base.identity {
            override suspend fun quest(slug: String): CatalogResult<QuestReader> = CatalogResult.Ready(reader.copy(owner = QuestReaderOwner(
                reader.card.id, base.identityState.value.ownerStatus ?: "not_completed", base.identityState.value.running)))
            override suspend fun takeQuest() = progress()
            override suspend fun continueQuest() = progress()
            override suspend fun continueQuest(id: String) = progress()
            override suspend fun activity(id: String) {
                val count = reads.incrementAndGet()
                base.identityState.value = if (failFirstActivity && count == 1)
                    base.identityState.value.copy(question = null, failure = IdentityFailure(503, null, null))
                else base.identityState.value.copy(question = QuestQuestion(id, if (id == "gps-a") "GPS A simulasi" else "GPS B simulasi",
                    QuestActivityKind.GPS, "Petunjuk fixture untuk pengujian UI.", emptyList(), null),
                    failure = null, gpsOutcome = outcomes[id], gpsReady = proofActivity == id && pendingActivity == null,
                    gpsSubmissionPending = pendingActivity == id,
                    gpsCollectionBlocked = pendingActivity != null && pendingActivity != id)
            }
            override suspend fun collectGps() {
                check(pendingActivity == null)
                val id = requireNotNull(base.identityState.value.question).id
                collects.incrementAndGet(); collectTargets += id; proofActivity = id
                base.identityState.value = base.identityState.value.copy(gpsReady = true)
            }
            override suspend fun submitGps() {
                val id = requireNotNull(base.identityState.value.question).id
                check(proofActivity == id)
                val count = submits.incrementAndGet(); submitTargets += id
                if (unknownFirstSubmit && count == 1) {
                    pendingActivity = id
                    base.identityState.value = base.identityState.value.copy(gpsReady = false, gpsSubmissionPending = true,
                        failure = IdentityFailure(503, null, null))
                    return
                }
                outcomes[id] = "verified"
                proofActivity = null; pendingActivity = null
                base.identityState.value = base.identityState.value.copy(gpsOutcome = "verified", gpsReady = false,
                    gpsSubmissionPending = false, gpsCollectionBlocked = false, failure = null)
            }
            override suspend fun answer(value: String) { answers.incrementAndGet() }
        }
        val runtime = base.runtime.let { b -> TravelerRuntime(identity, b.catalog, b.itineraries, b.distribution, b.trips, b.notifications,
            b.account, b.legal, b.registration, b.publicContent, b.savedSpots, b.questGroups, b.newOperationKey, b.startup) }
    }

    private fun openActivity(f: Fixture) {
        ui.setContent { TravelerApp(f.runtime, {}, backHandler = { enabled, back -> BackHandler(enabled, back) }) }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Halo, Traveler").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("Quest").onFirst().performClick()
        ui.onNodeWithText("Jejak budaya simulasi").performClick()
        ui.onNodeWithText("Ambil Quest").performScrollTo().performClick()
        ui.onNodeWithText("GPS A simulasi").performScrollTo().performClick()
        ui.waitUntil(5_000) { f.reads.get() == 1 }
    }

    @Test fun activityBStillOffersCollectionAfterActivityAVerified() {
        val f = Fixture(); openActivity(f)
        ui.onNodeWithTag("quest-gps-collect").performScrollTo().performClick()
        ui.onNodeWithTag("quest-gps-submit").performScrollTo().performClick()
        ui.onNodeWithText("Lokasi berhasil diverifikasi.").assertExists()
        ui.onNodeWithTag("quest-gps-collect").assertDoesNotExist(); capture("quest-gps-a-verified")
        ui.onNodeWithText("Progres Quest").performScrollTo().performClick()
        ui.onNodeWithText("GPS B simulasi").performScrollTo().performClick()
        ui.onNodeWithText("Lokasi berhasil diverifikasi.").assertDoesNotExist()
        ui.onNodeWithTag("quest-gps-collect").performScrollTo().assertIsEnabled(); capture("quest-gps-b-collect")
        ui.onNodeWithTag("quest-gps-collect").performClick()
        ui.onNodeWithTag("quest-gps-submit").performScrollTo().assertIsEnabled()
        assertEquals(2, f.collects.get()); assertEquals(1, f.submits.get()); assertEquals(0, f.answers.get())
    }

    @Test fun localRetryRefetchesTheActivityAndBackReturnsToProgressOnce() {
        val f = Fixture(failFirstActivity = true); openActivity(f)
        ui.onNodeWithTag("quest-activity-retry").performScrollTo().assertIsEnabled(); capture("quest-activity-read-failed")
        ui.onNodeWithTag("quest-activity-retry").performClick()
        ui.waitUntil(5_000) { f.reads.get() == 2 }
        ui.onNodeWithTag("quest-activity-retry").assertDoesNotExist()
        ui.onNodeWithTag("quest-gps-collect").performScrollTo().assertIsEnabled(); capture("quest-activity-read-recovered")
        assertEquals(0, f.collects.get()); assertEquals(0, f.submits.get()); assertEquals(0, f.answers.get())
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithText("GPS B simulasi").assertExists()
        ui.onNodeWithTag("quest-gps-collect").assertDoesNotExist()
        assertEquals(2, f.reads.get())
    }

    @Test fun activeProofFromActivityADoesNotOfferSubmitOnActivityB() {
        val f = Fixture(); openActivity(f)
        ui.onNodeWithTag("quest-gps-collect").performScrollTo().performClick()
        ui.onNodeWithTag("quest-gps-submit").assertIsEnabled()
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithText("GPS B simulasi").performScrollTo().performClick()
        ui.onNodeWithTag("quest-gps-submit").assertDoesNotExist()
        ui.onNodeWithTag("quest-gps-collect").performScrollTo().assertIsEnabled(); capture("quest-gps-b-active-a")
        ui.onNodeWithTag("quest-gps-collect").performClick()
        ui.onNodeWithTag("quest-gps-submit").performScrollTo().performClick()
        ui.onNodeWithText("Lokasi berhasil diverifikasi.").assertExists()
        assertEquals(listOf("gps-a", "gps-b"), f.collectTargets)
        assertEquals(listOf("gps-b"), f.submitTargets)
    }

    @Test fun unknownSubmitStaysWithActivityAAndBlocksNewCollectionOnB() {
        val f = Fixture(unknownFirstSubmit = true); openActivity(f)
        ui.onNodeWithTag("quest-gps-collect").performScrollTo().performClick()
        ui.onNodeWithTag("quest-gps-submit").performScrollTo().performClick()
        ui.onNodeWithTag("quest-gps-submit").assertIsEnabled()
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithText("GPS B simulasi").performScrollTo().performClick()
        ui.onNodeWithTag("quest-gps-submit").assertDoesNotExist()
        ui.onNodeWithTag("quest-gps-collect").performScrollTo().assertIsNotEnabled(); capture("quest-gps-b-pending-a")
        assertEquals(listOf("gps-a"), f.collectTargets); assertEquals(listOf("gps-a"), f.submitTargets)
        ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithText("GPS A simulasi").performScrollTo().performClick()
        ui.onNodeWithTag("quest-gps-collect").assertIsNotEnabled()
        ui.onNodeWithTag("quest-gps-submit").performScrollTo().assertIsEnabled(); capture("quest-gps-a-own-retry")
        ui.onNodeWithTag("quest-gps-submit").performClick()
        ui.onNodeWithText("Lokasi berhasil diverifikasi.").assertExists()
        assertEquals(listOf("gps-a"), f.collectTargets); assertEquals(listOf("gps-a", "gps-a"), f.submitTargets)
        assertEquals(0, f.answers.get())
    }

    private fun capture(name: String) {
        val output = ui.activity.getExternalFilesDir("audit")!!.resolve("$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
