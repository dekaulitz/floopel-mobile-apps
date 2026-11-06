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
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NativeErasureApiTest {
    @Test fun requestResponseLossKeepsTheKeyAndDoesNotAutomaticallyRepeatTheMutation() = runTest {
        val writes = mutableListOf<Pair<String?,String?>>()
        var lost = true
        val client = HttpClient(MockEngine { request ->
            val data = when {
                request.url.encodedPath.endsWith("/logout") -> "{}"
                request.url.encodedPath.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"identity","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
                request.url.encodedPath.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
                request.url.encodedPath.endsWith("-context") -> {
                    assertEquals("Bearer identity",request.headers[HttpHeaders.Authorization])
                    """{"context_token":"limited","purpose":"request","expires_at":"2099-01-01T00:00:00Z"}"""
                }
                else -> {
                    writes += request.headers["Idempotency-Key"] to request.headers[HttpHeaders.Authorization]
                    assertEquals("limited",request.headers["X-Account-Deletion-Context"])
                    if(lost) error("Synthetic response loss")
                    """{"status":"pending","can_cancel":true,"grace_expires_at":"2099-01-01T00:00:00Z"}"""
                }
            }
            respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val api=NativeApi(client,"https://fixture.invalid",AppPlatform.ANDROID)
        val session=TravelerSession(api,object:RefreshCredentialStore {override fun read():String?=null;override fun write(credential:String)=Unit;override fun clear()=Unit},"en")
        try {
            session.login("fixture@example.invalid","fixture-password")
            val repository=NativeErasureApi(api.catalogTransport(),session)
            assertIs<CatalogResult.Ready<Unit>>(repository.authenticate("fixture@example.invalid","fixture-password"))
            assertEquals(CatalogResult.NetworkError,repository.request("stable-request-key"))
            assertEquals(1,writes.size)
            assertEquals(CatalogResult.HttpError(409),repository.request("different-request-key"))
            lost=false
            assertEquals("pending",assertIs<CatalogResult.Ready<AccountErasureState>>(repository.request("stable-request-key")).value.status)
            assertEquals(listOf("stable-request-key","stable-request-key"),writes.map{it.first})
            assertEquals(listOf("Bearer identity",null),writes.map{it.second})
            session.logout()
            assertEquals(CatalogResult.HttpError(401),repository.request("stable-request-key"))
        } finally {api.close()}
    }
    @Test fun restrictedRecoveryNeverCreatesNormalIdentityAndClearsItsCapability() = runTest {
        val requests = mutableListOf<Pair<String, String?>>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("IOS", request.headers["X-App-Platform"])
            assertEquals("2.4.1-test", request.headers["X-App-Version"])
            requests += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
            val data = when {
                request.url.encodedPath.endsWith("/account-deletion-context") -> """{"context_token":"limited","purpose":"cancel","expires_at":"2099-01-01T00:00:00Z"}"""
                request.url.encodedPath.endsWith("/cancellation") -> """{"status":"active","can_cancel":false}"""
                else -> """{"status":"pending","can_cancel":true,"grace_expires_at":"2099-01-01T00:00:00Z"}"""
            }
            if (!request.url.encodedPath.endsWith("-context")) assertEquals("limited",request.headers["X-Account-Deletion-Context"])
            respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
        })
        val api = NativeApi(client,"https://fixture.invalid",AppPlatform.IOS, appVersion = "2.4.1-test")
        val session = TravelerSession(api, object:RefreshCredentialStore {override fun read():String?=null;override fun write(credential:String)=error("Restricted recovery must not store credentials");override fun clear()=Unit},"en")
        try {
            val repository=NativeErasureApi(api.catalogTransport(),session)
            assertIs<CatalogResult.Ready<Unit>>(repository.authenticate("fixture@example.invalid","fixture-password"))
            assertTrue(assertIs<CatalogResult.Ready<AccountErasureState>>(repository.state()).value.canCancel)
            assertEquals("active",assertIs<CatalogResult.Ready<AccountErasureState>>(repository.cancel("same-cancellation-key")).value.status)
            assertFalse(session.view.value.signedIn)
            assertTrue(requests.all { it.second==null })
            repository.clear()
            assertEquals(CatalogResult.HttpError(401),repository.cancel("same-cancellation-key"))
        } finally {api.close()}
    }
}
