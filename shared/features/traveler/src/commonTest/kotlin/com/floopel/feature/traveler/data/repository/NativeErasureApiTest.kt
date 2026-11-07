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
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NativeErasureApiTest {
    @Test fun rejectedReauthenticationVerifiesTheBearerWithoutReplayingOrDiscardingAValidSession() = runTest {
        for (probeOutcome in listOf("ok", "maintenance", "throttled", "offline", "invalid")) {
        var authentications = 0
        var probes = 0
        var refreshes = 0
        val api = NativeApi(HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val data = when {
                path.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"identity","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
                path.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
                path.endsWith("-context") -> {
                    authentications++; assertEquals("Bearer identity", request.headers[HttpHeaders.Authorization])
                    return@MockEngine respond("{}", HttpStatusCode.Unauthorized)
                }
                path.endsWith("/me") -> {
                    probes++; assertEquals(HttpMethod.Get, request.method); assertEquals("Bearer identity", request.headers[HttpHeaders.Authorization])
                    when (probeOutcome) {
                        "maintenance" -> return@MockEngine respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.RetryAfter, "30"))
                        "throttled" -> return@MockEngine respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "30"))
                        "offline" -> error("Synthetic probe connection failure")
                        "invalid" -> return@MockEngine respond("{}", HttpStatusCode.Unauthorized)
                    }
                    "{}"
                }
                path.endsWith("/refresh") -> { refreshes++; return@MockEngine respond("{}", HttpStatusCode.Unauthorized) }
                else -> error("No deletion mutation or implicit authentication replay is allowed")
            }
            respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }), "https://fixture.invalid", AppPlatform.ANDROID)
        val session = TravelerSession(api, object : RefreshCredentialStore {
            override fun read(): String? = null; override fun write(credential: String) = Unit; override fun clear() = Unit
        }, "id")
        try {
            session.login("fixture@example.invalid", "fixture-password"); val epoch = session.identityGeneration
            val repository = NativeErasureApi(api.catalogTransport(), session)
            assertEquals(CatalogResult.HttpError(401), repository.authenticate("fixture@example.invalid", "incorrect-fixture-password"))
            if (probeOutcome == "invalid") {
                assertFalse(session.view.value.signedIn); assertTrue(session.identityGeneration > epoch)
                assertEquals(1, refreshes)
            } else {
                assertTrue(session.view.value.signedIn, probeOutcome); assertEquals(epoch, session.identityGeneration, probeOutcome)
                assertEquals(0, refreshes)
                if (probeOutcome in setOf("maintenance", "throttled")) assertTrue(session.view.value.retryAtMillis > 0)
            }
            assertEquals(1, authentications); assertEquals(1, probes)
            assertEquals(CatalogResult.HttpError(401), repository.request("synthetic-original-key"))
        } finally { api.close() }
        }
    }
    @Test fun rejectedAndUncommittedRequestsReconcileActiveWithBearerAndRetryOnlyTheOriginalKey() = runTest {
        for (lost in listOf(false, true)) {
            var writes = 0
            var restrictedReads = 0
            val keys = mutableListOf<String?>()
            val client = HttpClient(MockEngine { request ->
                val path = request.url.encodedPath
                val data = when {
                    path.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"identity","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
                    path.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
                    path.endsWith("-context") -> """{"context_token":"limited","purpose":"request","expires_at":"2099-01-01T00:00:00Z"}"""
                    request.method == HttpMethod.Get -> {
                        if (request.headers[HttpHeaders.Authorization] == null) {
                            restrictedReads++; return@MockEngine respond("{}", HttpStatusCode.Forbidden)
                        }
                        """{"status":"active","can_cancel":false,"eligibility":{"can_request":true,"blocking_reason":null}}"""
                    }
                    else -> {
                        assertEquals("Bearer identity", request.headers[HttpHeaders.Authorization])
                        writes++; keys += request.headers["Idempotency-Key"]
                        if (writes == 1) {
                            if (lost) error("Synthetic loss before commit")
                            return@MockEngine respond("{}", HttpStatusCode.Conflict)
                        }
                        """{"status":"pending","can_cancel":true,"grace_expires_at":null}"""
                    }
                }
                respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            })
            val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
            val session = TravelerSession(api, object : RefreshCredentialStore {
                override fun read(): String? = null; override fun write(credential: String) = Unit; override fun clear() = Unit
            }, "id")
            try {
                session.login("fixture@example.invalid", "fixture-password")
                val repository = NativeErasureApi(api.catalogTransport(), session)
                assertIs<CatalogResult.Ready<Unit>>(repository.authenticate("fixture@example.invalid", "fixture-password"))
                assertEquals("active", assertIs<CatalogResult.Ready<AccountErasureState>>(repository.state()).value.status)
                assertFalse(repository.request("synthetic-original-key") is CatalogResult.Ready)
                assertEquals("active", assertIs<CatalogResult.Ready<AccountErasureState>>(repository.state()).value.status)
                assertEquals(1, writes); assertEquals(1, restrictedReads)
                assertEquals("pending", assertIs<CatalogResult.Ready<AccountErasureState>>(repository.request("synthetic-original-key")).value.status)
                assertEquals(listOf<String?>("synthetic-original-key", "synthetic-original-key"), keys.toList())
            } finally { api.close() }
        }
    }
    @Test fun activeAuthenticateThenPreflightUsesBearerAndNeverRequestsDeletion() = runTest {
        var reads = 0
        var deletionWrites = 0
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val data = when {
                path.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"identity","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
                path.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
                path.endsWith("-context") -> """{"context_token":"limited","purpose":"request","expires_at":"2099-01-01T00:00:00Z"}"""
                path.endsWith("/account-deletion") -> {
                    if (request.method != HttpMethod.Get) deletionWrites++
                    assertEquals(HttpMethod.Get, request.method)
                    reads++
                    // Mirrors the contract: active restricted-only preflight is forbidden.
                    if (request.headers[HttpHeaders.Authorization] != "Bearer identity")
                        return@MockEngine respond("{}", HttpStatusCode.Forbidden)
                    """{"status":"active","can_cancel":false,"eligibility":{"can_request":true,"blocking_reason":null}}"""
                }
                else -> error("Unexpected request $path")
            }
            respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
        val session = TravelerSession(api, object : RefreshCredentialStore {
            override fun read(): String? = null; override fun write(credential: String) = Unit; override fun clear() = Unit
        }, "id")
        try {
            session.login("fixture@example.invalid", "fixture-password")
            val repository = NativeErasureApi(api.catalogTransport(), session)
            assertIs<CatalogResult.Ready<Unit>>(repository.authenticate("fixture@example.invalid", "fixture-password"))
            repeat(2) { assertTrue(assertIs<CatalogResult.Ready<AccountErasureState>>(repository.state()).value.canRequest) }
            assertEquals(2, reads); assertEquals(0, deletionWrites)
        } finally { api.close() }
    }

    @Test fun closeDuringReauthenticationCannotPublishALateCapability() = runTest {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine {
            started.complete(Unit); release.await()
            respond("""{"meta":{"code":"OK"},"error":null,"data":{"context_token":"limited","purpose":"cancel","expires_at":"2099-01-01T00:00:00Z"}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
        val session = TravelerSession(api, object : RefreshCredentialStore {
            override fun read(): String? = null; override fun write(credential: String) = Unit; override fun clear() = Unit
        }, "id")
        try {
            val repository = NativeErasureApi(api.catalogTransport(), session)
            val auth = async { repository.authenticate("fixture@example.invalid", "fixture-password") }
            started.await(); repository.clear(); release.complete(Unit)
            assertEquals(CatalogResult.InvalidResponse, auth.await())
            assertEquals(CatalogResult.HttpError(401), repository.cancel("same-cancellation-key"))
        } finally { api.close() }
    }
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
                request.method == HttpMethod.Get -> {
                    assertNull(request.headers[HttpHeaders.Authorization])
                    assertEquals("limited", request.headers["X-Account-Deletion-Context"])
                    """{"status":"pending","can_cancel":true,"grace_expires_at":null}"""
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
            assertEquals("pending", assertIs<CatalogResult.Ready<AccountErasureState>>(repository.state()).value.status)
            assertEquals(1, writes.size)
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
    @Test fun erasingRecoveryUsesStatusProofWithoutBearerOrCancellation() = runTest {
        var calls = 0
        val api = NativeApi(HttpClient(MockEngine { request ->
            calls++; assertNull(request.headers[HttpHeaders.Authorization])
            val data = if (request.url.encodedPath.endsWith("-context"))
                """{"context_token":"status-only","purpose":"status","expires_at":"2099-01-01T00:00:00Z"}"""
            else {
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("status-only", request.headers["X-Account-Deletion-Context"])
                """{"status":"erasing","can_cancel":false,"grace_expires_at":null}"""
            }
            respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }), "https://fixture.invalid", AppPlatform.ANDROID)
        val session = TravelerSession(api, object : RefreshCredentialStore {
            override fun read(): String? = null; override fun write(credential: String) = error("No normal credentials allowed"); override fun clear() = Unit
        }, "id")
        try {
            val repository = NativeErasureApi(api.catalogTransport(), session)
            assertIs<CatalogResult.Ready<Unit>>(repository.authenticate("fixture@example.invalid", "fixture-password"))
            assertEquals("erasing", assertIs<CatalogResult.Ready<AccountErasureState>>(repository.state()).value.status)
            assertEquals(CatalogResult.HttpError(401), repository.cancel("synthetic-cancellation-key"))
            assertFalse(session.view.value.signedIn); assertEquals(2, calls)
        } finally { api.close() }
    }
}
