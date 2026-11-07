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
import kotlin.math.abs
import kotlin.time.Instant

/** Native recipient consumer and retained list. Production PUT/journal/VM are tested on host. */
@RunWith(AndroidJUnit4::class)
class TravelerQuestGroupInvitationDesignTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private val originalLocale=Locale.getDefault()
    @After fun restoreLocale() { Locale.setDefault(originalLocale) }
    private val f=TravelerQuestDesignFixture()
    private val recipientActor="recipient-fixture"
    private val groupId="22222222-2222-4222-8222-222222222222"
    private val pages=CopyOnWriteArrayList<Int>()
    private val puts=AtomicInteger()
    private val groupReads=AtomicInteger()
    @Volatile private var status="pending"
    @Volatile private var recipient=recipientActor
    @Volatile private var inviter:String?="pengundang"
    @Volatile private var readerTitle="Quest undangan simulasi"
    @Volatile private var failReader=false
    private var readerGate:CompletableDeferred<Unit>?=null
    private val readerScale=mutableFloatStateOf(1f)
    private fun id(n:Int)="33333333-3333-4333-8333-${n.toString().padStart(12,'0')}"
    private fun invitation(n:Int,reader:Boolean=false)=QuestGroupInvitation(id(n),groupId,f.card.id,
        if(reader || n==21 && status!="pending") readerTitle else "Invitation $n",recipient,"manager-fixture",if(n==21) status else "pending",
        Instant.parse("2026-11-08T00:00:00Z"),null,"penerima",inviter)
    private fun launch(language:String="id",scale:Float=1f,ready:Boolean=true) {
        Locale.setDefault(Locale.forLanguageTag(language)); f.readyReader=f.readyReader.copy(card=f.card.copy(mode="group"))
        readerScale.floatValue=if(scale<=1.3f) scale else 1f
        val r=f.runtime
        val groups=object:TravelerQuestGroupRepository by r.questGroups {
            override suspend fun groups(page:Int,locale:String,questId:String?)=CatalogResult.Ready(QuestGroupBatch<QuestGroup>(emptyList(),1,0,0))
            override suspend fun invitations(page:Int,locale:String,groupId:String?):CatalogResult<QuestGroupBatch<QuestGroupInvitation>> {
                pages+=page; return CatalogResult.Ready(QuestGroupBatch(((page-1)*20+1..minOf(page*20,21)).map { invitation(it) },page,21,2))
            }
            override suspend fun invitation(id:String,locale:String):CatalogResult<QuestGroupInvitation> {
                readerGate?.await()
                return if(failReader) CatalogResult.NetworkError else CatalogResult.Ready(invitation(id.substringAfterLast('-').toInt(),true))
            }
            override suspend fun decideInvitation(id:String,accept:Boolean,locale:String,key:String):CatalogResult<QuestGroupInvitation> {
                puts.incrementAndGet(); status=if(accept) "accepted" else "declined"
                return CatalogResult.Ready(invitation(21,true))
            }
            override suspend fun group(id:String,locale:String):CatalogResult<QuestGroup> {
                groupReads.incrementAndGet(); return CatalogResult.HttpError(403)
            }
        }
        val account=object:TravelerAccountRepository by r.account {
            override suspend fun profile()=CatalogResult.Ready(PersonalProfile("Fixture","Recipient","penerima","ID",profileId=recipientActor))
        }
        val runtime=TravelerRuntime(r.identity,r.catalog,r.itineraries,r.distribution,r.trips,r.notifications,account,
            r.legal,r.registration,r.publicContent,r.savedSpots,groups,r.newOperationKey,r.startup)
        ui.runOnUiThread {
            WindowCompat.enableEdgeToEdge(ui.activity.window)
            WindowCompat.getInsetsController(ui.activity.window,ui.activity.window.decorView).run { isAppearanceLightStatusBars=true; isAppearanceLightNavigationBars=true }
        }
        ui.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,readerScale.floatValue)) {
            TravelerApp(runtime,{},language=language,backHandler={enabled,back -> BackHandler(enabled,back)})
        } }
        val quests=if(language=="id") "Quest" else "Quests"
        ui.waitUntil(8_000) { ui.onAllNodesWithText(quests).fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText(quests).onFirst().performClick(); ui.onNodeWithText(f.card.title).performScrollTo().performClick()
        ui.onNodeWithText(if(language=="id") "Kelompok Quest" else "Quest group").performScrollTo().performClick()
        ui.onNodeWithText(if(language=="id") "Undangan kelompok" else "Group invitations").performScrollTo().performClick()
        ui.onNodeWithTag("quest-group-invitations-list").performScrollToKey("next-page")
        ui.waitUntil(5_000) { pages.contains(2) }
        ui.onNodeWithTag("quest-group-invitations-list").performScrollToKey(id(21))
        ui.onNodeWithText(if(status=="pending") "Invitation 21" else readerTitle).performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("quest-group-invitation-reader").fetchSemanticsNodes().isNotEmpty() }
        // Scale only the requested reader; entry screens are outside this font-scale proof.
        ui.runOnUiThread { readerScale.floatValue=scale }; ui.waitForIdle()
        if(ready) ready()
    }
    private fun ready() {
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("quest-group-invitation-quest").fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
    }
    private fun capture(name:String) {
        ui.waitForIdle()
        val folder=requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("quest-group-invitation-audit")); folder.mkdirs()
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        folder.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK) }
    @Test fun pendingRecipientUsesExactMailSlotHierarchySpacingAndTwoReachableActions() {
        launch()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("quest-group-invitation-artwork-ready").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("quest-group-invitation-artwork-slot").assertWidthIsEqualTo(360.dp).assertHeightIsEqualTo(96.dp)
        ui.onNodeWithTag("quest-group-invitation-artwork-ready").assertWidthIsEqualTo(144.dp).assertHeightIsEqualTo(96.dp)
        val tags=listOf("artwork-slot","quest","context","inviter","note","accept","decline")
        val bounds=tags.map { ui.onNodeWithTag("quest-group-invitation-$it").getUnclippedBoundsInRoot() }
        bounds.forEach { assertTrue("20dp gutter: $it",abs(it.left.value-20f)<.6f) }
        bounds.zipWithNext().forEach { (a,b) -> assertTrue("16dp gap: $a $b",abs(b.top.value-a.bottom.value-16f)<.6f) }
        ui.onNodeWithText("@pengundang mengundangmu").assertExists()
        ui.onNodeWithTag("quest-group-invitation-accept").assertHeightIsAtLeast(48.dp).assertIsEnabled()
        ui.onNodeWithTag("quest-group-invitation-decline").assertHeightIsAtLeast(48.dp).assertIsEnabled()
        capture("pending-id"); back(); assertEquals(0,puts.get()); assertEquals(0,groupReads.get()); assertEquals(listOf(1,2),pages.toList())
    }
    private fun decline(language:String,scale:Float) {
        launch(language,scale)
        ui.onNodeWithTag("quest-group-invitation-decline").performScrollTo().assertIsEnabled()
        if(language=="en") capture("pending-en-font130")
        ui.onNodeWithTag("quest-group-invitation-decline").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("quest-group-invitation-reader").fetchSemanticsNodes().isEmpty() }
        ui.onNode(hasText(if(language=="id") "Ditolak" else "Declined") and hasAnyAncestor(hasTestTag("quest-group-invitation-${id(21)}"))).assertIsDisplayed()
        capture(if(language=="id") "declined-list-id" else "declined-list-en")
        assertEquals(listOf(1,2),pages.toList()); assertEquals(1,puts.get()); assertEquals(0,groupReads.get())
    }
    @Test fun acknowledgedDeclineReturnsToItem21WithIndonesianStatus() { decline("id",1f) }
    @Test fun englishAt130PercentDeclinesAndRetainsTheOlderRow() { decline("en",1.3f) }
    @Test fun missingInviterAt200PercentKeepsNeutralCopyAndScrollableActions() {
        inviter=null; readerTitle="A long fixture Quest title describing a journey through several neighborhoods"
        launch("en",2f)
        ui.onNodeWithTag("quest-group-invitation-inviter").assertTextEquals("Group invitation")
        val toolbar=ui.onNodeWithTag("traveler-top-bar").getUnclippedBoundsInRoot().top
        ui.onNodeWithTag("quest-group-invitation-decline").performScrollTo().assertIsDisplayed().assertIsEnabled()
        assertEquals(toolbar,ui.onNodeWithTag("traveler-top-bar").getUnclippedBoundsInRoot().top)
        capture("missing-inviter-en-font200"); back(); assertEquals(0,puts.get()); assertEquals(0,groupReads.get())
    }
    @Test fun loadingHasNoDecisionAndReadyDataDoesNotFetchTheGroup() {
        readerGate=CompletableDeferred(); launch(ready=false)
        ui.onNodeWithTag("quest-group-invitation-accept").assertDoesNotExist(); capture("loading")
        requireNotNull(readerGate).complete(Unit); ready()
        ui.onNodeWithTag("quest-group-invitation-accept").assertIsEnabled(); assertEquals(0,puts.get()); assertEquals(0,groupReads.get())
    }
    @Test fun readFailureRetryDoesNotFabricateAnInvitationOrSendADecision() {
        failReader=true; launch(ready=false)
        ui.onNodeWithTag("quest-group-invitation-accept").assertDoesNotExist(); ui.onNodeWithText("Coba lagi").assertExists(); capture("error")
        failReader=false; ui.onNodeWithText("Coba lagi").performScrollTo().performClick(); ready()
        ui.onNodeWithTag("quest-group-invitation-decline").assertIsEnabled(); assertEquals(0,puts.get()); assertEquals(0,groupReads.get())
    }
    private fun readOnly(expected:String,name:String) {
        launch(); ui.onNodeWithTag("quest-group-invitation-status").assertTextEquals(expected)
        ui.onNodeWithTag("quest-group-invitation-accept").assertDoesNotExist(); ui.onNodeWithTag("quest-group-invitation-decline").assertDoesNotExist()
        capture(name); assertEquals(0,puts.get()); assertEquals(0,groupReads.get())
    }
    @Test fun acceptedInvitationDoesNotOfferAnotherDecisionOrAutoOpenTheGroup() { status="accepted"; readOnly("Diterima","accepted") }
    @Test fun declinedInvitationRemainsReadOnly() { status="declined"; readOnly("Ditolak","declined") }
    @Test fun managerReadingAnotherRecipientsPendingInvitationHasNoRecipientControls() {
        recipient="other-recipient"; readOnly("Menunggu jawaban","manager-readonly")
        ui.onNodeWithTag("quest-group-invitation-inviter").assertDoesNotExist()
    }
}
