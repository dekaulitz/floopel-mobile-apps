package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.data.remote.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.*
import com.floopel.feature.traveler.feature.legal.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class NativeRegistrationApiTest {
    @Test fun nativeRegistrationEchoesServedLegalHashesAndNeverIssuesAViewerSessionBeforeVerification() = runTest {
        val fixture = RegistrationFixture()
        try {
            val refs = assertIs<RegistrationResult.Ready<RegistrationReferences>>(fixture.repository.references("en")).value
            assertEquals("en", refs.terms.locale)
            assertEquals("terms-hash", refs.terms.hash)
            assertEquals("Indonesia", refs.nationalities.single().label)
            assertEquals(RegistrationResult.Ready(UsernameAvailability(true, listOf("traveler1"))), fixture.repository.usernameAvailable("traveler"))
            val draft = RegistrationDraft("fixture@example.invalid", "ExamplePassword123", "Traveler", "Owner", "traveler", "1990-01-01", "ID", refs)
            assertEquals(RegistrationResult.Ready("f••@example.invalid"), fixture.repository.register(draft, "00000000-0000-4000-8000-000000000001"))
            assertFalse(fixture.session.view.value.signedIn)
            val payload = Json.parseToJsonElement(fixture.lastBody!!).jsonObject
            assertEquals("terms-hash", payload["consent"]!!.jsonObject["terms_hash"]!!.jsonPrimitive.content)
            assertEquals("privacy-hash", payload["consent"]!!.jsonObject["privacy_hash"]!!.jsonPrimitive.content)
            assertFalse(payload.containsKey("return_to"))
            assertFalse(draft.toString().contains("ExamplePassword123"))
            assertEquals(RegistrationResult.Ready(30), fixture.repository.resend(draft.email))
            assertEquals(RegistrationResult.Ready(30), fixture.repository.forgotPassword(draft.email))
            fixture.session.verifyEmail(draft.email, "123456")
            assertTrue(fixture.session.view.value.signedIn)
            assertEquals(1, fixture.verifications)
            assertEquals("protected", fixture.credential)
        } finally { fixture.client.close() }
    }

    @Test fun passwordRecoveryUsesNativeTransportAndPreservesUnusableProofFailures() = runTest {
        val fixture = RegistrationFixture()
        try {
            assertEquals(RegistrationResult.Ready(30), fixture.repository.forgotPassword("fixture@example.invalid"))
            assertEquals("/api/mobile/v1/auth/forgot-password", fixture.lastPath)
            val token = "fixture-recovery-token-000000000001"
            assertEquals(RegistrationResult.Ready(Unit), fixture.repository.resetPassword(token, "UpdatedPassword123"))
            assertEquals("/api/mobile/v1/auth/reset-password", fixture.lastPath)
            val body = Json.parseToJsonElement(assertNotNull(fixture.lastBody)).jsonObject
            assertEquals(setOf("token", "password"), body.keys)
            assertEquals(token, body.getValue("token").jsonPrimitive.content)
            assertFalse(fixture.session.view.value.signedIn)
            assertNull(fixture.credential)
            for ((status, code) in listOf(HttpStatusCode.Gone to "PASSWORD_RESET_UNUSABLE", HttpStatusCode.TooManyRequests to "RATE_LIMITED", HttpStatusCode.InternalServerError to "INTERNAL")) {
                fixture.status = status
                fixture.failureCode = code
                val calls = fixture.calls
                val failure = assertIs<RegistrationResult.Failed>(fixture.repository.resetPassword(token, "UpdatedPassword123"))
                assertEquals(status.value, failure.status)
                assertEquals(code, failure.code)
                assertEquals(calls + 1, fixture.calls)
                assertFalse(fixture.session.view.value.signedIn)
            }
            fixture.status = HttpStatusCode.OK
            fixture.overrideData = "{}"
            assertEquals(RegistrationResult.Failed(502), fixture.repository.resetPassword(token, "UpdatedPassword123"))
        } finally {
            fixture.client.close()
        }
    }

    @Test fun malformedOrUnavailableOnboardingKeepsFailuresSafeAndNeverRetriesIssuance() = runTest {
        val fixture = RegistrationFixture()
        try {
            fixture.status = HttpStatusCode.TooManyRequests
            val limited = assertIs<RegistrationResult.Failed>(fixture.repository.resend("fixture@example.invalid"))
            assertEquals(429, limited.status)
            assertEquals(40L, limited.retryAfterSeconds)
            assertEquals(1, fixture.calls)
            fixture.status = HttpStatusCode.OK
            fixture.overrideData = """{"available":"true"}"""
            assertEquals(RegistrationResult.Failed(502), fixture.repository.usernameAvailable("traveler"))
            fixture.overrideData = """{"resend_in_seconds":-1}"""
            assertEquals(RegistrationResult.Failed(502), fixture.repository.resend("fixture@example.invalid"))
            fixture.overrideData = null
            fixture.status = HttpStatusCode.ServiceUnavailable
            assertEquals(503, assertIs<RegistrationResult.Failed>(fixture.repository.references("en")).status)
            fixture.status = HttpStatusCode.OK
            fixture.networkFailure = true
            fixture.session.verifyEmail("fixture@example.invalid", "123456")
            assertFalse(fixture.session.view.value.signedIn)
            assertNull(fixture.credential)
            assertEquals(1, fixture.verifications)
            assertEquals(0, fixture.session.view.value.failure!!.status)
            fixture.networkFailure = false
            fixture.session.login("fixture@example.invalid", "ExamplePassword123")
            assertTrue(fixture.session.view.value.signedIn)
            assertEquals(1, fixture.verifications)
        } finally { fixture.client.close() }
    }
}

private class RegistrationFixture {
    var status = HttpStatusCode.OK
    var overrideData: String? = null
    var networkFailure = false
    var calls = 0
    var verifications = 0
    var lastBody: String? = null
    var lastPath: String? = null
    var failureCode = "VALIDATION"
    var credential: String? = null
    private val tokens = """{"token_type":"Bearer","access_token":"fixture","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"protected","refresh_expires_at":"2099-02-01T00:00:00Z"}"""
    val client = HttpClient(MockEngine { request ->
        calls++
        val path = request.url.encodedPath
        lastPath = path
        if (!path.endsWith("/progress")) {
            assertNull(request.headers[HttpHeaders.Cookie]); assertNull(request.headers[HttpHeaders.Authorization])
            assertEquals("ANDROID", request.headers["X-App-Platform"])
        }
        if (path.endsWith("/verify-email")) verifications++
        if (networkFailure) error("Fixture response loss")
        lastBody = (request.body as? TextContent)?.text
        val data = overrideData ?: when {
            path.endsWith("/legal/terms") || path.endsWith("/legal/privacy") -> {
                val type = path.substringAfterLast('/')
                """{"doc_type":"$type","version":"1.0","locale_served":"en","content_html":"<p>Legal document</p>","content_hash":"$type-hash"}"""
            }
            path.endsWith("/nationalities") -> """[{"code":"ID","name":"Indonesia","labels":{"id":"Indonesia","en":"Indonesia"}}]"""
            path.endsWith("/username-available") -> """{"available":true,"suggestions":["traveler1"]}"""
            path.endsWith("/register") -> {
                assertEquals("00000000-0000-4000-8000-000000000001", request.headers["Idempotency-Key"])
                """{"status":"verification_required","email_masked":"f••@example.invalid"}"""
            }
            path.endsWith("/resend") -> """{"resend_in_seconds":30}"""
            path.endsWith("/forgot-password") -> """{"retry_in_seconds":30}"""
            path.endsWith("/reset-password") -> "null"
            path.endsWith("/verify-email") || path.endsWith("/login") -> """{"tokens":$tokens}"""
            else -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
        }
        val code = if (status.value >= 400) failureCode else "OK"
        val responseData = if (status.value >= 400) "null" else data
        val responseError = if (status.value >= 400) """{"message":"Safe fixture","details":null}""" else "null"
        val headers = headersOf(HttpHeaders.ContentType, "application/json").let {
            if (status == HttpStatusCode.TooManyRequests) headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("40")) else it
        }
        respond("""{"meta":{"code":"$code"},"error":$responseError,"data":$responseData}""", status, headers)
    })
    private val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
    val session = TravelerSession(api, object : RefreshCredentialStore {
        override fun read() = credential
        override fun write(credential: String) { this@RegistrationFixture.credential = credential }
        override fun clear() { credential = null }
    }, "en")
    val repository = NativeRegistrationApi(api)
}
