package com.floopel.traveler

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.domain.model.TravelerIdentityView
import com.floopel.feature.traveler.domain.repository.TravelerIntroProgress
import com.floopel.feature.traveler.testing.TravelerFixture
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TravelerStartupSmallWindowTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun introRemainsScrollableAt320By640AndFont130() {
        val fixture = TravelerFixture(); fixture.identityState.value = TravelerIdentityView(generation = 1)
        fixture.introProgress = TravelerIntroProgress()
        ui.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 1.3f)) {
                Box(Modifier.requiredSize(320.dp, 640.dp)) {
                    TravelerApp(fixture.runtime, {}, backHandler = { enabled, back -> BackHandler(enabled, back) })
                }
            }
        }
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("intro-heading").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("Lanjut").performScrollTo().assertIsDisplayed().performClick()
        ui.onNodeWithText("Kembali").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Lanjut").performClick()
        ui.onNodeWithText("Lanjut").performScrollTo().performClick()
        ui.onNodeWithText("Lanjut ke login").performScrollTo().assertIsDisplayed()
        val output = ui.activity.getExternalFilesDir("audit")!!.resolve("intro-small-font130.png"); output.parentFile?.mkdirs()
        output.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ui.onNodeWithText("Lanjut ke login").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Selamat datang").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
    }
}
