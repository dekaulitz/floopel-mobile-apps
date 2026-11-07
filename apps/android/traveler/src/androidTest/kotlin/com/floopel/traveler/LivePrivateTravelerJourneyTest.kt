package com.floopel.traveler

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Opt-in native host + real API test. Fixture credentials are read from private device test setup. */
@RunWith(AndroidJUnit4::class)
class LivePrivateTravelerJourneyTest {
    private val ui = createAndroidComposeRule<MainActivity>()
    // Check opt-in before the activity rule starts a runtime or touches the network.
    @get:Rule val liveRule: TestRule = RuleChain.outerRule(TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            assumeTrue(InstrumentationRegistry.getArguments().getString("floopelPrivateLive") == "true")
            base.evaluate()
        }
    } }).around(ui)

    @Test fun nativeLoginCreatePlanEditFirstStopAndRestoreActivity() {
        assertTrue("Real-device integration requires HTTPS", BuildConfig.API_ORIGIN.startsWith("https://"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manifest = JSONObject(File(context.getExternalFilesDir(null), "private-fixture.json").readText())
        val accounts = manifest.getJSONArray("accounts")
        val owner = (0 until accounts.length()).map { accounts.getJSONObject(it) }.single { it.getString("role") == "organizer" }
        ui.waitUntil(30_000) { ui.onAllNodesWithText("Halo, penjelajah").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("Profile").onLast().performClick()
        ui.onNode(hasText("Masuk", substring = false) and hasClickAction()).performClick()
        ui.onNode(hasSetTextAction() and hasText("Email")).performTextInput(owner.getString("email"))
        ui.onNode(hasSetTextAction() and hasText("Kata sandi")).performTextInput(owner.getString("password"))
        ui.onNode(hasText("Masuk", substring = false) and hasClickAction()).performScrollTo().performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithText("Itinerary saya").fetchSemanticsNodes().isNotEmpty() }
        // Native activity recreation exercises retained host ownership and the actual saved route.
        ui.activityRule.scenario.recreate()
        ui.onNodeWithText("Itinerary saya").performScrollTo().performClick()
        ui.onNodeWithText("Buat itinerary").performClick()
        val title = "Android UI QA ${manifest.getString("run_id")} ${System.currentTimeMillis()}"
        ui.onNode(hasSetTextAction() and hasText("Judul itinerary")).performTextInput(title)
        ui.onAllNodesWithText("Buat itinerary").onLast().performClick()
        ui.waitUntil(30_000) { ui.onAllNodes(hasTestTag("itinerary-title") and hasText(title)).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("itinerary-title").assertTextEquals(title)
        ui.onNodeWithText("Tambah Spot", substring = false).performScrollTo().performClick()
        ui.waitUntil(30_000) { ui.onAllNodes(hasText("Tambah Spot", substring = false) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodes(hasText("Tambah Spot", substring = false) and hasClickAction()).onFirst().performScrollTo().performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("itinerary-title").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Simpan Spot").assertDoesNotExist()
        ui.onNodeWithText("Buat Trip dari itinerary", substring = false).performScrollTo().assertIsEnabled()
        // Open the first Stop using its test tag, independent of the existing catalog's editorial title.
        ui.onAllNodesWithTag("itinerary-stop").onFirst().performScrollTo().performClick()
        ui.onNode(hasSetTextAction() and hasText("Urutan")).assertTextContains("1")
        ui.onNode(hasSetTextAction() and hasText("Waktu tiba")).performScrollTo().performTextReplacement("08:00")
        ui.onNode(hasSetTextAction() and hasText("Durasi (menit)")).performScrollTo().performTextReplacement("0")
        ui.onNodeWithText("Simpan Spot").performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("itinerary-title").fetchSemanticsNodes().isNotEmpty() }
        ui.activityRule.scenario.recreate()
        ui.onNodeWithTag("itinerary-title").assertTextEquals(title)
        capture("live-private-itinerary")
        recordProcess(context, "journey")
    }

    private fun capture(name: String) {
        val output = File(ui.activity.getExternalFilesDir(null), "audit/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** Second explicit phase: launch after the journey runner has exited and the target process was stopped. */
@RunWith(AndroidJUnit4::class)
class LivePrivateSessionRestoreTest {
    private val ui = createAndroidComposeRule<MainActivity>()
    @get:Rule val liveRule: TestRule = RuleChain.outerRule(TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue(arguments.getString("floopelPrivateLive") == "true" && arguments.getString("floopelRestoreCheck") == "true")
            base.evaluate()
        }
    } }).around(ui)

    @Test fun nativeEncryptedRefreshRestoresTheFixtureAccountAndLogoutClearsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = JSONObject(File(context.getExternalFilesDir(null), "audit/live-private-process-journey.json").readText())
        assertTrue("Restore requires a new target process", previous.getInt("pid") != android.os.Process.myPid() ||
            previous.getLong("started_elapsed") != android.os.Process.getStartElapsedRealtime())
        val accounts = JSONObject(File(context.getExternalFilesDir(null), "private-fixture.json").readText()).getJSONArray("accounts")
        val owner = (0 until accounts.length()).map { accounts.getJSONObject(it) }.single { it.getString("role") == "organizer" }
        ui.waitUntil(30_000) { ui.onAllNodesWithText("Profile").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("Profile").onLast().performClick()
        val label = "@${owner.getString("username")}"
        ui.waitUntil(30_000) { ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText(label).assertExists()
        ui.onNodeWithText("Keluar", substring = false).performScrollTo().performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithText("Halo, penjelajah").fetchSemanticsNodes().isNotEmpty() }
        recordProcess(context, "restore")
    }
}

private fun recordProcess(context: android.content.Context, phase: String) {
    val output = File(context.getExternalFilesDir(null), "audit/live-private-process-$phase.json")
    output.parentFile?.mkdirs()
    output.writeText(JSONObject().put("phase", phase).put("pid", android.os.Process.myPid())
        .put("started_elapsed", android.os.Process.getStartElapsedRealtime()).toString())
}
