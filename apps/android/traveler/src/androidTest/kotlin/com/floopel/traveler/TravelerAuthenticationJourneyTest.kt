package com.floopel.traveler

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.domain.model.TravelerIdentityView
import com.floopel.feature.traveler.testing.TravelerFixture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertFalse
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class TravelerAuthenticationJourneyTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun loginIsRequiredAfterColdLaunchAndLogout() {
        val fixture = TravelerFixture()
        val interceptsBack = AtomicBoolean(true)
        fixture.identityState.value = TravelerIdentityView(generation = 1)
        ui.setContent { TravelerApp(fixture.runtime, {}, backHandler = { enabled, back ->
            SideEffect { interceptsBack.set(enabled) }
            BackHandler(enabled, back)
        }) }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Selamat datang").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
        assertFalse(interceptsBack.get())
        ui.onNodeWithText("Halo, penjelajah").assertDoesNotExist()
        capture("login-required")
        ui.onNodeWithText("Lupa password?").performScrollTo().performClick()
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
        ui.onNodeWithContentDescription("Kembali").performClick()
        ui.onNode(hasSetTextAction() and hasText("Email")).performTextInput("fixture@example.test")
        ui.onNode(hasSetTextAction() and hasText("Password")).performTextInput("synthetic-password")
        ui.onNode(hasText("Masuk", substring = false) and hasClickAction()).performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("traveler-navigation").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("Profile").onLast().performClick()
        ui.onNodeWithContentDescription("Pengaturan").performClick()
        ui.onNodeWithText("Keluar", substring = false).performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Selamat datang").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
        // The real native host + process restoration is covered by the separate opt-in journey.
        ui.onNodeWithContentDescription("Kembali").assertDoesNotExist()
        ui.waitUntil(5_000) { !interceptsBack.get() }
        capture("login-after-logout")
        ui.onNode(hasSetTextAction() and hasText("Email")).performTextReplacement("fixture@example.test")
        ui.onNode(hasSetTextAction() and hasText("Password")).performTextInput("synthetic-password")
        ui.onNode(hasText("Masuk", substring = false) and hasClickAction()).performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("traveler-navigation").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("Home").onLast().assertIsSelected()
        ui.onAllNodesWithText("Profile").onLast().assertIsNotSelected()
    }

    private fun capture(name: String) {
        val output = ui.activity.getExternalFilesDir("audit")!!.resolve("$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
