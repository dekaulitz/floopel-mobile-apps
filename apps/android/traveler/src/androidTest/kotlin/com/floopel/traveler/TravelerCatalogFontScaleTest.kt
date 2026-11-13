package com.floopel.traveler

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.testing.TravelerFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TravelerCatalogFontScaleTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()

    @Test fun doubledFontKeepsSearchTextAndPublicationActionsInsideTheirControls() {
        val fixture = TravelerFixture()
        ui.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                TravelerApp(fixture.runtime, {}, backHandler = { enabled, back -> BackHandler(enabled, back) })
            }
        }
        ui.waitUntil(5_000) { ui.onAllNodesWithText("Halo, Traveler").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("Community").onLast().performClick()
        val search = ui.onNodeWithTag("catalog-search").fetchSemanticsNode().boundsInRoot
        val hint = ui.onNodeWithText("Cari itinerary publik", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(hint.left >= search.left && hint.right <= search.right && hint.top >= search.top && hint.bottom <= search.bottom)
        capture("community-search-font-200")
        ui.onNodeWithTag("public-plan-copy").performScrollTo()
        val view = ui.onNodeWithTag("public-plan-action").fetchSemanticsNode().boundsInRoot
        val copy = ui.onNodeWithTag("public-plan-copy").fetchSemanticsNode().boundsInRoot
        assertEquals(view.height, copy.height, 1f)
        ui.onNodeWithText("Lihat itinerary").assertIsDisplayed()
        ui.onNodeWithText("Simpan salinan").assertIsDisplayed()
        capture("community-actions-font-200")
    }

    private fun capture(name: String) {
        val output = ui.activity.getExternalFilesDir("audit")!!.resolve("$name.png")
        output.parentFile?.mkdirs()
        output.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
