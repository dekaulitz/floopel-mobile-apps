package com.floopel.traveler

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.LocaleList
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.testing.TravelerPhotoFixture
import com.floopel.traveler.platform.media.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale

/** Production Compose and native codec/picker, with isolated domain doubles for all writes. */
@RunWith(AndroidJUnit4::class)
class TravelerPhotoDesignTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val picked = AtomicInteger()
    private val files = mutableListOf<File>()
    private val originalLocale = Locale.getDefault()
    private var originalFontScale: Float? = null
    private val dialogFontScale = AtomicReference(0f)
    @After fun cleanup() {
        Locale.setDefault(originalLocale)
        originalFontScale?.let { shell("settings put system font_scale $it") }
        files.forEach { check(it.delete()) }
    }
    private fun shell(command: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }
    private fun avatar(): String = File.createTempFile("photo-avatar-fixture-", ".png", ui.activity.cacheDir).also {
        files += it; it.writeBytes(image().bytes)
    }.toURI().toString()
    private fun image(): ProfilePhotoImage {
        val bitmap = Bitmap.createBitmap(480, 320, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.rgb(60, 47, 170)); drawRect(0f, 0f, 240f, 320f, android.graphics.Paint().apply { color = Color.rgb(244, 175, 41) }) }
        return try { val bytes = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); ProfilePhotoImage(bytes.toByteArray(), "image/png") }
        finally { bitmap.recycle() }
    }
    private fun launch(f: TravelerPhotoFixture = TravelerPhotoFixture(), scale: Float = 1f,
        picker: suspend () -> ProfilePhotoSelection? = { picked.incrementAndGet(); ProfilePhotoSelection.Loaded(image()) },
        bannerPicker: (suspend () -> TravelerImage?)? = null, language: String = "id") {
        if (scale != 1f) {
            originalFontScale = Settings.System.getFloat(ui.activity.contentResolver, Settings.System.FONT_SCALE, 1f)
            shell("settings put system font_scale $scale")
            ui.activityRule.scenario.recreate()
            ui.waitUntil(8_000) { ui.activity.resources.configuration.fontScale == scale }
        }
        Locale.setDefault(Locale.forLanguageTag(language))
        ui.setContent {
            val density = LocalDensity.current.density
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val localizedConfiguration = remember(configuration, language) { Configuration(configuration).apply { setLocales(LocaleList.forLanguageTags(language)); fontScale = scale } }
            val localized = remember(context, localizedConfiguration) { context.createConfigurationContext(localizedConfiguration) }
            CompositionLocalProvider(LocalDensity provides Density(density, scale), LocalContext provides localized, LocalConfiguration provides localizedConfiguration) {
                TravelerApp(f.runtime, {}, photoPicker = bannerPicker, profilePhotoPicker = picker,
                    profilePhotoCropper = AndroidProfilePhotoCropper, photoWindowStyle = { dialogFontScale.set(LocalDensity.current.fontScale); PhotoWindowStyle(it) },
                    language = language,
                    backHandler = { enabled, back -> BackHandler(enabled, back) })
            }
        }
        ui.waitUntil(8_000) { ui.onAllNodesWithText(if (language == "id") "Halo, Traveler" else "Hi, Traveler").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun editProfile() {
        ui.onAllNodesWithText("Profile").onLast().performClick()
        ui.onNodeWithText("Edit profil").performClick()
        ui.onNodeWithTag("profile-edit-photo").assertIsEnabled()
    }
    private fun photo() { ui.onNodeWithTag("profile-edit-photo").performClick(); ui.onNodeWithTag("profile-photo-choose").assertIsDisplayed() }
    private fun select() {
        ui.onNodeWithTag("profile-photo-choose").performScrollTo().performClick()
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("profile-photo-crop-ready").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun done() { ui.onNodeWithTag("profile-photo-crop-done").performScrollTo().performClick() }
    private fun back() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        ui.waitForIdle()
    }
    private fun capture(name: String) {
        val file = File(ui.activity.getExternalFilesDir(null), "photo-audit/$name.png"); file.parentFile?.mkdirs()
        file.outputStream().buffered().use { output ->
            check(ui.onNodeWithTag("profile-photo-flow").captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }
    @Test fun chooseAndCropUseFigmaGuttersSquareAndExplicitCancel() {
        val f = TravelerPhotoFixture(); launch(f); editProfile(); photo()
        val density = ui.activity.resources.displayMetrics.density
        val flow = ui.onNodeWithTag("profile-photo-flow").fetchSemanticsNode().boundsInRoot
        val choose = ui.onNodeWithTag("profile-photo-choose").fetchSemanticsNode().boundsInRoot
        assertEquals(20f * density, choose.left - flow.left, 1f); assertEquals(48f * density, choose.height, 1f)
        capture("photo-current")
        select()
        val crop = ui.onNodeWithTag("profile-photo-crop-ready").fetchSemanticsNode().boundsInRoot
        assertEquals(crop.width, crop.height, 1f); assertEquals(20f * density, crop.left - flow.left, 1f)
        capture("photo-crop")
        ui.onNodeWithTag("profile-photo-rotate").performScrollTo().performClick()
        ui.onNodeWithText("Batal").performScrollTo().performClick()
        ui.onNodeWithTag("profile-photo-choose").assertExists(); assertEquals(0, f.uploadCalls.get()); assertEquals(1, picked.get())
    }
    @Test fun readyRequiresStatusAndCanonicalAvatarWhileMetadataDraftSurvives() {
        val f = TravelerPhotoFixture(); f.nextAvatar = avatar()
        launch(f); editProfile()
        ui.onNode(hasSetTextAction() and hasText("Nama depan")).performTextReplacement("Draft tetap")
        photo(); select()
        val actions = ui.onNodeWithTag("profile-photo-crop-ready").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        ui.runOnIdle { assertTrue(actions.first { it.label == "Perbesar foto" }.action()) }
        done(); ui.waitUntil(8_000) { f.uploadCalls.get() == 1 }
        ui.onNodeWithText("Foto lama tetap digunakan sampai foto baru siap.").assertExists()
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("profile-photo-artwork-ready").fetchSemanticsNodes().isNotEmpty() }
        val art = ui.onNodeWithTag("profile-photo-artwork-ready").fetchSemanticsNode().boundsInRoot
        val density = ui.activity.resources.displayMetrics.density
        assertEquals(176f * density, art.width, 1f); assertEquals(117.333f * density, art.height, 2f)
        capture("photo-processing")
        assertNotNull(f.received)
        f.statusResult = CatalogResult.Ready(ProfilePhotoUpload(f.uploadId, ProfilePhotoStatus.READY))
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("profile-photo-return").fetchSemanticsNodes().isNotEmpty() }
        capture("photo-ready")
        back()
        ui.onNode(hasSetTextAction() and hasText("Nama depan")).assertTextContains("Draft tetap")
        assertEquals(1, f.uploadCalls.get()); assertEquals(0, f.metadataCalls.get())
    }
    @Test fun closingPhotoWithoutSavingMetadataPreservesTheMountedEditorDraft() {
        val f = TravelerPhotoFixture(); launch(f); editProfile()
        ui.onNode(hasSetTextAction() and hasText("Nama depan")).performTextReplacement("Draft belum disimpan")
        photo(); select(); ui.onNodeWithText("Batal").performScrollTo().performClick(); back()
        ui.onNode(hasSetTextAction() and hasText("Nama depan")).assertTextContains("Draft belum disimpan")
        assertEquals(0, f.uploadCalls.get()); assertEquals(0, f.metadataCalls.get())
    }
    @Test fun canceledSystemSelectionReturnsToCurrentWithoutAnyWrite() {
        val f = TravelerPhotoFixture(); launch(f, picker = { picked.incrementAndGet(); null }); editProfile(); photo()
        ui.onNodeWithTag("profile-photo-choose").performClick(); ui.waitForIdle()
        ui.onNodeWithTag("profile-photo-choose").assertIsEnabled(); assertEquals(1, picked.get()); assertEquals(0, f.uploadCalls.get())
    }
    @Test fun changeLimitShowsItsSpecificMessageAndKeepsTheOldPhoto() {
        val f = TravelerPhotoFixture(); f.uploadResult = CatalogResult.HttpError(429, code = ProfilePhotoCodes.ChangeLimited, retryAfterSeconds = 60)
        launch(f); editProfile(); photo(); select(); done()
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("profile-photo-choose-again").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("profile-photo-choose-again").assertIsNotEnabled()
        ui.onNodeWithTag("profile-photo-current-avatar").assertExists(); assertEquals(1, f.uploadCalls.get())
        capture("photo-change-limited")
    }
    @Test fun failedStatusCheckCanRecoverByReadingWithoutUploadingAgain() {
        val f = TravelerPhotoFixture(); f.statusResult = CatalogResult.NetworkError
        launch(f); editProfile(); photo(); select(); done()
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("profile-photo-check").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("profile-photo-artwork-ready").fetchSemanticsNodes().isNotEmpty() }
        val art = ui.onNodeWithTag("profile-photo-artwork-ready").fetchSemanticsNode().boundsInRoot
        val density = ui.activity.resources.displayMetrics.density
        assertEquals(176f * density, art.width, 1f); assertEquals(117.333f * density, art.height, 2f)
        capture("photo-review")
        f.nextAvatar = "https://fixture.invalid/new.jpg"; f.statusResult = CatalogResult.Ready(ProfilePhotoUpload(f.uploadId, ProfilePhotoStatus.READY))
        val gate = CompletableDeferred<Unit>(); f.beforeStatus = { gate.await() }
        ui.onNodeWithTag("profile-photo-check").performScrollTo().performClick()
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("profile-photo-artwork-ready").fetchSemanticsNodes().isNotEmpty() }
        val checking = ui.onNodeWithTag("profile-photo-artwork-ready").fetchSemanticsNode().boundsInRoot
        assertEquals(148f * density, checking.width, 1f); assertEquals(98.6667f * density, checking.height, 2f)
        capture("photo-checking"); gate.complete(Unit)
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("profile-photo-return").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, f.uploadCalls.get())
    }
    @Test fun twoHundredPercentFontKeepsCropDoneAndCancelReachable() {
        val f = TravelerPhotoFixture(); launch(f, 2f, language = "en")
        ui.onAllNodesWithText("Profile").onLast().performClick(); ui.onNodeWithText("Edit profile").performClick(); photo(); select()
        assertEquals(2f, dialogFontScale.get(), 0f)
        val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        ui.onNodeWithText("Done").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layout) }
        assertEquals(2f, layout.single().layoutInput.density.fontScale, 0f)
        ui.onNodeWithTag("profile-photo-crop-done").performScrollTo().assertIsDisplayed().assertIsEnabled()
        capture("photo-crop-en-font200")
        ui.onNodeWithText("Cancel").performScrollTo().performClick(); assertEquals(0, f.uploadCalls.get())
    }
    private fun nativePicker(registry: ActivityResultRegistry? = null): AndroidTravelerPhotoPicker {
        val result = AtomicReference<AndroidTravelerPhotoPicker>()
        ui.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        ui.activityRule.scenario.onActivity { activity -> result.set(if (registry == null) AndroidTravelerPhotoPicker(activity)
            else AndroidTravelerPhotoPicker(activity, registry = registry)) }
        ui.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        return result.get()
    }
    private fun cancelExternalPicker() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var pkg = ""
        ui.waitUntil(8_000) { pkg = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString().orEmpty()
            pkg.contains("providers.media") || pkg.contains("documentsui") }
        assertTrue(pkg.contains("providers.media") || pkg.contains("documentsui"))
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        ui.waitForIdle()
    }
    @Test fun realSdkProfilePickerOpensAndCancelDoesNotUpload() {
        val picker = nativePicker(); val f = TravelerPhotoFixture(); launch(f, picker = picker::selectProfile)
        editProfile(); photo(); ui.onNodeWithTag("profile-photo-choose").performClick(); cancelExternalPicker()
        ui.onNodeWithTag("profile-photo-choose").assertIsEnabled(); assertEquals(0, f.uploadCalls.get())
    }
    @Test fun realSdkTripBannerPickerOpensFromTheFirstTap() {
        val picker = nativePicker(); val f = TravelerPhotoFixture(); launch(f, bannerPicker = picker::select)
        ui.onAllNodesWithText("Profile").onLast().performClick(); ui.onNodeWithText("Trip saya").performScrollTo().performClick()
        ui.onNodeWithText("Trip simulasi").performClick()
        ui.onNodeWithText("Pilih banner Trip (opsional)").performScrollTo().performClick(); cancelExternalPicker()
        assertEquals(0, f.base.bannerCalls.get()); assertEquals(0, f.base.tripCreateCalls.get())
    }
    @Test fun myItineraryCanOpenTripFormAndItsNativeBannerPickerWithoutCreatingATrip() {
        val picker = nativePicker(); val f = TravelerPhotoFixture(); launch(f, bannerPicker = picker::select)
        ui.onAllNodesWithText("Profile").onLast().performClick(); ui.onNodeWithText("Itinerary saya").performScrollTo().performClick()
        ui.onNodeWithText(f.base.plan.itinerary.title).performClick()
        ui.onNodeWithText("Buat Trip dari itinerary").performScrollTo().performClick()
        ui.onNodeWithText("Zona waktu: Asia/Jakarta").performScrollTo().assertExists()
        ui.onNodeWithText("Pilih banner Trip (opsional)").performScrollTo().performClick(); cancelExternalPicker()
        assertEquals(0, f.base.bannerCalls.get()); assertEquals(0, f.base.tripCreateCalls.get())
    }
    @Test fun missingPickerActivityDoesNotPermanentlyBlockTheNextLaunch() {
        val launches = AtomicInteger()
        val registry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                if (launches.incrementAndGet() == 1) throw ActivityNotFoundException("Fixture no handler")
                dispatchResult(requestCode, Activity.RESULT_CANCELED, Intent())
            }
        }
        val picker = nativePicker(registry); val f = TravelerPhotoFixture(); launch(f, picker = picker::selectProfile)
        editProfile(); photo(); ui.onNodeWithTag("profile-photo-choose").performClick()
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("profile-photo-choose-again").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("profile-photo-choose-again").performScrollTo().performClick()
        ui.waitUntil(5_000) { launches.get() == 2 }; assertEquals(0, f.uploadCalls.get())
    }
    @Test fun emptyOwnedItineraryCannotCreateATrip() {
        val f = TravelerPhotoFixture(); f.base.plan = f.base.plan.copy(stops = emptyList(), itinerary = f.base.plan.itinerary.copy(stops = 0))
        launch(f); ui.onAllNodesWithText("Profile").onLast().performClick()
        ui.onNodeWithText("Itinerary saya").performScrollTo().performClick(); ui.onNodeWithText(f.base.plan.itinerary.title).performClick()
        ui.onNodeWithText("Buat Trip dari itinerary").performScrollTo().assertIsNotEnabled(); assertEquals(0, f.base.tripCreateCalls.get())
    }
    @Test fun ownedItineraryCreatesOneFixtureTripAfterExplicitFormSubmission() {
        val f = TravelerPhotoFixture(); launch(f)
        ui.onAllNodesWithText("Profile").onLast().performClick(); ui.onNodeWithText("Itinerary saya").performScrollTo().performClick()
        ui.onNodeWithText(f.base.plan.itinerary.title).performClick(); ui.onNodeWithText("Buat Trip dari itinerary").performScrollTo().performClick()
        ui.onNode(hasSetTextAction() and hasText("Nama Trip")).performScrollTo().performTextReplacement("Trip fixture baru")
        ui.onNode(hasSetTextAction() and hasText("Tanggal mulai (YYYY-MM-DD)")).performScrollTo().performTextReplacement("2026-11-14")
        ui.onNode(hasSetTextAction() and hasText("Tanggal selesai (YYYY-MM-DD)")).performScrollTo().performTextReplacement("2026-11-15")
        ui.onNode(hasText("Buat Trip dari itinerary") and hasClickAction()).performClick()
        ui.waitUntil(8_000) { f.base.tripCreateCalls.get() == 1 }
        assertEquals(0, f.base.bannerCalls.get())
    }
    @Test fun ownPublishedCardKeepsItsReaderButDoesNotOfferCopy() {
        val f = TravelerPhotoFixture(); f.profile = f.profile.copy(username = f.base.publicPlan.ownerUsername)
        launch(f); ui.onAllNodesWithText("Profile").onLast().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithText("@${f.profile.username}").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText("Community").onLast().performClick()
        ui.onNodeWithTag("public-plan-copy").assertDoesNotExist()
        ui.onNodeWithTag("public-plan-action").performScrollTo().performClick()
        ui.onNodeWithText(f.base.publicPlan.title).assertExists(); assertEquals(0, f.base.cloneCalls.get())
    }
}
