package com.floopel.traveler

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.view.KeyEvent
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        ui.waitUntil(30_000) { ui.onAllNodesWithText("Selamat datang").fetchSemanticsNodes().isNotEmpty() ||
            ui.onAllNodesWithTag("traveler-navigation").fetchSemanticsNodes().isNotEmpty() }
        if (ui.onAllNodesWithTag("traveler-navigation").fetchSemanticsNodes().isNotEmpty()) {
            // An earlier fixture journey may have left its own session valid. Never clear another account.
            ui.onAllNodesWithText("Profile").onLast().performClick()
            val label = "@${owner.getString("username")}"
            ui.waitUntil(30_000) { ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText(label).assertExists()
            ui.onNodeWithText("Keluar", substring = false).performScrollTo().performClick()
        }
        ui.waitUntil(30_000) { ui.onAllNodesWithText("Selamat datang").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
        ui.onNode(hasSetTextAction() and hasText("Email")).performTextInput(owner.getString("email"))
        ui.onNode(hasSetTextAction() and hasText("Password")).performTextInput(owner.getString("password"))
        ui.onNode(hasText("Masuk", substring = false) and hasClickAction()).performScrollTo().performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("traveler-navigation").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("Profile").onLast().performClick()
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
        ui.waitUntil(30_000) { ui.onAllNodes(hasSetTextAction() and hasText("Urutan")).fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(hasSetTextAction() and hasText("Urutan")).assertTextContains("1")
        ui.onNode(hasSetTextAction() and hasText("Waktu tiba")).performScrollTo().performTextReplacement("08:00")
        ui.onNode(hasSetTextAction() and hasText("Durasi (menit)")).performScrollTo().performTextReplacement("0")
        ui.onNodeWithText("Simpan Spot").performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("itinerary-title").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(30_000) { ui.onAllNodesWithText("08:00", substring = true).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Durasi (menit): 0", substring = true).assertExists()
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
        ui.onAllNodesWithText("Community").onLast().performClick()
        waitForCatalog()
        capture("live-community-public")
        ui.onNodeWithText("Open Trip publik").performScrollTo().performClick()
        waitForCatalog()
        capture("live-community-open-trip")
        ui.onNodeWithText("Curated Trip").performScrollTo().performClick()
        waitForCatalog()
        capture("live-community-curated")
        ui.onAllNodesWithText("Open Trip").onLast().performClick()
        waitForCatalog()
        capture("live-open-trip-catalog")
        ui.onAllNodesWithText("Profile").onLast().performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Keluar", substring = false).performScrollTo().performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithText("Selamat datang").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
        recordProcess(context, "restore")
        capture("live-login-after-logout")
        // Real system key, with ON_STOP as evidence that root Back was delegated to the host.
        val activity = ui.activity
        val stopped = CountDownLatch(1)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stopped.countDown() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { activity.lifecycle.addObserver(observer) }
        try {
            val keyTime = android.os.SystemClock.uptimeMillis()
            assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(keyTime, keyTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true))
            assertTrue(instrumentation.uiAutomation.injectInputEvent(KeyEvent(keyTime, android.os.SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true))
            assertTrue("Root Login must delegate system Back and stop the activity", stopped.await(10, TimeUnit.SECONDS))
            val output = File(context.getExternalFilesDir(null), "audit/live-private-back.json")
            output.writeText(JSONObject().put("key", "KEYCODE_BACK").put("activity_stopped", true)
                .put("navigation_absent", true).put("pid", android.os.Process.myPid()).toString())
        } finally { instrumentation.runOnMainSync { activity.lifecycle.removeObserver(observer) } }
    }

    private fun waitForCatalog() {
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("catalog-search").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(30_000) { ui.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).fetchSemanticsNodes().isEmpty() }
    }

    private fun capture(name: String) {
        val output = File(ui.activity.getExternalFilesDir(null), "audit/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** Third explicit phase: reopen after the logged-out root's system Back, then verify active tabs. */
@RunWith(AndroidJUnit4::class)
class LivePrivateLoginBackTest {
    private val ui = createAndroidComposeRule<MainActivity>()
    @get:Rule val liveRule: TestRule = RuleChain.outerRule(TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue(arguments.getString("floopelPrivateLive") == "true" && arguments.getString("floopelLoginBackCheck") == "true")
            base.evaluate()
        }
    } }).around(ui)

    @Test fun reopeningAfterSystemBackRequiresLoginAndReauthenticationSelectsHome() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val back = JSONObject(File(context.getExternalFilesDir(null), "audit/live-private-back.json").readText())
        assertTrue(back.getBoolean("activity_stopped"))
        val accounts = JSONObject(File(context.getExternalFilesDir(null), "private-fixture.json").readText()).getJSONArray("accounts")
        val owner = (0 until accounts.length()).map { accounts.getJSONObject(it) }.single { it.getString("role") == "organizer" }
        ui.waitUntil(30_000) { ui.onAllNodesWithText("Selamat datang").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
        ui.onNodeWithContentDescription("Kembali").assertDoesNotExist()
        capture("live-login-after-back")
        ui.onNode(hasSetTextAction() and hasText("Email")).performTextInput(owner.getString("email"))
        ui.onNode(hasSetTextAction() and hasText("Password")).performTextInput(owner.getString("password"))
        ui.onNode(hasText("Masuk", substring = false) and hasClickAction()).performScrollTo().performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithTag("traveler-navigation").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("Home").onLast().assertIsSelected()
        ui.onAllNodesWithText("Profile").onLast().assertIsNotSelected()
        capture("live-login-home-selected")
        ui.onAllNodesWithText("Profile").onLast().performClick()
        ui.onAllNodesWithText("Profile").onLast().assertIsSelected()
        val label = "@${owner.getString("username")}"
        ui.waitUntil(30_000) { ui.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Keluar", substring = false).performScrollTo().performClick()
        ui.waitUntil(30_000) { ui.onAllNodesWithText("Selamat datang").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
        recordProcess(context, "login-back")
    }

    private fun capture(name: String) {
        val output = File(ui.activity.getExternalFilesDir(null), "audit/$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}

private fun recordProcess(context: android.content.Context, phase: String) {
    val output = File(context.getExternalFilesDir(null), "audit/live-private-process-$phase.json")
    output.parentFile?.mkdirs()
    output.writeText(JSONObject().put("phase", phase).put("pid", android.os.Process.myPid())
        .put("started_elapsed", android.os.Process.getStartElapsedRealtime()).toString())
}
