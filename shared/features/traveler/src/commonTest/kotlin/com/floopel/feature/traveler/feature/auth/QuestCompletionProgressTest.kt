package com.floopel.feature.traveler.feature.auth

import com.floopel.feature.traveler.core.network.AppPlatform
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.domain.model.QuestLocation
import com.floopel.feature.traveler.domain.repository.QuestAttestation
import com.floopel.feature.traveler.domain.repository.QuestLocationSource
import com.floopel.feature.traveler.domain.repository.RefreshCredentialStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Completion projection reads use the real session state machine and deterministic transport gates. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QuestCompletionProgressTest {
    @Test
    fun coldTakeCapturesTheBaselineBeforeProofAndAdmission() = runTest {
        Fixture(this, initialProgressAvailable = false).use { fixture ->
            fixture.open()
            assertNull(fixture.session.view.value.progress)
            fixture.session.takeQuest()
            assertEquals(900L, fixture.session.view.value.progress?.xpTotal)
            assertEquals(1, fixture.starts)
            assertEquals(listOf("progress", "progress", "challenge", "credential", "start"), fixture.events)
        }
    }

    @Test
    fun anUnavailableBaselineDoesNotPreventAnOtherwiseValidAdmission() = runTest {
        Fixture(this, initialProgressAvailable = false).use { fixture ->
            fixture.failProgress += 2
            fixture.open()
            fixture.session.takeQuest()
            assertEquals(1, fixture.starts)
            assertEquals("active", fixture.session.view.value.participation?.status)
            assertNull(fixture.session.view.value.progress)
            assertNull(fixture.session.view.value.failure)
        }
    }

    @Test
    fun cancellingTheBaselinePropagatesWithoutProofOrAdmission() = runTest {
        Fixture(this, initialProgressAvailable = false).use { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.beforeProgress = { if (it == 2) { entered.complete(Unit); release.await() } }
            fixture.open()
            val taking = launch { fixture.session.takeQuest() }
            entered.await()
            taking.cancelAndJoin()
            assertTrue(taking.isCancelled)
            assertEquals(0, fixture.starts)
            assertEquals(0, fixture.proofs)
            assertFalse(fixture.session.view.value.busy)
        }
    }

    @Test
    fun anExistingProjectionAvoidsAnotherBaselineRequest() = runTest {
        Fixture(this).use { fixture ->
            fixture.open()
            fixture.session.takeQuest()
            assertEquals(1, fixture.progressReads)
            assertEquals(1, fixture.starts)
        }
    }

    @Test
    fun logoutDuringTheBaselineCannotRequestAChallengeOrProviderProof() = runTest {
        Fixture(this, initialProgressAvailable = false).use { fixture ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.beforeProgress = { if (it == 2) { entered.complete(Unit); release.await() } }
            fixture.open()
            val taking = launch { fixture.session.takeQuest() }
            entered.await()
            fixture.session.logout()
            release.complete(Unit)
            taking.join()
            assertEquals(0, fixture.events.count { it == "challenge" }, "Logout must stop execution challenge requests")
            assertEquals(0, fixture.proofs, "Logout must stop provider proof requests")
            assertEquals(0, fixture.starts)
            assertFalse(fixture.session.view.value.signedIn)
            assertNull(fixture.session.view.value.progress)
            assertNull(fixture.session.view.value.participation)
        }
    }

    @Test
    fun aCompletedAnswerRefreshesAfterTheMutationWithoutLeavingPlay() = runTest {
        Fixture(this).use { fixture ->
            fixture.prepareActivity()
            fixture.session.answerQuestion("Kopi")
            fixture.assertCompletedProjection()
            assertEquals(2, fixture.progressReads)
            assertEquals("correct", fixture.session.view.value.answerResult?.outcome)
        }
    }

    @Test
    fun anIncompleteAnswerDoesNotFetchCompletionProgress() = runTest {
        Fixture(this).use { fixture ->
            fixture.completeAnswer = false
            fixture.prepareActivity()
            fixture.session.answerQuestion("Kopi")
            assertEquals(1, fixture.progressReads)
            assertEquals("active", fixture.session.view.value.participation?.status)
            assertEquals(900L, fixture.session.view.value.progress?.xpTotal)
        }
    }

    @Test
    fun completedGpsRefreshesAfterCollectionAndMutationRelease() = runTest {
        Fixture(this, gps = true).use { fixture ->
            fixture.prepareActivity()
            val collecting = launch { fixture.session.collectGps() }
            runCurrent()
            assertTrue(fixture.session.view.value.gpsCollecting)
            fixture.session.submitGps()
            collecting.join()
            fixture.assertCompletedProjection()
            assertEquals(2, fixture.progressReads)
            assertEquals("verified", fixture.session.view.value.gpsResult?.attempt?.status)
        }
    }

    @Test
    fun logoutRejectsALatePostCompletionProjection() = runTest {
        Fixture(this).use { fixture ->
            fixture.prepareActivity()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.beforeProgress = { if (fixture.completed) { entered.complete(Unit); release.await() } }
            val answering = launch { fixture.session.answerQuestion("Kopi") }
            entered.await()
            fixture.session.logout()
            release.complete(Unit)
            answering.join()
            assertFalse(fixture.session.view.value.signedIn)
            assertNull(fixture.session.view.value.progress)
            assertNull(fixture.session.view.value.answerResult)
        }
    }

    private class Fixture(scope: TestScope, val gps: Boolean = false, initialProgressAvailable: Boolean = true) : AutoCloseable {
        var progressReads = 0
        var starts = 0
        var proofs = 0
        var completed = false
        var completeAnswer = true
        val events = mutableListOf<String>()
        val failProgress = mutableSetOf<Int>().apply { if (!initialProgressAvailable) add(1) }
        var beforeProgress: suspend (Int) -> Unit = {}
        private var samples = 0
        private val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(scope.testScheduler)
            addHandler { request ->
                val path = request.url.encodedPath
                fun ok(data: String) = respond("""{"meta":{"request_id":"$questId","timestamp":0,"code":"OK"},"error":null,"data":$data}""")
                fun proof() = """{"id":"$proofId","participation_id":"$participationId","activity_id":"$activityId","status":"active","started_at":"1970-01-01T00:00:00Z","expires_at":"1970-01-01T00:05:00Z","observation_count":$samples,"execution_epoch":1}"""
                when {
                    path.endsWith("/login") -> ok("""{"tokens":{"token_type":"Bearer","access_token":"identity","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}""")
                    path.endsWith("/logout") -> respond("", HttpStatusCode.NoContent)
                    path.endsWith("/progress") -> {
                        events += "progress"
                        val count = ++progressReads
                        beforeProgress(count)
                        if (count in failProgress) respond("{}", HttpStatusCode.InternalServerError)
                        else ok(passport(completed))
                    }
                    path.endsWith("/completion/me") -> ok("""{"quest_id":"$questId","locations":[],"quest_slug":"completion","status":"not_completed","is_hidden":false}""")
                    path.endsWith("/completion") -> ok("""{"id":"$questId","slug":"completion","locations":[],"title":"Completion","description":"Story","availability":"active","execution_status":"available","proof_summary":"One activity","reward_preview":{"xp":100,"badge":null},"quota":null,"activities":[]}""")
                    path.endsWith("/challenges") -> { events += "challenge"; ok("""{"challenge_id":"challenge","platform":"android","challenge":"challenge","expires_at":"2099-01-01T00:00:00Z"}""") }
                    path.endsWith("/credentials") -> { events += "credential"; ok("""{"access_token":"execution","token_type":"Bearer","expires_in":600}""") }
                    path.endsWith("/start") -> { events += "start"; starts++; ok(participation(false)) }
                    path.endsWith("/activities/$activityId") -> ok("""{"activity":{"id":"$activityId","locations":[],"title":"Activity","type":"${if (gps) "gps" else "question"}","ordinal":1},"instructions":"Complete","options":[],"answer_type":${if (gps) "null" else "\"text\""},"explanation":null}""")
                    path.endsWith("/answers") -> { completed = completeAnswer; ok("""{"outcome":"${if (completed) "correct" else "incorrect"}","explanation":null,"participation":${participation(completed)}}""") }
                    path.endsWith("/proof-sessions") -> ok(proof())
                    path.endsWith("/observations") -> { samples++; ok(proof()) }
                    path.endsWith("/submit") -> { completed = true; ok("""{"proof_attempt":{"id":"attempt","status":"verified","proof_session_id":"$proofId","activity_id":"$activityId"},"participation":${participation(true)}}""") }
                    else -> error("Unexpected fixture path")
                }
            }
        }))
        val session = TravelerSession(NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID), object : RefreshCredentialStore {
            private var credential: String? = null
            override fun read() = credential
            override fun write(credential: String) { this.credential = credential }
            override fun clear() { credential = null }
        }, "en", { scope.testScheduler.currentTime }, object : QuestAttestation {
            override val available = true
            override suspend fun androidIntegrityToken(challenge: String): String { proofs++; return "proof" }
        }, object : QuestLocationSource {
            override suspend fun current() = QuestLocation(0.0, 0.0, 5.0, "1970-01-01T00:00:00Z")
        })

        suspend fun open() { session.login("fixture@example.invalid", "password"); session.openQuest("completion") }
        suspend fun prepareActivity() { open(); session.takeQuest(); session.openActivity(activityId) }
        fun assertCompletedProjection() {
            val state = session.view.value
            assertEquals(TravelerScreen.QUEST_PLAY, state.screen)
            assertEquals("completed", state.participation?.status)
            assertEquals(activityId, state.activity?.activity?.id)
            assertEquals(1000L, state.progress?.xpTotal)
            assertEquals(2, state.progress?.level?.level)
            assertEquals(completionId, state.progress?.recentCompletions?.single()?.id)
            assertFalse(state.busy)
            assertNull(state.failure)
        }
        override fun close() = client.close()
    }

    companion object {
        private const val questId = "00000000-0000-4000-8000-000000000001"
        private const val participationId = "00000000-0000-4000-8000-000000000002"
        private const val activityId = "00000000-0000-4000-8000-000000000003"
        private const val proofId = "00000000-0000-4000-8000-000000000004"
        private const val completionId = "00000000-0000-4000-8000-000000000005"
        private fun participation(done: Boolean) = """{"id":"$participationId","status":"${if (done) "completed" else "active"}","quest_title":"Completion","quest_revision_id":"revision","quest_execution_status":"available","completed_activity_count":${if (done) 1 else 0},"total_activity_count":1,"activities":[{"activity_id":"$activityId","status":"${if (done) "completed" else "available"}"}]}"""
        private fun passport(done: Boolean) = """{"xp_total":${if (done) 1000 else 900},"level":{"level":${if (done) 2 else 1},"code":"${if (done) "pathfinder" else "explorer"}","name":"Level","xp_floor":${if (done) 1000 else 0},"xp_to_next_level":${if (done) 2000 else 100},"policy_revision":1},"badges":[],"recent_completions":${if (done) "[{\"completion_id\":\"$completionId\",\"quest_title\":\"Completion\",\"quest_slug\":\"completion\",\"share_available\":true,\"reward_status\":\"granted\"}]" else "[]"}}"""
    }
}
