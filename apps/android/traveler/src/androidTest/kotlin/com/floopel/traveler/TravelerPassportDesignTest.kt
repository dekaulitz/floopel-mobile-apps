package com.floopel.traveler

import android.graphics.Bitmap
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.core.designsystem.component.FloopelQrCode
import com.floopel.core.designsystem.theme.FloopelTheme
import com.floopel.core.model.AppProduct
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerIdentity
import com.floopel.feature.traveler.testing.TravelerFigmaFixture
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import java.util.Locale

/** Synthetic identities only; decode the rendered pixels with an independent QR implementation. */
@RunWith(AndroidJUnit4::class)
class TravelerPassportDesignTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val fixture = TravelerFigmaFixture()
    private val shared = mutableListOf<String>()
    private val admissions = AtomicInteger()
    private val resumes = AtomicInteger()
    private val reads = AtomicInteger()
    private val selectionEvidence = JSONArray()
    private val originalLocale = Locale.getDefault()
    @After fun restoreLocale() { Locale.setDefault(originalLocale) }
    private var beforePassport: suspend () -> Unit = {}
    private val progress = TravelerPassport(1250, 8, "Penjelajah", listOf(TravelerPassportBadge("earned", "Badge simulasi", null)),
        listOf(QuestRunning("participation", "Jelajah budaya simulasi", "active", 2, 4, emptyMap())), 1000, 750,
        listOf(TravelerPassportCompletion("completed", "Jejak selesai simulasi", "quest-simulasi", true, "awarded")))

    private fun launch(scale: Float = 1f, value: TravelerPassport = progress, open: Boolean = true, language: String = "id") {
        // Compose Resources 1.11 uses Locale.current; restore the process-only override after each test.
        Locale.setDefault(Locale.forLanguageTag(language))
        fixture.base.identityState.value = fixture.base.identityState.value.copy(passport = value)
        val r = fixture.runtime
        val identity = object : TravelerIdentity by r.identity {
            override suspend fun passport() { reads.incrementAndGet(); beforePassport() }
            override suspend fun takeQuest() { admissions.incrementAndGet(); r.identity.takeQuest() }
            override suspend fun continueQuest(id: String) { resumes.incrementAndGet(); r.identity.continueQuest(id) }
        }
        val runtime = TravelerRuntime(identity, r.catalog, r.itineraries, r.distribution, r.trips, r.notifications, r.account,
            r.legal, r.registration, r.publicContent, r.savedSpots, r.questGroups, r.newOperationKey, r.startup,
            comments = r.comments, settings = r.settings)
        ui.runOnUiThread {
            WindowCompat.enableEdgeToEdge(ui.activity.window)
            WindowCompat.getInsetsController(ui.activity.window, ui.activity.window.decorView).run {
                isAppearanceLightStatusBars = true; isAppearanceLightNavigationBars = true
            }
        }
        ui.setContent {
            val density = LocalDensity.current.density
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            val localizedConfiguration = remember(configuration, language) { Configuration(configuration).apply {
                setLocales(LocaleList.forLanguageTags(language))
            } }
            val localized = remember(context, localizedConfiguration) { context.createConfigurationContext(localizedConfiguration) }
            CompositionLocalProvider(LocalDensity provides Density(density, scale), LocalContext provides localized,
                LocalConfiguration provides localizedConfiguration) {
                TravelerApp(runtime, { shared += it }, language = language, backHandler = { enabled, back -> BackHandler(enabled, back) })
            }
        }
        ui.waitUntil(8_000) { ui.onAllNodesWithTag("navigation-profile").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("navigation-profile").performClick()
        if (open) openPassport()
    }
    private fun openPassport() {
        ui.onNode(hasText("Total XP") and hasClickAction()).performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("passport-screen").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("traveler-navigation").assertDoesNotExist()
    }
    private fun ready() = ui.waitUntil(5_000) { ui.onAllNodesWithTag("floopel-qr-ready", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    private fun back() { ui.runOnUiThread { ui.activity.onBackPressedDispatcher.onBackPressed() } }
    private fun selectedGroup(group: String, active: String, vararg tags: String) {
        ui.onNodeWithTag(group).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        val items = JSONArray()
        for (tag in tags) {
            val node = ui.onNodeWithTag(tag).assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertHeightIsAtLeast(48.dp)
            if (tag == active) node.assertIsSelected() else node.assertIsNotSelected()
            val config = node.fetchSemanticsNode().config
            items.put(JSONObject().put("tag", tag).put("selected", config[SemanticsProperties.Selected]).put("role", config[SemanticsProperties.Role].toString()))
        }
        assertEquals(tags.size, ui.onAllNodes(hasAnyAncestor(hasTestTag(group)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).fetchSemanticsNodes().size)
        assertEquals(1, ui.onAllNodes(hasAnyAncestor(hasTestTag(group)) and isSelected()).fetchSemanticsNodes().size)
        selectionEvidence.put(JSONObject().put("group", group).put("active", active).put("items", items))
    }
    private fun tabs(active: String) = selectedGroup("passport-tabs", "passport-tab-$active", "passport-tab-overview", "passport-tab-badges", "passport-tab-stats")
    private fun status(active: String) = selectedGroup("passport-status-tabs", "passport-status-$active", "passport-status-running", "passport-status-completed")
    private fun saveSelectionAudit(name: String) {
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("passport-semantics-audit"))
        directory.mkdirs()
        directory.resolve("$name.json").writeText(JSONObject().put("checks", selectionEvidence).put("passport_reads", reads.get()).put("admissions", admissions.get()).toString(2))
    }
    private fun image() = ui.onNodeWithTag("passport-qr-code", useUnmergedTree = true).captureToImage().asAndroidBitmap()
    private fun decode(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels))),
            mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")).text
    }
    private fun summary(username: String = fixture.profile.username, level: Int = 8, xp: Long = 1250) {
        ready()
        val bitmap = image()
        val json = JSONObject(decode(bitmap))
        assertEquals(setOf("username", "level", "total_xp"), json.keys().asSequence().toSet())
        assertEquals(username, json.getString("username")); assertEquals(level, json.getInt("level")); assertEquals(xp, json.getLong("total_xp"))
        // The first finder pattern is seven modules wide; all four margins must be >= four modules.
        val dark = (0 until bitmap.height).flatMap { y -> (0 until bitmap.width).mapNotNull { x ->
            if ((bitmap.getPixel(x, y) and 0x00ffffff) == 0) x to y else null
        } }
        val left = dark.minOf { it.first }; val top = dark.minOf { it.second }
        val run = (left until bitmap.width).takeWhile { (bitmap.getPixel(it, top) and 0x00ffffff) == 0 }.size
        val quiet = 4 * (run / 7)
        assertTrue(quiet > 0 && left >= quiet && top >= quiet)
        assertTrue(bitmap.width - 1 - dark.maxOf { it.first } >= quiet)
        assertTrue(bitmap.height - 1 - dark.maxOf { it.second } >= quiet)
    }
    private fun capture(name: String) {
        ui.waitForIdle()
        if (ui.onAllNodesWithTag("passport-artwork-loading").fetchSemanticsNodes().isNotEmpty()) {
            ui.waitUntil(5_000) { ui.onAllNodesWithTag("passport-artwork-ready").fetchSemanticsNodes().isNotEmpty() }
        }
        val directory = requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("passport-audit"))
        directory.mkdirs()
        ui.mainClock.advanceTimeByFrame(); ui.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Compose semantics can settle a frame before the platform screenshot buffer is presented.
        Thread.sleep(100)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        directory.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun overviewUsesAccountValuesAndAScannable128DpSlotWithTheFigmaGutters() {
        launch(); summary()
        val density = ui.activity.resources.displayMetrics.density
        val card = ui.onNodeWithTag("passport-card").fetchSemanticsNode().boundsInRoot
        val qr = ui.onNodeWithTag("passport-qr-preview").fetchSemanticsNode().boundsInRoot
        assertEquals(20 * density, card.left, 1f); assertEquals(128 * density, qr.width, 1f)
        assertEquals(120 * density, image().width.toFloat(), 1f)
        ui.onNodeWithText("1.250 XP").assertExists(); ui.onNodeWithText("Jelajah budaya simulasi").assertExists()
        capture("passport-overview"); assertEquals(0, admissions.get())
    }
    @Test fun expandedQrDecodesTheSameSummaryAndBothBackActionsReturnToPassport() {
        launch(); ready()
        val bar = ui.onNodeWithTag("traveler-top-bar").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithTag("passport-enlarge").performScrollTo().performClick(); summary()
        ui.onNodeWithTag("traveler-top-bar").assertIsDisplayed()
        assertEquals(bar, ui.onNodeWithTag("traveler-top-bar").fetchSemanticsNode().boundsInRoot)
        val density = ui.activity.resources.displayMetrics.density
        assertEquals(248 * density, ui.onNodeWithTag("passport-qr-expanded").fetchSemanticsNode().boundsInRoot.width, 1f)
        assertEquals(240 * density, image().width.toFloat(), 1f)
        ui.onNodeWithText("Kembali ke Passport").assertIsDisplayed(); capture("passport-qr-expanded")
        ui.onNodeWithText("Kembali ke Passport").performClick(); ui.onNodeWithTag("passport-qr-preview").assertExists()
        ui.onNodeWithTag("passport-enlarge").performClick(); back(); ui.onNodeWithTag("passport-qr-preview").assertExists()
    }
    @Test fun zeroXpIsAValidPassportAndDiscoveryDoesNotAdmitAQuest() {
        launch(value = TravelerPassport(0, 1, "Penjelajah", emptyList(), emptyList())); summary(level = 1, xp = 0)
        val bar = ui.onNodeWithTag("traveler-top-bar").fetchSemanticsNode().boundsInRoot
        ui.onNodeWithText("Mulai jejak pertamamu").performScrollTo().assertIsDisplayed(); capture("passport-empty")
        ui.onNodeWithTag("traveler-top-bar").assertIsDisplayed()
        assertEquals(bar, ui.onNodeWithTag("traveler-top-bar").fetchSemanticsNode().boundsInRoot)
        ui.onNodeWithText("Temukan Quest").performScrollTo().performClick(); ui.onNodeWithTag("passport-screen").assertDoesNotExist()
        assertEquals(0, admissions.get()); assertEquals(0, resumes.get())
    }
    @Test fun tabsBadgeFocusAndStatisticsPreserveTheCardAndDoNotRefetchPassport() {
        launch(); ready(); val initialReads = reads.get()
        ui.onNodeWithTag("passport-tab-badges").performScrollTo().performClick()
        ui.onNodeWithTag("passport-badge-earned").performScrollTo().assertExists(); capture("passport-badges")
        ui.onNodeWithTag("passport-badge-earned").performClick(); ui.onNodeWithText("Tercatat di Passport").assertExists(); capture("passport-badge-focus")
        back(); ui.onNodeWithTag("passport-badge-earned").assertExists()
        ui.onNodeWithTag("passport-tab-stats").performScrollTo().performClick(); ui.onNodeWithText("Pencapaianmu").performScrollTo().assertExists()
        ui.onNodeWithText("1 badge diraih").assertExists(); capture("passport-stats"); assertEquals(initialReads, reads.get())
    }
    @Test fun completionSharingUsesTheOwnerHistoryAndContinueDoesNotTakeAnotherQuest() {
        launch(); ready(); ui.onNodeWithText("Selesai").performScrollTo().performClick()
        ui.onNodeWithText("Jejak selesai simulasi").performScrollTo().assertIsDisplayed(); capture("passport-completed")
        ui.onNodeWithText("Bagikan pencapaian").performScrollTo().performClick()
        assertEquals(listOf("Quest selesai: Jejak selesai simulasi"), shared)
        ui.onNodeWithText("Berjalan").performScrollTo().performClick()
        ui.onNodeWithText("Lanjutkan Quest").performScrollTo().performClick()
        ui.waitUntil(5_000) { resumes.get() > 0 }; assertEquals(0, admissions.get())
    }
    @Test fun aCompletionWithoutSharePermissionDoesNotExposeTheShareAction() {
        launch(value = progress.copy(completions = progress.completions.map { it.copy(shareAvailable = false) }))
        ready(); ui.onNodeWithText("Selesai").performScrollTo().performClick()
        ui.onNodeWithText("Jejak selesai simulasi").assertExists(); ui.onNodeWithText("Bagikan pencapaian").assertDoesNotExist()
        assertTrue(shared.isEmpty())
    }
    @Test fun loadingAndApiFailureUseTheFigmaIllustrationsAndRetryReadsOnly() {
        launch(open = false)
        val gate = CompletableDeferred<Unit>()
        beforePassport = { fixture.base.identityState.value = fixture.base.identityState.value.copy(busy = true); gate.await() }
        openPassport(); ui.onNodeWithText("Memuat Passport…").assertExists(); capture("passport-loading")
        gate.complete(Unit)
        ui.runOnIdle { fixture.base.identityState.value = fixture.base.identityState.value.copy(busy = false, failure = IdentityFailure(503, null, null)) }
        ui.onNodeWithText("Passport belum termuat").assertExists(); ui.onNodeWithTag("passport-qr-code").assertDoesNotExist(); capture("passport-failed")
        beforePassport = { fixture.base.identityState.value = fixture.base.identityState.value.copy(failure = null) }
        ui.onNodeWithText("Coba lagi").performClick(); summary(); assertEquals(0, admissions.get())
    }
    @Test fun failedIdentityKeepsProgressButHidesTheBarcodeUntilProfileRetrySucceeds() {
        launch(open = false); fixture.profileResult = CatalogResult.NetworkError; openPassport()
        ui.onNodeWithText("Muat ulang identitas").performScrollTo().assertIsDisplayed()
        ui.onNodeWithTag("passport-qr-code").assertDoesNotExist(); ui.onNodeWithText("1.250 XP").assertExists(); capture("passport-identity-error")
        fixture.profileResult = CatalogResult.Ready(fixture.profile)
        ui.onNodeWithText("Muat ulang identitas").performClick(); summary()
    }
    @Test fun changedProgressReplacesTheRenderedQrRatherThanReusingTheOldMatrix() {
        launch(); summary()
        ui.runOnIdle { fixture.base.identityState.value = fixture.base.identityState.value.copy(passport = progress.copy(xp = 0, level = 1)) }
        ui.waitUntil(5_000) { runCatching { JSONObject(decode(image())).getLong("total_xp") == 0L }.getOrDefault(false) }
        summary(level = 1, xp = 0)
    }
    @Test fun longNamesAndOneHundredThirtyPercentTextKeepAllTabsReachable() {
        fixture.profileResult = CatalogResult.Ready(fixture.profile.copy(firstName = "Traveler dengan nama yang panjang", lastName = "Simulasi aksesibilitas"))
        launch(1.3f); summary(); ui.onNodeWithTag("passport-tab-stats").performScrollTo().performClick()
        ui.onNodeWithText("Pencapaianmu").performScrollTo().assertIsDisplayed(); tabs("stats"); capture("passport-font-130"); saveSelectionAudit("font-130")
    }
    @Test fun doubledTextKeepsTheExpandedFooterAndTabsReachable() {
        launch(2f); ready(); ui.onNodeWithTag("passport-enlarge").performScrollTo().performClick(); summary()
        ui.onNodeWithText("Kembali ke Passport").assertIsDisplayed(); capture("passport-qr-font-200")
        ui.onNodeWithText("Kembali ke Passport").performClick(); ui.onNodeWithTag("passport-tab-badges").performScrollTo().performClick()
        ui.onNodeWithTag("passport-badge-earned").performScrollTo().assertIsDisplayed(); tabs("badges"); saveSelectionAudit("font-200")
    }
    @Test fun passportSelectionSemanticsRemainExclusiveAcrossSwitchesAndBackWithoutExtraReads() {
        launch(); ready(); val initialReads = reads.get()
        tabs("overview"); status("running")
        ui.onNodeWithTag("passport-status-completed").performScrollTo().performClick(); status("completed")
        ui.onNodeWithTag("passport-tab-badges").performScrollTo().performClick(); tabs("badges")
        ui.onNodeWithTag("passport-status-tabs").assertDoesNotExist()
        ui.onNodeWithTag("passport-badge-earned").performScrollTo().performClick(); back(); tabs("badges")
        ui.onNodeWithTag("passport-tab-stats").performScrollTo().performClick(); tabs("stats")
        ui.onNodeWithTag("passport-enlarge").performScrollTo().performClick(); back(); tabs("stats")
        ui.onNodeWithTag("passport-tab-overview").performScrollTo().performClick(); tabs("overview"); status("completed")
        ui.onNodeWithTag("passport-status-running").performScrollTo().performClick(); status("running")
        assertEquals(initialReads, reads.get()); assertEquals(0, admissions.get()); assertEquals(0, resumes.get())
        saveSelectionAudit("selection-id")
    }
    @Test fun englishPassportExposesTheSameLocalizedExclusiveSelectionGroups() {
        launch(language = "en"); ready(); val initialReads = reads.get()
        ui.onNodeWithText("Overview").assertExists(); ui.onNodeWithText("In progress").assertExists()
        tabs("overview"); status("running")
        ui.onNodeWithTag("passport-status-completed").performScrollTo().performClick(); status("completed")
        ui.onNodeWithTag("passport-tab-badges").performScrollTo().performClick(); tabs("badges")
        ui.onNodeWithTag("passport-badge-earned").performScrollTo().performClick(); back(); tabs("badges")
        ui.onNodeWithTag("passport-tab-overview").performScrollTo().performClick(); tabs("overview"); status("completed")
        assertEquals(initialReads, reads.get()); assertEquals(0, admissions.get()); saveSelectionAudit("selection-en")
    }
    @Test fun theSharedQrEncoderRoundTripsUnicodeAndPayloadChangesWithoutAPlatformProvider() {
        var payload by mutableStateOf("{\"username\":\"旅人_simulasi\",\"level\":1,\"total_xp\":0}")
        ui.setContent { FloopelTheme(AppProduct.TRAVELER) {
            FloopelQrCode(payload, "QR simulasi", Modifier.size(120.dp).testTag("passport-qr-code")) { error("QR generation failed") }
        } }
        ready(); assertEquals(payload, decode(image()))
        ui.runOnIdle { payload = "{\"username\":\"traveler_2\",\"level\":10,\"total_xp\":9223372036854775807}" }
        ui.waitUntil(5_000) { runCatching { decode(image()) == payload }.getOrDefault(false) }
        assertEquals(payload, decode(image()))
    }
}
