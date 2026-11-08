package com.floopel.feature.traveler.feature.auth

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.data.repository.NativeTravelerIdentity
import com.floopel.feature.traveler.domain.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class QuestAnswerAttributionTest {
    @Test fun pendingAndSuccessProjectionsStayBoundToTheActualSubmittedInputWithDistinctExplicitAttempts() = runTest {
        val f = Fixture()
        try {
            f.enter(); val gate = CompletableDeferred<Unit>(); f.beforeAnswer = { gate.await() }
            val action = async { f.session.answerQuestion("A") }; f.started.await()
            val pending = f.identity.view.first(); assertTrue(pending.busy); assertEquals("A", pending.submittedAnswer); assertNull(pending.answerOutcome)
            assertEquals(listOf("A"), f.inputs)
            gate.complete(Unit); action.await()
            val first = f.identity.view.first(); assertEquals("A", first.submittedAnswer); assertEquals("incorrect", first.answerOutcome)
            f.session.openActivity(f.activityId); assertEquals("A", f.identity.view.first().submittedAnswer)
            assertEquals("incorrect", f.identity.view.first().answerOutcome) // same-reader revisit retains attribution
            f.beforeAnswer = {}; f.session.answerQuestion("B")
            val second = f.identity.view.first(); assertEquals("B", second.submittedAnswer); assertEquals("correct", second.answerOutcome)
            assertEquals(listOf("A", "B"), f.inputs); assertNotEquals(f.keys[0], f.keys[1])
        } finally { f.client.close() }
    }
    @Test fun movingToAnotherActivityHidesThePreviousAnswerAndItsResult() = runTest {
        val f = Fixture()
        try {
            f.enter(); f.session.answerQuestion("A"); assertEquals("incorrect", f.identity.view.first().answerOutcome)
            f.session.openActivity(f.otherActivityId)
            assertNull(f.identity.view.first().submittedAnswer); assertNull(f.identity.view.first().answerOutcome)
            assertEquals(1, f.inputs.size)
        } finally { f.client.close() }
    }
    @Test fun logoutDiscardsAnOldAnswerCallbackWithoutReplayingAndExplicitRetryKeepsTheExistingIntentKey() = runTest {
        val f = Fixture()
        try {
            f.enter(); f.failAnswer = true; f.session.answerQuestion("A")
            assertEquals("A", f.identity.view.first().submittedAnswer); assertNull(f.identity.view.first().answerOutcome)
            f.failAnswer = false; f.session.answerQuestion("A"); assertEquals(f.keys[0], f.keys[1])
            val gate = CompletableDeferred<Unit>(); f.beforeAnswer = { withContext(NonCancellable) { gate.await() } }
            val action = async { f.session.answerQuestion("B") }; f.secondStarted.await()
            f.session.logout(); gate.complete(Unit); action.await()
            val current = f.identity.view.first(); assertFalse(current.signedIn); assertNull(current.submittedAnswer); assertNull(current.answerOutcome)
            assertEquals(listOf("A", "A", "B"), f.inputs)
        } finally { f.client.close() }
    }
}

private class Fixture {
    val questId = "00000000-0000-4000-8000-000000000001"
    val participationId = "00000000-0000-4000-8000-000000000002"
    val activityId = "00000000-0000-4000-8000-000000000003"
    val otherActivityId = "00000000-0000-4000-8000-000000000004"
    val inputs = mutableListOf<String>()
    val keys = mutableListOf<String?>()
    val started = CompletableDeferred<Unit>()
    val secondStarted = CompletableDeferred<Unit>()
    var beforeAnswer: suspend () -> Unit = {}
    var failAnswer = false
    private fun participation() = """{"id":"$participationId","status":"active","quest_title":"Quest fixture","quest_revision_id":"revision","quest_execution_status":"available","completed_activity_count":0,"total_activity_count":2,"activities":[{"activity_id":"$activityId","status":"available"},{"activity_id":"$otherActivityId","status":"available"}]}"""
    val client = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        fun ok(data: String) = respond("""{"error":null,"data":$data}""")
        when {
            path.endsWith("/login") -> ok("""{"tokens":{"token_type":"Bearer","access_token":"fixture","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"fixture-refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}""")
            path.endsWith("/logout") -> ok("null")
            path.endsWith("/progress") -> ok("""{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}""")
            path.endsWith("/fixture/me") -> ok("""{"quest_id":"$questId","locations":[],"quest_slug":"fixture","status":"not_completed"}""")
            path.endsWith("/fixture") -> ok("""{"id":"$questId","slug":"fixture","locations":[],"title":"Quest fixture","description":"Story","availability":"active","execution_status":"available","proof_summary":"Answer","reward_preview":{"xp":100,"badge":null},"quota":null,"activities":[]}""")
            path.endsWith("/challenges") -> ok("""{"challenge_id":"fixture","platform":"android","challenge":"fixture","expires_at":"2099-01-01T00:00:00Z"}""")
            path.endsWith("/credentials") -> ok("""{"access_token":"fixture-execution","token_type":"Bearer","expires_in":600}""")
            path.endsWith("/start") -> ok(participation())
            path.contains("/activities/") && !path.endsWith("/answers") -> {
                val id = path.substringAfterLast('/')
                ok("""{"activity":{"id":"$id","title":"Question fixture","type":"question","ordinal":1},"instructions":"Answer","options":[],"answer_type":"text","explanation":null}""")
            }
            path.endsWith("/answers") -> {
                assertEquals("Bearer fixture-execution", request.headers[HttpHeaders.Authorization])
                val input = if ((request.body as TextContent).text.contains("\"B\"")) "B" else "A"
                inputs += input; keys += request.headers["Idempotency-Key"]; assertNotNull(keys.last())
                started.complete(Unit); if (input == "B") secondStarted.complete(Unit)
                beforeAnswer(); if (failAnswer) error("fixture offline")
                ok("""{"outcome":"${if (input == "A") "incorrect" else "correct"}","explanation":"Result $input","participation":${participation()},"completion":null}""")
            }
            else -> error("Unexpected fixture route $path")
        }
    })
    private val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
    val session = TravelerSession(api, object : RefreshCredentialStore {
        private var value: String? = null
        override fun read() = value
        override fun write(credential: String) { value = credential }
        override fun clear() { value = null }
    }, "id", attestation = object : QuestAttestation {
        override val available = true
        override suspend fun androidIntegrityToken(challenge: String) = "fixture-proof"
    })
    val identity = NativeTravelerIdentity(session, api)
    suspend fun enter() { session.login("fixture@example.invalid", "fixture-password"); session.openQuest("fixture"); session.takeQuest(); session.openActivity(activityId) }
}
