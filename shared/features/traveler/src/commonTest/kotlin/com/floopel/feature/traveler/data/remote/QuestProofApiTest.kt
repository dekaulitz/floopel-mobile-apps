package com.floopel.feature.traveler.data.remote

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.domain.model.*

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class QuestProofApiTest {
    private val participation = "00000000-0000-4000-8000-000000000001"
    private val activity = "00000000-0000-4000-8000-000000000002"
    private val session = "00000000-0000-4000-8000-000000000003"
    private val observation = "00000000-0000-4000-8000-000000000004"
    private val execution = ExecutionCredential("execution", "Bearer", 600)
    private val point = QuestLocation(1.0, 2.0, 4.0, "2026-10-14T00:00:00Z")
    private fun proof(count: Int) = """{"id":"$session","participation_id":"$participation","activity_id":"$activity","status":"active","started_at":"2026-10-14T00:00:00Z","expires_at":"2026-10-14T00:05:00Z","observation_count":$count,"execution_epoch":1}"""

    @Test
    fun gpsRequestsUseExecutionAndServerOutcomes() = runTest {
        var requests = 0
        val api = NativeApi(HttpClient(MockEngine { request ->
            requests++
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("Bearer execution", request.headers[HttpHeaders.Authorization])
            assertEquals("request-$requests", request.headers["Idempotency-Key"])
            assertNull(request.headers[HttpHeaders.Cookie])
            val data = when (requests) {
                1 -> {
                    assertEquals("/api/apps/v1/mobile/gamification/participations/$participation/proof-sessions", request.url.encodedPath)
                    assertEquals("""{"activity_id":"$activity"}""", (request.body as TextContent).text)
                    proof(0)
                }
                2 -> {
                    assertEquals("/api/apps/v1/mobile/gamification/proof-sessions/$session/observations", request.url.encodedPath)
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals(setOf("observation_id", "lat", "lng", "accuracy_m", "captured_at"), body.keys)
                    assertEquals(observation, body["observation_id"]?.jsonPrimitive?.content)
                    assertEquals(4.0, body["accuracy_m"]?.jsonPrimitive?.double)
                    proof(1)
                }
                3 -> {
                    assertEquals("/api/apps/v1/mobile/gamification/proof-sessions/$session/submit", request.url.encodedPath)
                    assertFalse(request.body is TextContent)
                    """{"proof_attempt":{"id":"attempt","status":"rejected","proof_session_id":"$session","activity_id":"$activity","reason_code":"GPS_PROOF_INSUFFICIENT"},"participation":{"id":"$participation","status":"active","quest_title":"Quest","quest_revision_id":"revision","quest_execution_status":"available","completed_activity_count":0,"total_activity_count":1},"completion":null}"""
                }
                else -> error("Unexpected automatic request")
            }
            respond("""{"error":null,"data":$data}""")
        }), "https://fixture.invalid", AppPlatform.ANDROID)
        assertEquals(0, api.startProof(participation, activity, execution, "request-1").observationCount)
        assertEquals(1, api.observeProof(session, observation, point, execution, "request-2").observationCount)
        val result = api.submitProof(session, execution, "request-3")
        assertEquals("rejected", result.attempt.status)
        assertEquals(0, result.participation.completedCount)
        assertEquals(3, requests)
    }

    @Test
    fun lostObservationResponseIsNotAutomaticallyReplayed() = runTest {
        var requests = 0
        val api = NativeApi(HttpClient(MockEngine {
            requests++
            respond("maintenance", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "60"))
        }), "https://fixture.invalid", AppPlatform.ANDROID)
        val failure = assertFailsWith<ApiFailure> { api.observeProof(session, observation, point, execution, "stable-key") }
        assertEquals(503, failure.status)
        assertEquals(60, failure.retryAfterSeconds)
        assertEquals(1, requests)
    }

    @Test
    fun malformedCoordinatesAreNotSent() = runTest {
        val api = NativeApi(HttpClient(MockEngine { error("Invalid evidence must not reach transport") }), "https://fixture.invalid", AppPlatform.ANDROID)
        for (location in listOf(
            QuestLocation(Double.NaN, 2.0, 4.0, point.capturedAt),
            QuestLocation(1.0, 181.0, 4.0, point.capturedAt),
            QuestLocation(1.0, 2.0, -1.0, point.capturedAt),
        )) assertFailsWith<IllegalArgumentException> { api.observeProof(session, observation, location, execution, "key") }
    }
}
