package com.floopel.traveler

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
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
import com.floopel.feature.traveler.domain.repository.TravelerQuestGroupRepository
import com.floopel.feature.traveler.domain.repository.TravelerAccountRepository
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

/** Domain-boundary native UI only; actual journal/HTTP/clock sequences are covered by host tests. */
@RunWith(AndroidJUnit4::class)
class TravelerQuestGroupDesignTest {
    @get:Rule val ui=createAndroidComposeRule<ComponentActivity>()
    private val locale=Locale.getDefault()
    @After fun restoreLocale() { Locale.setDefault(locale) }
    private val f=TravelerQuestDesignFixture()
    private val group=QuestGroup("22222222-2222-4222-8222-222222222222",f.card.id,"Group fixture","owner",
        true,1,4,Instant.parse("2026-11-08T00:00:00Z"))
    private val posts=AtomicInteger()
    private val reviews=AtomicInteger()
    private val releases=AtomicInteger()
    private val pages=CopyOnWriteArrayList<Pair<String?,Int>>()
    @Volatile private var created=false
    @Volatile private var pending=false
    @Volatile private var reviewed=false
    @Volatile private var failReview=false
    @Volatile private var failAppend=false
    private var reviewGate: CompletableDeferred<Unit>?=null
    private var total=21
    private fun id(n:Int)="33333333-3333-4333-8333-${n.toString().padStart(12,'0')}"
    private fun invitation(n:Int)=QuestGroupInvitation(id(n),group.id,group.questId,"Invitation $n","guest-profile",group.managerProfileId,
        "pending",group.createdAt,null,"guest_b","fixture_owner")
    private fun launch(language:String="id",scale:Float=1f) {
        Locale.setDefault(Locale.forLanguageTag(language)); f.readyReader=f.readyReader.copy(card=f.card.copy(mode="group"))
        val r=f.runtime
        val groups=object : TravelerQuestGroupRepository by r.questGroups {
            override suspend fun groups(page:Int,locale:String,questId:String?)=CatalogResult.Ready(QuestGroupBatch(if(created) listOf(group) else emptyList(),1,if(created) 1 else 0,if(created) 1 else 0))
            override suspend fun group(id:String,locale:String)=CatalogResult.Ready(group)
            override suspend fun members(groupId:String,page:Int)=CatalogResult.Ready(QuestGroupBatch<QuestGroupMember>(emptyList(),1,0,0))
            override suspend fun createGroup(questId:String,locale:String,key:String):CatalogResult<QuestGroup> {
                posts.incrementAndGet(); created=true; pending=true; return CatalogResult.NetworkError
            }
            override suspend fun invite(groupId:String,username:String,locale:String,key:String):CatalogResult<QuestGroupInvitation> {
                if(pending) return CatalogResult.HttpError(409,code="PENDING_OPERATION")
                posts.incrementAndGet(); return CatalogResult.Ready(invitation(1))
            }
            override suspend fun reviewPendingMutation(locale:String):CatalogResult<QuestGroupRecoveryReview> {
                reviews.incrementAndGet(); reviewGate?.await(); reviewed=!failReview
                return if(failReview) CatalogResult.NetworkError else CatalogResult.Ready(QuestGroupRecoveryReview(group.managerProfileId,listOf(group),emptyList()))
            }
            override suspend fun startNewAction():CatalogResult<Unit> {
                assertTrue(reviewed); releases.incrementAndGet(); reviewed=false; pending=false; return CatalogResult.Ready(Unit)
            }
            override suspend fun invitations(page:Int,locale:String,groupId:String?):CatalogResult<QuestGroupBatch<QuestGroupInvitation>> {
                pages+=groupId to page
                if(page==2 && failAppend) return CatalogResult.NetworkError
                return CatalogResult.Ready(QuestGroupBatch(((page-1)*20+1..minOf(page*20,total)).map(::invitation),page,total,(total+19)/20))
            }
            override suspend fun invitation(id:String,locale:String)=CatalogResult.Ready(invitation(id.substringAfterLast('-').toInt()))
        }
        val account=object : TravelerAccountRepository by r.account {
            override suspend fun profile()=CatalogResult.Ready(PersonalProfile("Fixture","Owner","fixture_owner","ID",profileId="owner"))
        }
        val runtime=TravelerRuntime(r.identity,r.catalog,r.itineraries,r.distribution,r.trips,r.notifications,account,r.legal,r.registration,
            r.publicContent,r.savedSpots,groups,r.newOperationKey,r.startup)
        ui.runOnUiThread {
            WindowCompat.enableEdgeToEdge(ui.activity.window)
            WindowCompat.getInsetsController(ui.activity.window,ui.activity.window.decorView).run { isAppearanceLightStatusBars=true; isAppearanceLightNavigationBars=true }
        }
        ui.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,scale)) {
            TravelerApp(runtime,{},language=language,backHandler={enabled,back -> BackHandler(enabled,back)})
        } }
        val questLabel=if(language=="id") "Quest" else "Quests"
        ui.waitUntil(8_000) { ui.onAllNodesWithText(questLabel).fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithText(questLabel).onFirst().performClick()
        ui.onNodeWithText(f.card.title).performScrollTo().performClick()
        ui.onNodeWithText(if(language=="id") "Kelompok Quest" else "Quest group").performScrollTo().performClick()
    }
    private fun create(language:String="id") {
        ui.onNodeWithText(if(language=="id") "Buat kelompok" else "Create group").performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("quest-group-mutation-recovery").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1,posts.get()); assertTrue(pending)
    }
    private fun capture(name:String) {
        ui.waitForIdle()
        val folder=requireNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("quest-group-audit")); folder.mkdirs()
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        folder.resolve("$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK) }
    @Test fun unknownCheckingAndExplicitDecisionDoNotReplayOrAutomaticallyInvite() {
        launch(); create()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("quest-group-artwork-ready").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("quest-group-artwork-ready").assertWidthIsEqualTo(184.dp).assertHeightIsEqualTo(122.667.dp)
        capture("unknown"); reviewGate=CompletableDeferred()
        ui.onNodeWithTag("quest-group-recovery-review").performScrollTo().performClick()
        ui.onNodeWithText("Kami memeriksa peran, anggota, undangan dan status terbaru.").assertExists(); capture("checking")
        assertEquals(0,releases.get()); assertEquals(1,posts.get()); requireNotNull(reviewGate).complete(Unit)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("quest-group-recovery-continue").fetchSemanticsNodes().isNotEmpty() }
        capture("review"); ui.onNodeWithTag("quest-group-recovery-continue").assertIsEnabled().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("quest-group-mutation-recovery").fetchSemanticsNodes().isEmpty() }
        assertFalse(pending); assertEquals(1,releases.get()); assertEquals(1,posts.get())
    }
    @Test fun failedReviewRetryAndNativeBackPreserveThePendingActionAndReachTheExistingGroup() {
        launch(); create(); failReview=true
        ui.onNodeWithTag("quest-group-recovery-review").performScrollTo().performClick()
        ui.onNodeWithTag("quest-group-recovery-continue").assertDoesNotExist(); capture("review-failed")
        failReview=false; ui.onNodeWithText("Coba lagi").performScrollTo().performClick()
        ui.onNodeWithTag("quest-group-recovery-continue").assertIsEnabled(); back()
        ui.onNodeWithTag("quest-group-recovery-continue").assertDoesNotExist(); assertTrue(pending)
        back(); ui.onNodeWithTag("quest-group-recovery-open").assertExists()
        ui.onNodeWithText("Group fixture").performScrollTo().performClick()
        ui.onNode(hasSetTextAction()).performTextInput("guest_b")
        ui.onNodeWithText("Undang traveler").performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("quest-group-mutation-recovery").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2,reviews.get()); assertEquals(0,releases.get()); assertEquals(1,posts.get())
    }
    @Test fun englishDecisionAt130PercentIsReachable() {
        launch("en",1.3f); create("en")
        ui.onNodeWithTag("quest-group-recovery-review").performScrollTo().performClick()
        ui.onNodeWithText("Latest group status").assertExists(); ui.onNodeWithTag("quest-group-recovery-continue").assertIsDisplayed().assertIsEnabled()
        capture("review-en-font130"); back(); assertTrue(pending); assertEquals(0,releases.get()); assertEquals(1,posts.get())
    }
    private fun list(manager:Boolean) {
        created=true; launch()
        if(manager) ui.onNodeWithText("Group fixture").performScrollTo().performClick()
        ui.onNodeWithText("Undangan kelompok").performScrollTo().performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("quest-group-invitations-list").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun pageTwoAndBack(manager:Boolean) {
        list(manager)
        ui.onNodeWithTag("quest-group-invitations-list").performScrollToKey("next-page")
        ui.waitUntil(5_000) { pages.any { it.second==2 } }
        ui.onNodeWithTag("quest-group-invitations-list").performScrollToKey(id(21))
        capture(if(manager) "manager-page-two" else "recipient-page-two")
        ui.onNodeWithText("Invitation 21").performClick(); ui.onNodeWithText("Invitation 21").assertExists(); back()
        ui.onNodeWithText("Invitation 21").assertIsDisplayed(); ui.onNodeWithTag("quest-group-invitations-next").assertDoesNotExist()
        assertEquals(listOf((if(manager) group.id else null) to 1,(if(manager) group.id else null) to 2),pages.toList())
        assertEquals(0,posts.get())
    }
    @Test fun recipientCanReachAnOlderInvitationAndReturnWithoutReloadingPageOne() { pageTwoAndBack(false) }
    @Test fun managerKeepsTheGroupFilterForTheOlderInvitationAndReaderBack() { pageTwoAndBack(true) }
    @Test fun appendErrorKeepsTheListAndRetriesPageTwoOnly() {
        failAppend=true; list(false)
        ui.onNodeWithTag("quest-group-invitations-list").performScrollToKey("next-page")
        ui.onNodeWithText("Coba lagi").assertExists(); capture("append-error")
        failAppend=false; ui.onNodeWithText("Coba lagi").performScrollTo().performClick()
        ui.waitUntil(5_000) { pages.count { it.second==2 }==2 }
        ui.onNodeWithTag("quest-group-invitations-list").performScrollToKey(id(21)); ui.onNodeWithText("Invitation 21").assertIsDisplayed()
        assertEquals(listOf(1,2,2),pages.map { it.second }); assertEquals(0,posts.get())
    }
    @Test fun emptyListHasNoNextPageRequest() {
        total=0; list(false)
        ui.onNodeWithText("Belum ada hasil").assertExists(); ui.onNodeWithTag("quest-group-invitations-next").assertDoesNotExist()
        capture("empty"); assertEquals(listOf(1),pages.map { it.second }); assertEquals(0,posts.get())
    }
}
