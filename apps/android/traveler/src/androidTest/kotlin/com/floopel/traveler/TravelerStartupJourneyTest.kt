package com.floopel.traveler

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.traveler.platform.storage.AndroidTravelerStartupPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.io.File

private fun startupScope() = InstrumentationRegistry.getArguments().getString("floopelStartupScope") ?: "qa-intro-not-configured"

private fun startupOptIn(base: org.junit.runners.model.Statement, completed: Boolean) = object : Statement() {
    override fun evaluate() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("floopelStartupCheck") == "true")
        val scope = startupScope(); require(scope.startsWith("qa-intro-"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val progress = runBlocking { AndroidTravelerStartupPreferences(context, scope).read() }
        assertEquals("Use a fresh isolated scope for the first phase", completed, progress.completed)
        base.evaluate()
    }
}

@RunWith(AndroidJUnit4::class)
class TravelerStartupJourneyTest {
    private val ui = nativeTravelerRule(startupScope(), startupDelayMillis = 600)
    @get:Rule val rule: TestRule = RuleChain.outerRule(TestRule { base, _ -> startupOptIn(base, completed = false) }).around(ui)

    @Test fun brandedStartupAndFourIntroPagesPersistAcrossRecreation() {
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("startup-splash").fetchSemanticsNodes().isNotEmpty() }
        val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
        val mark = ui.onNodeWithTag("startup-mark").fetchSemanticsNode().boundsInRoot
        val density = ui.activity.resources.displayMetrics.density
        assertEquals(96f * density, mark.width, 1f); assertEquals(mark.width, mark.height, 1f)
        assertEquals(root.center.x, mark.center.x, 1f); assertEquals(root.center.y, mark.center.y, 1f)
        capture("startup-branded")
        waitTitle("Perjalanan yang lebih bermakna")
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
        val illustration = ui.onNodeWithTag("intro-illustration").fetchSemanticsNode().boundsInRoot
        assertEquals(280f * density, illustration.width, 1f); assertEquals(236f * density, illustration.height, 1f)
        assertEquals(36f * density, ui.onNodeWithTag("intro-brand").fetchSemanticsNode().boundsInRoot.width, 1f)
        capture("intro-1")
        ui.onNodeWithText("Lanjut").performScrollTo().performClick()
        waitTitle("Temukan cerita di setiap tempat"); capture("intro-2")
        ui.onNodeWithText("Kembali").performClick(); waitTitle("Perjalanan yang lebih bermakna")
        ui.onNodeWithText("Lanjut").performClick(); waitTitle("Temukan cerita di setiap tempat")
        ui.activityRule.scenario.recreate(); waitTitle("Temukan cerita di setiap tempat")
        ui.onNodeWithText("Lanjut").performClick(); waitTitle("Rencanakan, lalu pergi bersama"); capture("intro-3")
        ui.onNodeWithText("Lanjut").performClick(); waitTitle("Jadikan jelajah sebuah petualangan"); capture("intro-4")
        ui.onNodeWithContentDescription("Langkah 4 dari 4").assertExists()
        ui.onNodeWithText("Lanjut ke login").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Selamat datang").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(runBlocking { AndroidTravelerStartupPreferences(context, startupScope()).read() }.completed)
        capture("intro-completed-login")
    }

    private fun waitTitle(title: String) {
        ui.waitUntil(10_000) { ui.onAllNodes(hasTestTag("intro-heading") and hasText(title)).fetchSemanticsNodes().isNotEmpty() }
        // Local SVG decoding is asynchronous and is not tracked by Compose's idling resource.
        // Check the illustration's painted pixels before recording visual evidence.
        ui.waitUntil(5_000) {
            val bounds = ui.onNodeWithTag("intro-illustration").fetchSemanticsNode().boundsInRoot
            val bitmap = ui.onRoot().captureToImage().asAndroidBitmap()
            var painted = 0
            for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height) step 4) {
                for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(bitmap.width) step 4) {
                    if (bitmap.getPixel(x, y) and 0x00ffffff == 0x003730a3) painted++
                }
            }
            painted > 100
        }
    }
    private fun capture(name: String) {
        val output = File(ui.activity.getExternalFilesDir(null), "audit/$name.png"); output.parentFile?.mkdirs()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        output.outputStream().use { screen.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
        var viewTree = ""
        ui.runOnUiThread {
            fun tree(view: android.view.View, depth: Int): String = buildString {
                append(" ".repeat(depth)); append(view.javaClass.simpleName)
                append(" h=${view.height} y=${view.y} fits=${view.fitsSystemWindows} visibility=${view.systemUiVisibility}\n")
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) append(tree(view.getChildAt(index), depth + 1))
            }
            viewTree = tree(ui.activity.window.decorView, 0)
        }
        File(output.parentFile, "$name-geometry.json").writeText(org.json.JSONObject().apply {
            put("root_width_px", root.width); put("root_height_px", root.height)
            put("window_height_px", ui.activity.window.decorView.height)
            put("density", ui.activity.resources.displayMetrics.density)
            put("view_tree", viewTree)
            put("status_bar_color", ui.activity.window.statusBarColor)
            put("navigation_bar_color", ui.activity.window.navigationBarColor)
        }.toString(2))
        assertEquals("Native insets must not shrink the Compose root after recreation", ui.activity.window.decorView.height.toFloat(), root.height, 1f)
    }
}

@RunWith(AndroidJUnit4::class)
class TravelerStartupReopenTest {
    private val ui = nativeTravelerRule(startupScope())
    @get:Rule val rule: TestRule = RuleChain.outerRule(TestRule { base, _ -> startupOptIn(base, completed = true) }).around(ui)

    @Test fun returningSignedOutInstallationGoesDirectlyToLogin() {
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Selamat datang").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("intro-heading").assertDoesNotExist()
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
        ui.onNodeWithContentDescription("Kembali").assertDoesNotExist()
        val output = File(ui.activity.getExternalFilesDir(null), "audit/intro-returning-login.png")
        output.outputStream().use { requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** Separate explicit invocations, with force-stop between them, prove unfinished progress is durable. */
@RunWith(AndroidJUnit4::class)
class TravelerStartupProgressCheckpointTest {
    private val ui = nativeTravelerRule(startupScope())
    @get:Rule val rule: TestRule = RuleChain.outerRule(TestRule { base, _ -> startupOptIn(base, completed = false) }).around(ui)

    @Test fun firstIntroActionWritesAProcessIndependentCheckpoint() {
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Perjalanan yang lebih bermakna").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Lanjut").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Temukan cerita di setiap tempat").fetchSemanticsNodes().isNotEmpty() }
        val output = File(ui.activity.getExternalFilesDir(null), "audit/intro-progress-process.json"); output.parentFile?.mkdirs()
        output.writeText(org.json.JSONObject().apply {
            put("scope", startupScope()); put("pid", android.os.Process.myPid()); put("step", 1)
        }.toString(2))
    }
}

@RunWith(AndroidJUnit4::class)
class TravelerStartupProgressResumeTest {
    private val ui = nativeTravelerRule(startupScope())
    @get:Rule val rule: TestRule = RuleChain.outerRule(TestRule { base, _ -> startupOptIn(base, completed = false) }).around(ui)

    @Test fun aNewProcessResumesTheUnfinishedIntroWithoutUnlockingFeatures() {
        val before = org.json.JSONObject(File(ui.activity.getExternalFilesDir(null), "audit/intro-progress-process.json").readText())
        assertEquals(startupScope(), before.getString("scope")); assertNotEquals(before.getInt("pid"), android.os.Process.myPid())
        ui.waitUntil(10_000) { ui.onAllNodesWithText("Temukan cerita di setiap tempat").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("Langkah 2 dari 4").assertExists()
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
        ui.onNodeWithText("Lanjut").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Rencanakan, lalu pergi bersama").fetchSemanticsNodes().isNotEmpty() }
        val progress = runBlocking { AndroidTravelerStartupPreferences(ui.activity, startupScope()).read() }
        assertEquals(2, progress.step); assertFalse(progress.completed)
        File(ui.activity.getExternalFilesDir(null), "audit/intro-progress-restored.json").writeText(org.json.JSONObject().apply {
            put("scope", startupScope()); put("previous_pid", before.getInt("pid")); put("pid", android.os.Process.myPid())
            put("step", progress.step); put("completed", progress.completed)
        }.toString(2))
    }
}
