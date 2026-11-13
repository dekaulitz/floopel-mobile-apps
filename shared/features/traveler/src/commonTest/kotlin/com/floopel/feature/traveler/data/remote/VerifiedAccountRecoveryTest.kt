package com.floopel.feature.traveler.data.remote

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.repository.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.*

import com.floopel.feature.traveler.data.repository.NativeErasureApi
import com.floopel.feature.traveler.domain.model.AccountErasureState
import com.floopel.feature.traveler.domain.model.CatalogResult
import com.floopel.feature.traveler.domain.repository.RefreshCredentialStore
import com.floopel.feature.traveler.feature.auth.TravelerSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class VerifiedAccountRecoveryTest {
    @Test fun passwordAndGoogleRecoveryOnlyUseRestrictedProofAndCancellationHidesTheEntry() = runTest {
        for (google in listOf(false, true)) {
            val fixture = RecoveryFixture()
            try {
                if (google) fixture.session.loginGoogle("provider-token") else fixture.session.login("fixture@example.invalid", "fixture-password")
                assertTrue(fixture.session.view.value.accountRecoveryRequired)
                assertFalse(fixture.session.view.value.signedIn)
                assertEquals(0, fixture.storedCredentials)
                val repository = NativeErasureApi(fixture.api.catalogTransport(), fixture.session)
                assertEquals("pending", assertIs<CatalogResult.Ready<AccountErasureState>>(repository.state()).value.status)
                assertEquals("active", assertIs<CatalogResult.Ready<AccountErasureState>>(repository.cancel("stable-cancel-key-123")).value.status)
                assertFalse(fixture.session.view.value.accountRecoveryRequired)
                assertFalse(fixture.session.view.value.signedIn)
                assertEquals(0, fixture.storedCredentials)
            } finally { fixture.api.close() }
        }
    }

    @Test fun wrongPasswordExpiredProofAndLogoutNeverExposeRecovery() = runTest {
        val fixture = RecoveryFixture()
        try {
            fixture.status = HttpStatusCode.Unauthorized
            fixture.session.login("fixture@example.invalid", "wrong-password")
            assertFalse(fixture.session.view.value.accountRecoveryRequired)
            fixture.status = HttpStatusCode.Accepted
            fixture.expiry = "2000-01-01T00:00:00Z"
            fixture.session.login("fixture@example.invalid", "fixture-password")
            assertFalse(fixture.session.view.value.accountRecoveryRequired)
            fixture.expiry = "2099-01-01T00:00:00Z"
            fixture.session.login("fixture@example.invalid", "fixture-password")
            assertTrue(fixture.session.view.value.accountRecoveryRequired)
            fixture.session.logout()
            assertFalse(fixture.session.view.value.accountRecoveryRequired)
            assertEquals(CatalogResult.HttpError(401), NativeErasureApi(fixture.api.catalogTransport(), fixture.session).state())
        } finally { fixture.api.close() }
    }
}

private class RecoveryFixture {
    var storedCredentials = 0
    var status = HttpStatusCode.Accepted
    var expiry = "2099-01-01T00:00:00Z"
    private val client = HttpClient(MockEngine { request ->
        assertNull(request.headers[HttpHeaders.Cookie])
        assertNull(request.headers[HttpHeaders.Authorization])
        val login = request.url.encodedPath.endsWith("/login") || request.url.encodedPath.endsWith("/google")
        val data = when {
            login -> """{"status":"account_recovery","context_token":"restricted","purpose":"cancel","expires_at":"$expiry"}"""
            request.url.encodedPath.endsWith("/cancellation") -> {
                assertEquals("restricted", request.headers["X-Account-Deletion-Context"])
                assertEquals("stable-cancel-key-123", request.headers["Idempotency-Key"])
                """{"status":"active","can_cancel":false}"""
            }
            else -> {
                assertEquals("restricted", request.headers["X-Account-Deletion-Context"])
                """{"status":"pending","can_cancel":true,"grace_expires_at":"2099-01-01T00:00:00Z"}"""
            }
        }
        respond("""{"meta":{"code":"ACCOUNT_RECOVERY_REQUIRED"},"error":null,"data":$data}""", if (login) status else HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    })
    val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
    val session = TravelerSession(api, object : RefreshCredentialStore {
        override fun read(): String? = null
        override fun write(credential: String) { storedCredentials++ }
        override fun clear() = Unit
    }, "en")
}
