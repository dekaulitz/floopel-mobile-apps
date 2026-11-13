package com.floopel.feature.traveler.feature.auth

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.data.remote.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HiddenQuestLifecycleTest {
    @Test fun uncertainStartReplaysTheOriginalLocationAndKey() = verifyStartRetry(false)
    @Test fun rejectedStartAllowsAFreshLocationAndKey() = verifyStartRetry(true)

    private fun verifyStartRetry(rejected: Boolean) = runTest {
        var fixes = 0
        var starts = 0
        var originalKey: String? = null
        var originalBody: String? = null
        val session = session(object : QuestLocationSource {
            override suspend fun current() = QuestLocation(point.latitude, (++fixes) * 0.00001, point.accuracyMeters, point.capturedAt)
        }) { request ->
            when {
                request.url.encodedPath.endsWith("/reveal") -> respond("""{"error":null,"data":[$detail],"meta":{"pagination":{"page":1,"total_pages":1}}}""")
                request.url.encodedPath.endsWith("/me") -> respond("{}", HttpStatusCode.NotFound)
                request.url.encodedPath.endsWith("/start") -> {
                    starts++
                    val body = (request.body as io.ktor.http.content.TextContent).text
                    if (starts == 1) {
                        originalKey = request.headers["Idempotency-Key"]
                        originalBody = body
                        if (!rejected) throw IllegalStateException("Response lost after commit")
                        respond("{}", HttpStatusCode.NotFound)
                    } else {
                        if (rejected) {
                            assertNotEquals(originalKey, request.headers["Idempotency-Key"])
                            assertNotEquals(originalBody, body)
                        } else {
                            assertEquals(originalKey, request.headers["Idempotency-Key"])
                            assertEquals(originalBody, body)
                        }
                        respond("""{"error":null,"data":$participation}""")
                    }
                }
                else -> error("Unexpected request")
            }
        }
        session.login("fixture@example.invalid", "password")
        session.revealNearby()
        session.openHidden("hidden")
        session.takeQuest()
        assertEquals(if (rejected) 404 else 0, session.view.value.failure?.status)
        session.takeQuest()
        assertEquals(2, starts)
        assertEquals(if (rejected) 3 else 2, fixes)
        assertEquals(TravelerScreen.QUEST_PLAY, session.view.value.screen)
        assertNotNull(session.view.value.participation)
    }

    private val questId = "00000000-0000-4000-8000-000000000001"
    private val participation = """{"id":"00000000-0000-4000-8000-000000000002","status":"active","quest_title":"Hidden","quest_revision_id":"revision","quest_execution_status":"available","completed_activity_count":0,"total_activity_count":1} """
    private val detail = """{"id":"$questId","slug":"hidden","locations":[],"title":"Hidden","description":"Story","availability":"active","execution_status":"available","proof_summary":"GPS","reward_preview":{"xp":100,"badge":null},"quota":null,"activities":[]} """
    private val point = QuestLocation(0.0, 0.0, 5.0, "2026-10-14T00:00:00Z")
    private class Store : RefreshCredentialStore {
        override fun read(): String? = null
        override fun write(credential: String) {}
        override fun clear() {}
    }
    private fun session(location: QuestLocationSource, handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData): TravelerSession {
        val engine = MockEngine { request ->
            fun ok(data: String) = respond("""{"error":null,"data":$data}""")
            when {
                request.url.encodedPath.endsWith("/login") -> ok("""{"tokens":{"token_type":"Bearer","access_token":"identity","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}""")
                request.url.encodedPath.endsWith("/progress") -> ok("""{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}""")
                request.url.encodedPath.endsWith("/challenges") -> ok("""{"challenge_id":"challenge","platform":"android","challenge":"challenge","expires_at":"2099-01-01T00:00:00Z"}""")
                request.url.encodedPath.endsWith("/credentials") -> ok("""{"access_token":"execution","token_type":"Bearer","expires_in":600}""")
                else -> handler(request)
            }
        }
        return TravelerSession(NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.ANDROID), Store(), "en", attestation = object : QuestAttestation {
            override val available = true
            override suspend fun androidIntegrityToken(challenge: String) = "proof"
        }, locationSource = location)
    }

    @Test
    fun backgroundDiscardsLateLocationWithoutSendingReveal() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val fix = CompletableDeferred<QuestLocation>()
        val session = session(object : QuestLocationSource {
            override suspend fun current(): QuestLocation { waiting.complete(Unit); return fix.await() }
        }) { error("No request may follow a background location") }
        session.login("fixture@example.invalid", "password")
        val reveal = launch { session.revealNearby() }
        waiting.await()
        session.onBackground()
        fix.complete(point)
        reveal.join()
        assertNull(session.view.value.nearby)
        assertFalse(session.view.value.busy)
        assertEquals(TravelerScreen.QUEST_LIST, session.view.value.screen)
    }

    @Test
    fun backgroundDiscardsUnsentStartLocationBeforeRetry() = runTest {
        val waiting = CompletableDeferred<Unit>()
        val lateFix = CompletableDeferred<QuestLocation>()
        var fixes = 0
        var starts = 0
        val session = session(object : QuestLocationSource {
            override suspend fun current(): QuestLocation {
                fixes++
                if (fixes == 2) {
                    waiting.complete(Unit)
                    return lateFix.await()
                }
                return QuestLocation(point.latitude, fixes * 0.00001, point.accuracyMeters, point.capturedAt)
            }
        }) { request ->
            when {
                request.url.encodedPath.endsWith("/reveal") -> respond("""{"error":null,"data":[$detail],"meta":{"pagination":{"page":1,"total_pages":1}}}""")
                request.url.encodedPath.endsWith("/me") -> respond("{}", HttpStatusCode.NotFound)
                request.url.encodedPath.endsWith("/start") -> {
                    starts++
                    assertEquals(4, fixes)
                    respond("""{"error":null,"data":$participation}""")
                }
                else -> error("Unexpected request")
            }
        }
        session.login("fixture@example.invalid", "password")
        session.revealNearby()
        session.openHidden("hidden")
        val start = launch { session.takeQuest() }
        waiting.await()
        session.onBackground()
        lateFix.complete(point)
        start.join()
        assertEquals(0, starts)
        session.revealNearby()
        session.openHidden("hidden")
        session.takeQuest()
        assertEquals(1, starts)
        assertEquals(4, fixes)
        assertEquals(TravelerScreen.QUEST_PLAY, session.view.value.screen)
    }

    @Test
    fun backgroundKeepsStartLockedAndOwnedDetailAccessible() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var starts = 0
        var owned = false
        val session = session(object : QuestLocationSource { override suspend fun current() = point }) { request ->
            when {
                request.url.encodedPath.endsWith("/reveal") -> respond("""{"error":null,"data":[$detail],"meta":{"pagination":{"page":1,"total_pages":1}}}""")
                request.url.encodedPath.endsWith("/me") -> if (owned) respond("""{"error":null,"data":{"quest_id":"$questId","locations":[],"quest_slug":"hidden","status":"active","active_participation":$participation}}""") else respond("{}", HttpStatusCode.NotFound)
                request.url.encodedPath.endsWith("/start") -> {
                    starts++; started.complete(Unit); finish.await(); owned = true
                    respond("""{"error":null,"data":$participation}""")
                }
                else -> error("Unexpected request")
            }
        }
        session.login("fixture@example.invalid", "password")
        session.revealNearby()
        session.openHidden("hidden")
        val start = launch { session.takeQuest() }
        started.await()
        session.onBackground()
        assertTrue(session.view.value.busy)
        session.takeQuest()
        assertEquals(1, starts)
        finish.complete(Unit)
        start.join()
        assertFalse(session.view.value.busy)
        session.openHidden("hidden")
        assertEquals(TravelerScreen.QUEST_DETAIL, session.view.value.screen)
        assertEquals("Hidden", session.view.value.questDetail?.title)
        session.onBackground()
        assertNotNull(session.view.value.questDetail)
    }
}
