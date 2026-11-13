package com.floopel.traveler

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.testing.TravelerPublicationFixture
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TravelerPublicationStateTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private fun launch(f: TravelerPublicationFixture) {
        ui.setContent { TravelerApp(f.runtime, {}, backHandler = { enabled, back -> BackHandler(enabled, back) }) }
        ui.waitUntil(8_000) { ui.onAllNodesWithText("Halo, Traveler").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("Profile").onLast().performClick()
        ui.onNodeWithText("Itinerary saya").performScrollTo().performClick()
        ui.onNodeWithText(f.base.plan.itinerary.title).performClick()
        ui.onNodeWithText("Tinjau publikasi").performScrollTo().performClick()
    }
    private fun publish() {
        ui.onNodeWithText("Saya sudah meninjau catatan yang akan dipublikasikan.").performScrollTo().performClick()
        ui.onNodeWithText("Tidak ada data pribadi yang tidak semestinya dibagikan.").performScrollTo().performClick()
        ui.onNodeWithTag("publication-submit").performClick()
        ui.onNodeWithTag("publication-confirm").performClick()
    }
    @Test fun publishMetadataWithoutStopCountDoesNotRenderThePlanAsEmpty() {
        val f = TravelerPublicationFixture(); launch(f); publish()
        ui.waitUntil(8_000) { f.ownerReads.get() == 2 }
        ui.onNodeWithText("Publikasi dikonfirmasi oleh server.").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Rencana kosong belum dapat dipublikasikan. Tambahkan Spot terlebih dahulu.").assertDoesNotExist()
        ui.onNodeWithText("1 hari · 1 Spot").assertExists()
        ui.onNodeWithText("Batalkan publikasi").assertExists()
        assertEquals(1, f.base.publicationCalls.get()); capture("confirmed-not-empty")
    }
    @Test fun ownerReadFailureAfterConfirmedPublishOffersOnlyReadRecovery() {
        val f = TravelerPublicationFixture(); f.onPublished = { f.failOwnerRead = true }; launch(f); publish()
        ui.waitUntil(8_000) { f.ownerReads.get() == 2 }
        ui.onNodeWithText("Koneksi belum tersedia. Periksa internet lalu coba lagi.").assertExists()
        ui.onNodeWithTag("publication-submit").assertIsNotEnabled()
        ui.onNodeWithText("Rencana kosong belum dapat dipublikasikan. Tambahkan Spot terlebih dahulu.").assertDoesNotExist()
        capture("owner-read-failed")
        f.failOwnerRead = false; ui.onNodeWithText("Coba lagi").performScrollTo().performClick()
        ui.waitUntil(8_000) { f.ownerReads.get() == 3 }
        ui.onNodeWithText("Koneksi belum tersedia. Periksa internet lalu coba lagi.").assertDoesNotExist()
        ui.onNodeWithText("Batalkan publikasi").performScrollTo().assertIsDisplayed()
        assertEquals(1, f.base.publicationCalls.get()); assertEquals(0, f.unpublishCalls.get()); capture("owner-read-recovered")
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        val file = ui.activity.getExternalFilesDir("audit")!!.resolve("publication-state-$name.png")
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
