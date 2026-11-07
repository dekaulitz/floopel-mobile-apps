package com.floopel.traveler

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.floopel.feature.traveler.TravelerApp
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.testing.TravelerQuestDesignFixture
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant
import kotlin.math.abs

/** Matched actor/roster fixtures, never a real invite, role transfer or disband. */
@RunWith(AndroidJUnit4::class)
class TravelerQuestGroupManagementDesignTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private val originalLocale=Locale.getDefault()
    @After fun restoreLocale() {Locale.setDefault(originalLocale)}
    private val f=TravelerQuestDesignFixture()
    private val actor="actor-fixture"
    private val other="other-manager"
    private val groupId="22222222-2222-4222-8222-222222222222"
    private val selfId="33333333-3333-4333-8333-000000000001"
    @Volatile private var manager=actor
    @Volatile private var extraMember=false
    @Volatile private var available=true
    @Volatile private var failGroup=false
    @Volatile private var unknownInvite=false
    private var limit=4
    private var selfProgress:QuestMemberProgress?=QuestMemberProgress("not_started",0,4,null)
    private var groupGate:CompletableDeferred<Unit>?=null
    private val scale=mutableFloatStateOf(1f)
    private val writes=AtomicInteger()
    private val invitationFilters=CopyOnWriteArrayList<String?>()
    private val time=Instant.parse("2026-11-08T00:00:00Z")
    private fun members():List<QuestGroupMember> = buildList {
        add(QuestGroupMember(selfId,actor,"Rani simulasi",manager==actor,time,if(available) selfProgress else null))
        if(extraMember || manager!=actor) add(QuestGroupMember("33333333-3333-4333-8333-000000000002",other,"Faris simulasi",manager==other,time,
            if(available) QuestMemberProgress("active",2,4,null) else null))
    }
    private fun group()=QuestGroup(groupId,f.card.id,f.card.title.takeIf {available},manager,available,members().size,limit,time,f.card.slug.takeIf {available})
    private fun launch(language:String="id",font:Float=1f,ready:Boolean=true) {
        Locale.setDefault(Locale.forLanguageTag(language)); scale.floatValue=if(font<=1.3f) font else 1f
        f.readyReader=f.readyReader.copy(card=f.card.copy(mode="group")); val r=f.runtime
        val groups=object:TravelerQuestGroupRepository by r.questGroups {
            override suspend fun groups(page:Int,locale:String,questId:String?)=CatalogResult.Ready(QuestGroupBatch(listOf(group()),1,1,1))
            override suspend fun group(id:String,locale:String):CatalogResult<QuestGroup> {
                assertEquals(groupId,id); groupGate?.await()
                return if(failGroup) CatalogResult.NetworkError else CatalogResult.Ready(group())
            }
            override suspend fun members(groupId:String,page:Int):CatalogResult<QuestGroupBatch<QuestGroupMember>> {
                assertEquals(this@TravelerQuestGroupManagementDesignTest.groupId,groupId)
                val rows=members(); return CatalogResult.Ready(QuestGroupBatch(rows,1,rows.size,1))
            }
            override suspend fun invitations(page:Int,locale:String,groupId:String?):CatalogResult<QuestGroupBatch<QuestGroupInvitation>> {
                invitationFilters+=groupId; return CatalogResult.Ready(QuestGroupBatch(emptyList(),1,0,0))
            }
            override suspend fun invite(groupId:String,username:String,locale:String,key:String):CatalogResult<QuestGroupInvitation> {
                writes.incrementAndGet()
                return if(unknownInvite) CatalogResult.NetworkError else CatalogResult.Ready(QuestGroupInvitation("44444444-4444-4444-8444-444444444444",groupId,f.card.id,
                    f.card.title,"guest-profile",actor,"pending",time,null,username,"fixture_manager"))
            }
            override suspend fun transferManager(groupId:String,profileId:String,locale:String,key:String):CatalogResult<QuestGroup> {
                writes.incrementAndGet(); manager=profileId; return CatalogResult.Ready(group())
            }
            override suspend fun disbandGroup(groupId:String,key:String):CatalogResult<Unit> {writes.incrementAndGet(); return CatalogResult.Ready(Unit)}
            override suspend fun leaveGroup(groupId:String,key:String):CatalogResult<Unit> {writes.incrementAndGet(); return CatalogResult.Ready(Unit)}
        }
        val account=object:TravelerAccountRepository by r.account {
            override suspend fun profile()=CatalogResult.Ready(PersonalProfile("Rani","Simulasi","rani_simulasi","ID",profileId=actor))
        }
        val runtime=TravelerRuntime(r.identity,r.catalog,r.itineraries,r.distribution,r.trips,r.notifications,account,r.legal,r.registration,
            r.publicContent,r.savedSpots,groups,r.newOperationKey,r.startup)
        ui.runOnUiThread {
            WindowCompat.enableEdgeToEdge(ui.activity.window)
            WindowCompat.getInsetsController(ui.activity.window,ui.activity.window.decorView).run {isAppearanceLightStatusBars=true;isAppearanceLightNavigationBars=true}
        }
        ui.setContent {CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,scale.floatValue)) {
            TravelerApp(runtime,{},language=language,backHandler={enabled,back -> BackHandler(enabled,back)})
        }}
        val quests=if(language=="id") "Quest" else "Quests"
        ui.waitUntil(8_000) {ui.onAllNodesWithText(quests).fetchSemanticsNodes().isNotEmpty()}
        ui.onAllNodesWithText(quests).onFirst().performClick();ui.onNodeWithText(f.card.title).performScrollTo().performClick()
        val groupsLabel=if(language=="id") "Kelompok Quest" else "Quest group"
        ui.onNodeWithText(groupsLabel).performScrollTo().performClick()
        ui.onAllNodesWithText(if(available) f.card.title else groupsLabel).onLast().performScrollTo().performClick()
        ui.waitUntil(5_000) {ui.onAllNodesWithTag("quest-group-overview").fetchSemanticsNodes().isNotEmpty()}
        if(ready) readyOverview()
    }
    private fun readyOverview() {
        ui.waitUntil(5_000) {ui.onAllNodesWithTag("quest-group-member-$selfId").fetchSemanticsNodes().isNotEmpty()}
        ui.waitForIdle()
    }
    private fun manage() {
        ui.onNodeWithTag("quest-group-manage").performScrollTo().performClick()
        ui.waitUntil(5_000) {ui.onAllNodesWithTag("quest-group-username").fetchSemanticsNodes().isNotEmpty()}
    }
    private fun back() {InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);ui.waitForIdle()}
    private fun backFromManagement() {
        back()
        if(ui.onAllNodesWithTag("quest-group-management").fetchSemanticsNodes().isNotEmpty()) back() // first Back may close the IME
        readyOverview()
    }
    private fun capture(name:String) {
        ui.waitForIdle();val folder=requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("quest-group-management-audit"));folder.mkdirs()
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        folder.resolve("$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun managerOverviewHasTheCanonicalCardAndASeparateManagementEntry() {
        launch()
        ui.onNodeWithTag("quest-group-member-$selfId").assertWidthIsEqualTo(360.dp).assertHeightIsEqualTo(84.dp)
        val title=ui.onNodeWithTag("quest-group-title").getUnclippedBoundsInRoot(); assertTrue(abs(title.left.value-20f)<.6f)
        ui.onNodeWithTag("quest-group-count").assertTextEquals("1 dari 4 anggota · Privat")
        ui.onNodeWithTag("quest-group-username").assertDoesNotExist();ui.onNodeWithTag("quest-group-disband").assertDoesNotExist()
        capture("manager-overview");manage()
        ui.onNodeWithTag("quest-group-invite").assertIsNotEnabled();ui.onNodeWithTag("quest-group-sent-invitations").assertHeightIsAtLeast(48.dp)
        ui.onNodeWithTag("quest-group-transfer").assertIsEnabled();capture("management-empty")
        backFromManagement();assertEquals(0,writes.get())
    }
    @Test fun memberOverviewUsesIndividualProgressAndHasNoManagementForm() {
        manager=other;launch()
        ui.onNodeWithText("Rani simulasi · Anggota · Kamu").assertExists();ui.onNodeWithText("Faris simulasi · Pengelola").assertExists()
        ui.onNodeWithText("2 dari 4 aktivitas").assertExists();ui.onNodeWithTag("quest-group-manage").assertDoesNotExist()
        ui.onNodeWithTag("quest-group-leave").assertIsEnabled();capture("member-overview");assertEquals(0,writes.get())
    }
    @Test fun nullProgressIsUnknownAndCapacityComesFromTheGroupInsteadOfTripLimits() {
        selfProgress=null;extraMember=true;limit=8;launch()
        ui.onNodeWithTag("quest-group-count").assertTextEquals("2 dari 8 anggota · Privat")
        ui.onNodeWithText("Progres belum tersedia").assertExists();ui.onNodeWithText("Belum mengambil Quest").assertDoesNotExist()
        capture("null-progress-capacity");assertEquals(0,writes.get())
    }
    @Test fun draftSentInvitationsBackAndCancelledDisbandKeepTheSameOverview() {
        launch();manage();ui.onNodeWithTag("quest-group-username").performTextInput("guest_b")
        ui.onNodeWithTag("quest-group-invite").assertIsEnabled();capture("management-input")
        ui.onNodeWithTag("quest-group-sent-invitations").performScrollTo().performClick()
        ui.waitUntil(5_000) {ui.onAllNodesWithTag("quest-group-invitations-list").fetchSemanticsNodes().isNotEmpty()};back()
        ui.waitUntil(5_000) {ui.onAllNodesWithTag("quest-group-username").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("quest-group-username").assertTextContains("guest_b")
        ui.onNodeWithTag("quest-group-disband").performScrollTo().performClick();ui.onNodeWithText("Batal").performClick();readyOverview()
        ui.onNodeWithTag("quest-group-title").assertTextEquals(f.card.title);capture("cancelled-to-overview")
        assertEquals(listOf(groupId),invitationFilters.toList());assertEquals(0,writes.get())
    }
    @Test fun refreshedRoleChangeRemovesManagementControls() {
        extraMember=true;launch();manage();ui.onNodeWithTag("quest-group-sent-invitations").performScrollTo().performClick()
        ui.waitUntil(5_000) {ui.onAllNodesWithTag("quest-group-invitations-list").fetchSemanticsNodes().isNotEmpty()};manager=other;back()
        ui.waitUntil(5_000) {ui.onAllNodesWithText("Pengelolaan hanya tersedia untuk pengelola aktif.").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("quest-group-username").assertDoesNotExist();ui.onNodeWithTag("quest-group-disband").assertDoesNotExist()
        capture("role-changed-readonly");back();readyOverview();ui.onNodeWithTag("quest-group-manage").assertDoesNotExist();assertEquals(0,writes.get())
    }
    @Test fun unavailableGroupDoesNotOfferManagement() {
        available=false;launch();ui.onNodeWithTag("quest-group-manage").assertDoesNotExist();ui.onNodeWithTag("quest-group-leave").assertDoesNotExist()
        capture("unavailable");assertEquals(0,writes.get())
    }
    @Test fun loadingThenReadFailureRetryDoesNotInventManagementPermission() {
        groupGate=CompletableDeferred();launch(ready=false)
        ui.onNodeWithTag("quest-group-manage").assertDoesNotExist();capture("loading")
        failGroup=true;requireNotNull(groupGate).complete(Unit)
        ui.waitUntil(5_000) {ui.onAllNodesWithText("Coba lagi").fetchSemanticsNodes().isNotEmpty()};capture("error")
        failGroup=false;ui.onNodeWithText("Coba lagi").performScrollTo().performClick();readyOverview()
        ui.onNodeWithTag("quest-group-manage").assertIsEnabled();assertEquals(0,writes.get())
    }
    @Test fun englishAt130PercentCanCancelTransferBackToTheSameGroup() {
        extraMember=true;launch("en",1.3f);manage();ui.onNodeWithTag("quest-group-transfer").performScrollTo().performClick()
        ui.onNodeWithText("Cancel").performClick();readyOverview();ui.onNodeWithTag("quest-group-title").assertTextEquals(f.card.title)
        capture("overview-en-font130");assertEquals(0,writes.get())
    }
    @Test fun managementAt200PercentKeepsTheInputTargetsScrollAndPinnedToolbarReachable() {
        launch("en");manage();ui.runOnUiThread {scale.floatValue=2f};ui.waitForIdle()
        ui.onNodeWithTag("quest-group-username").performScrollTo().performTextInput("guest_b")
        val top=ui.onNodeWithTag("traveler-top-bar").getUnclippedBoundsInRoot().top
        ui.onNodeWithTag("quest-group-disband").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertEquals(top,ui.onNodeWithTag("traveler-top-bar").getUnclippedBoundsInRoot().top)
        capture("management-en-font200");backFromManagement();assertEquals(0,writes.get())
    }
    @Test fun unknownInviteUsesTheExistingRecoveryAndBackKeepsTheDraft() {
        launch();manage();ui.onNodeWithTag("quest-group-username").performTextInput("guest_b");unknownInvite=true
        ui.onNodeWithTag("quest-group-invite").performScrollTo().performClick()
        ui.waitUntil(5_000) {ui.onAllNodesWithTag("quest-group-mutation-recovery").fetchSemanticsNodes().isNotEmpty()};capture("unknown-invite")
        back();ui.waitUntil(5_000) {ui.onAllNodesWithTag("quest-group-username").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("quest-group-username").assertTextContains("guest_b");assertEquals(1,writes.get())
    }
}
