package com.floopel.feature.traveler.data.remote

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.*

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NativeApiTest {
    @Test
    fun loginUsesNativePlatformAndRedactsCredentials() = runTest {
        val engine = MockEngine { request ->
            assertEquals("/api/mobile/v1/auth/login", request.url.encodedPath)
            assertEquals("ANDROID", request.headers["X-App-Platform"])
            assertNull(request.headers["Cookie"])
            assertNull(request.headers["Authorization"])
            respond("""{"meta":{"code":"OK"},"error":null,"data":{"tokens":{"token_type":"Bearer","access_token":"secret-access","access_expires_at":"2026-10-14T12:00:00Z","refresh_token":"secret-refresh","refresh_expires_at":"2026-11-14T12:00:00Z"}}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val tokens = NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.ANDROID).login("fixture@example.invalid", "secret")
        assertEquals("secret-access", assertIs<NativeAuthentication.SignedIn>(tokens).tokens.accessToken)
        assertFalse(tokens.toString().contains("secret"))
    }

    @Test
    fun proxyMaintenancePreservesStatusAndNeverRetriesMutation() = runTest {
        var requests = 0
        val engine = MockEngine { requests++; respond("<html>maintenance</html>", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.RetryAfter, "60")) }
        val failure = assertFailsWith<ApiFailure> { NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.IOS).login("fixture@example.invalid", "secret") }
        assertEquals(503, failure.status)
        assertEquals(60, failure.retryAfterSeconds)
        assertEquals(1, requests)
        assertFalse(failure.toString().contains("html"))
    }

    @Test
    fun ownerReadUsesIdentityBearerAndDoesNotRefreshOnFailure() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            assertEquals("Bearer identity", request.headers["Authorization"])
            assertEquals("IOS", request.headers["X-App-Platform"])
            respond("""{"meta":{"code":"UNAUTHORIZED"},"error":{},"data":null}""", HttpStatusCode.Unauthorized)
        }
        val failure = assertFailsWith<ApiFailure> { NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.IOS).passport("identity", "id") }
        assertEquals(401, failure.status)
        assertEquals(1, requests)
    }

    @Test
    fun malformedSuccessfulPayloadIsNotAnEmptyPassport() = runTest {
        val engine = MockEngine { respond("""{"meta":{"code":"OK"},"error":null,"data":{}}""") }
        val failure = assertFailsWith<ApiFailure> { NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.IOS).passport("identity", "id") }
        assertEquals(502, failure.status)
    }

    @Test
    fun cancellationIsNotConvertedIntoServiceFailure() = runTest {
        val engine = MockEngine { throw CancellationException("screen closed") }
        assertFailsWith<CancellationException> { NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.IOS).passport("identity", "id") }
    }

    @Test
    fun retryAfterSupportsSecondsDatesAndInvalidValues() {
        assertEquals(60, retryAfterSeconds("60", 0))
        assertEquals(60, retryAfterSeconds("Thu, 01 Jan 1970 00:01:00 GMT", 0))
        assertEquals(0, retryAfterSeconds("Thu, 01 Jan 1970 00:01:00 GMT", 61000))
        assertNull(retryAfterSeconds("-1", 0))
        assertNull(retryAfterSeconds("bad", 0))
    }

    @Test
    fun malformedLoginEnvelopeIsReportedAsInvalidResponse() = runTest {
        val engine = MockEngine { respond("""{"error":null,"data":{}}""") }
        val failure = assertFailsWith<ApiFailure> { NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.IOS).login("fixture@example.invalid", "secret") }
        assertEquals(502, failure.status)
    }

    @Test
    fun questListReadsPaginationWithoutSendingIdentity() = runTest {
        val engine = MockEngine { request ->
            assertEquals("2", request.url.parameters["page"])
            assertEquals("20", request.url.parameters["per_page"])
            assertNull(request.headers["Authorization"])
            respond("""{"error":null,"meta":{"pagination":{"page":2,"total_pages":3}},"data":[]}""")
        }
        val page = NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.ANDROID).quests(2, "en")
        assertEquals(2, page.pagination.page)
        assertEquals(3, page.pagination.totalPages)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun ownerStateUsesIdentityAndRejectsPathInjection() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals("/api/apps/v1/gamification/quests/coffee-story/me", request.url.encodedPath)
            assertEquals("Bearer owner", request.headers["Authorization"])
            respond("""{"error":null,"data":{"quest_id":"quest","locations":[],"quest_slug":"coffee-story","status":"completed"}}""")
        }
        val api = NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.IOS)
        assertEquals("completed", api.questOwner("coffee-story", "id", "owner").status)
        assertFailsWith<IllegalArgumentException> { api.questOwner("../other", "id", "owner") }
        assertEquals(1, calls)
    }

    @Test
    fun multipleChoiceMutationUsesTypedPayloadAndDoesNotRetryFailure() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            assertEquals("Bearer execution", request.headers["Authorization"])
            assertEquals("stable-key", request.headers["Idempotency-Key"])
            assertEquals("""{"answer_type":"multiple_choice","selected_option_id":"00000000-0000-4000-8000-000000000003"}""", (request.body as io.ktor.http.content.TextContent).text)
            respond("{}", HttpStatusCode.Unauthorized)
        }
        val api = NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.ANDROID)
        val failure = assertFailsWith<ApiFailure> { api.answer("00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002", "multiple_choice", "00000000-0000-4000-8000-000000000003", ExecutionCredential("execution", "Bearer", 600), "stable-key", "en") }
        assertEquals(401, failure.status)
        assertEquals(1, requests)
    }
}
