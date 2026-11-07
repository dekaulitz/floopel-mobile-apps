package com.floopel.feature.traveler.core.network

import com.floopel.feature.traveler.data.remote.*

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Every JUnit test has a Unit return type, including coroutine-based tests. */
class FloopelRestClientTest {
    @Test
    fun returnsSuccessAndPreservesContractResponse() {
        runBlocking {
            val body = """{"meta":{"code":"OK"},"error":null,"data":{}}"""
            val client = FloopelRestClient(
                "https://api.example.test",
                HttpClient(MockEngine { request ->
                    assertEquals("/api/apps/v1/healthz", request.url.encodedPath)
                    respond(body)
                }),
            )
            try {
                val result = assertIs<ApiOutcome.Success>(
                    client.execute(RestMethod.GET, "/api/apps/v1/healthz"),
                )
                assertEquals(200, result.status)
                assertEquals(body, result.responseBody)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun handlesPlainTextMaintenanceWithoutTryingToParseIt() {
        runBlocking {
            val client = FloopelRestClient(
                "https://api.example.test",
                HttpClient(MockEngine {
                    respond(
                        "<html>temporary maintenance</html>",
                        status = HttpStatusCode.ServiceUnavailable,
                        headers = headersOf(HttpHeaders.RetryAfter, "60"),
                    )
                }),
            )
            try {
                val result = assertIs<ApiOutcome.Unavailable>(
                    client.execute(RestMethod.GET, "/api/apps/v1/healthz"),
                )
                assertEquals(60L, result.retryAfterSeconds)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun doesNotLeakErrorBodyFrom401() {
        runBlocking {
            val client = FloopelRestClient(
                "https://api.example.test",
                HttpClient(MockEngine {
                    respond("potentially sensitive text", status = HttpStatusCode.Unauthorized)
                }),
            )
            try {
                assertEquals(401, assertIs<ApiOutcome.HttpFailure>(
                    client.execute(RestMethod.GET, "/api/apps/v1/healthz"),
                ).status)
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun networkFailureDoesNotLeakException() {
        runBlocking {
            val client = FloopelRestClient(
                "https://api.example.test",
                HttpClient(MockEngine { throw IllegalStateException("not exposed") }),
            )
            try {
                assertIs<ApiOutcome.NetworkFailure>(
                    client.execute(RestMethod.GET, "/api/apps/v1/healthz"),
                )
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun cancellationPropagatesToTheCaller() {
        runBlocking {
            val client = FloopelRestClient(
                "https://api.example.test",
                HttpClient(MockEngine { throw CancellationException("cancelled") }),
            )
            try {
                assertFailsWith<CancellationException> {
                    client.execute(RestMethod.GET, "/api/apps/v1/healthz")
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun retryAfterPolicyIsBounded() {
        assertNull(parseRetryAfter(null))
        assertNull(parseRetryAfter("tomorrow"))
        assertNull(parseRetryAfter("0"))
        assertNull(parseRetryAfter("72000"))
        assertEquals(60L, parseRetryAfter("60"))
        assertEquals(2L, parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT", 1445412478000L))
        assertNull(parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT", 1445412480000L))
    }

    @Test
    fun unsafePathIsRejected() {
        runBlocking {
            val client = FloopelRestClient("https://api.example.test")
            try {
                assertFailsWith<IllegalArgumentException> {
                    client.execute(RestMethod.GET, "//evil.example.test/path")
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun httpBaseUrlIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            FloopelRestClient("http://api.example.test")
        }
    }

    @Test fun rateLimitPreservesRetryAfterWithoutRetryingOrLeakingProxyHtml() = runBlocking {
        var requests = 0
        val client = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine {
            requests++
            respond("<html>provider details</html>", HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.RetryAfter, "30"))
        }))
        try {
            val result = assertIs<ApiOutcome.HttpFailure>(client.execute(RestMethod.GET, "/api/apps/v1/spots"))
            assertEquals(429, result.status)
            assertEquals(30L, result.retryAfterSeconds)
            assertNull(result.jsonErrorBody)
            assertEquals(1, requests)
        } finally { client.close() }
    }

}
