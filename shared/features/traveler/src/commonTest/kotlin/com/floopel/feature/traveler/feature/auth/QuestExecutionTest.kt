package com.floopel.feature.traveler.feature.auth

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.data.remote.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class QuestExecutionTest {
    private val questId = "00000000-0000-4000-8000-000000000001"
    private val participationId = "00000000-0000-4000-8000-000000000002"
    private val activityId = "00000000-0000-4000-8000-000000000003"
    private fun participation(completed: Boolean = false) = """{"id":"$participationId","status":"${if (completed) "completed" else "active"}","quest_title":"Bound title","quest_revision_id":"revision","quest_execution_status":"available","completed_activity_count":${if (completed) 1 else 0},"total_activity_count":1,"activities":[{"activity_id":"$activityId","status":"${if (completed) "completed" else "available"}"}]}"""
    private class Store : RefreshCredentialStore {
        private var token: String? = null
        override fun read() = token
        override fun write(credential: String) { token = credential }
        override fun clear() { token = null }
    }

    @Test
    fun startRetryKeepsKeyAndQuestionUsesOnlyExecutionCredential() = runTest {
        var clock = 0L
        var starts = 0
        var proofs = 0
        var firstKey: String? = null
        val verifier = object : QuestAttestation {
            override val available = true
            override suspend fun androidIntegrityToken(challenge: String): String {
                assertEquals("server-challenge", challenge); proofs++; return "provider-proof"
            }
        }
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            fun ok(data: String) = respond("""{"error":null,"data":$data}""")
            when {
                path.endsWith("/login") -> ok("""{"tokens":{"token_type":"Bearer","access_token":"identity","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}""")
                path.endsWith("/progress") -> ok("""{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}""")
                path.endsWith("/coffee/me") -> ok("""{"quest_id":"$questId","locations":[],"quest_slug":"coffee","status":"not_completed"}""")
                path.endsWith("/coffee") -> ok("""{"id":"$questId","slug":"coffee","locations":[],"title":"Coffee","description":"Story","availability":"active","execution_status":"available","proof_summary":"Answer","reward_preview":{"xp":100,"badge":null},"quota":null,"activities":[]}""")
                path.endsWith("/challenges") -> {
                    assertEquals("Bearer identity", request.headers["Authorization"])
                    ok("""{"challenge_id":"challenge","platform":"android","challenge":"server-challenge","expires_at":"2099-01-01T00:00:00Z"}""")
                }
                path.endsWith("/credentials") -> {
                    assertEquals("Bearer identity", request.headers["Authorization"])
                    assertTrue((request.body as TextContent).text.contains("provider-proof"))
                    ok("""{"access_token":"execution","token_type":"Bearer","expires_in":600}""")
                }
                path.endsWith("/start") -> {
                    assertEquals("Bearer execution", request.headers["Authorization"])
                    starts++
                    if (starts == 1) {
                        firstKey = request.headers["Idempotency-Key"]
                        respond("maintenance", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "60"))
                    } else {
                        assertNotNull(firstKey); assertEquals(firstKey, request.headers["Idempotency-Key"])
                        ok(participation())
                    }
                }
                path.endsWith("/answers") -> {
                    assertEquals("Bearer execution", request.headers["Authorization"])
                    assertEquals("""{"answer_type":"text","text":"Arabika"}""", (request.body as TextContent).text)
                    assertNotNull(request.headers["Idempotency-Key"])
                    ok("""{"outcome":"correct","explanation":"Correct explanation","participation":${participation(true)},"completion":null}""")
                }
                path.endsWith("/activities/$activityId") -> {
                    assertEquals("Bearer identity", request.headers["Authorization"])
                    ok("""{"activity":{"id":"$activityId","title":"Origin","type":"question","ordinal":1},"instructions":"Answer","options":[],"answer_type":"text","explanation":null}""")
                }
                else -> error("Unexpected route")
            }
        }
        val session = TravelerSession(NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.ANDROID), Store(), "en", { clock }, verifier)
        session.login("fixture@example.invalid", "password")
        session.openQuest("coffee")
        session.takeQuest()
        assertEquals(503, session.view.value.failure?.status)
        session.takeQuest()
        assertEquals(1, starts)
        clock = 60001
        session.takeQuest()
        assertEquals(TravelerScreen.QUEST_PLAY, session.view.value.screen)
        assertEquals("Bound title", session.view.value.participation?.title)
        session.openActivity(activityId)
        session.answerQuestion("Arabika")
        assertEquals("completed", session.view.value.participation?.status)
        assertEquals("correct", session.view.value.answerResult?.outcome)
        assertEquals(3, proofs)
    }
}
