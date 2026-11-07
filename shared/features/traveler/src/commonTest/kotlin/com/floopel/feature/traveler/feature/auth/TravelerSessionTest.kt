package com.floopel.feature.traveler.feature.auth

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.data.remote.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TravelerSessionTest {
    private class Store(var credential: String? = null) : RefreshCredentialStore {
        var writes = 0

        override fun read() = credential

        override fun write(credential: String) {
            writes++
            this.credential = credential
        }

        override fun clear() {
            credential = null
        }
    }

    private val tokens =
        """{"token_type":"Bearer","access_token":"access","access_expires_at":"2026-10-14T12:00:00Z","refresh_token":"successor","refresh_expires_at":"2026-11-14T12:00:00Z"}"""

    @Test
    fun replacingQuestFiltersRejectsThePreviousPageAndKeepsNewCriteriaForNextPage() = runTest {
        val pending = CompletableDeferred<Unit>()
        val queries = mutableListOf<io.ktor.http.Url>()
        val client =
            HttpClient(
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/login") ->
                            respond("""{"error":null,"data":{"tokens":$tokens}}""")
                        request.url.encodedPath.endsWith("/quests") -> {
                            queries += request.url
                            val page = request.url.parameters["page"]!!.toInt()
                            val filtered = request.url.parameters["min_xp"] != null
                            if (page == 2 && !filtered) pending.await()
                            val id = if (filtered) "filtered-$page" else "old-$page"
                            respond(
                                """{"error":null,"meta":{"pagination":{"page":$page,"total_pages":2}},"data":[{"id":"$id","slug":"$id","locations":[],"title":"$id","availability":"active","execution_status":"available","activity_count":1,"reward_preview":{"xp":200,"badge":null},"quota":null}]}"""
                            )
                        }
                        else -> respond(passport)
                    }
                }
            )
        try {
            val session =
                TravelerSession(
                    NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID),
                    Store(),
                    "en",
                    { 0 },
                )
            session.login("fixture@example.invalid", "password")
            session.loadQuests(1)
            val stale = launch { session.loadQuests(2) }
            runCurrent()
            val filter = QuestDiscoveryFilter(minXP = 100)
            session.loadQuests(1, filter)
            pending.complete(Unit)
            stale.join()
            assertEquals(listOf("filtered-1"), session.view.value.questPage!!.items.map { it.id })
            val before = queries.size
            session.loadQuests(2, QuestDiscoveryFilter())
            assertEquals(before, queries.size)
            session.loadQuests(2)
            assertEquals(
                listOf("filtered-1", "filtered-2"),
                session.view.value.questPage!!.items.map { it.id },
            )
            assertEquals(filter, session.view.value.questFilter)
            assertEquals("100", queries.last().parameters["min_xp"])
        } finally {
            client.close()
        }
    }

    @Test
    fun questFeedAppendsDeduplicatesAndRetainsItemsUntilExplicitRetry() = runTest {
        var fail = true
        var now = 0L
        val client =
            HttpClient(
                MockEngine { request ->
                    when {
                        request.url.encodedPath.endsWith("/login") ->
                            respond("""{"error":null,"data":{"tokens":$tokens}}""")
                        request.url.encodedPath.endsWith("/quests") -> {
                            val page = request.url.parameters["page"]!!.toInt()
                            if (page == 2 && fail)
                                respond(
                                    "unavailable",
                                    HttpStatusCode.ServiceUnavailable,
                                    headersOf(HttpHeaders.RetryAfter, "1"),
                                )
                            else {
                                fun quest(id: String) =
                                    """{"id":"$id","slug":"$id","locations":[],"title":"$id","availability":"active","execution_status":"available","activity_count":1,"reward_preview":{"xp":10,"badge":null},"quota":null}"""
                                val rows =
                                    if (page == 1) quest("a") else quest("a") + "," + quest("b")
                                respond(
                                    """{"error":null,"meta":{"pagination":{"page":$page,"total_pages":2}},"data":[$rows]}"""
                                )
                            }
                        }
                        else -> respond(passport)
                    }
                }
            )
        val session =
            TravelerSession(
                NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID),
                Store(),
                "en",
                { now },
            )
        try {
            session.login("fixture@example.invalid", "password")
            session.loadQuests(1)
            session.loadQuests(2)
            assertEquals(listOf("a"), session.view.value.questPage!!.items.map { it.id })
            assertEquals(503, session.view.value.failure?.status)
            fail = false
            now = 1001
            session.retryRead()
            assertEquals(listOf("a", "b"), session.view.value.questPage!!.items.map { it.id })
            assertEquals(2, session.view.value.questPage!!.pagination.page)
            session.loadQuests(1)
            assertEquals(listOf("a"), session.view.value.questPage!!.items.map { it.id })
        } finally {
            client.close()
        }
    }

    @Test
    fun lateLoginCannotRestoreLoggedOutState() = runTest {
        val response = CompletableDeferred<Unit>()
        val engine = MockEngine {
            response.await()
            respond("""{"meta":{"code":"OK"},"error":null,"data":{"tokens":$tokens}}""")
        }
        val store = Store()
        val session =
            TravelerSession(
                NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.ANDROID),
                store,
                "en",
            )
        val login = launch { session.login("fixture@example.invalid", "password") }
        runCurrent()
        session.logout()
        response.complete(Unit)
        login.join()
        assertFalse(session.view.value.signedIn)
        assertNull(store.credential)
        assertEquals(0, store.writes)
    }

    @Test
    fun maintenanceKeepsProtectedRefreshAndDoesNotRepeatRefresh() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            respond(
                "maintenance",
                HttpStatusCode.ServiceUnavailable,
                headersOf(HttpHeaders.RetryAfter, "60"),
            )
        }
        val store = Store("existing")
        val session =
            TravelerSession(
                NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.IOS),
                store,
                "id",
            )
        session.restore()
        session.restore()
        assertEquals("existing", store.credential)
        assertEquals(503, session.view.value.failure?.status)
        assertEquals(1, requests)
    }

    @Test
    fun uncertainRefreshRequiresExplicitSignInInsteadOfReusingOldToken() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests++
            throw IllegalStateException("network failed")
        }
        val store = Store("old")
        val session =
            TravelerSession(
                NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.IOS),
                store,
                "id",
            )
        session.restore()
        session.restore()
        assertNull(store.credential)
        assertFalse(session.view.value.signedIn)
        assertEquals(1, requests)
    }

    private val passport =
        """{"meta":{"code":"OK"},"error":null,"data":{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}}"""

    @Test
    fun homeRefreshRecoversLevelAfterInitialPassportReadFailed() = runTest {
        var reads = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/login"))
                respond("""{"error":null,"data":{"tokens":$tokens}}""")
            else if (++reads == 1) respond("{}", HttpStatusCode.InternalServerError)
            else respond(passport)
        }
        val session =
            TravelerSession(
                NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.ANDROID),
                Store(),
                "en",
                { 0 },
            )
        session.login("fixture@example.invalid", "password")
        assertTrue(session.view.value.signedIn)
        assertNull(session.view.value.progress)
        assertFalse(session.view.value.busy)
        session.refreshPassportProgress()
        assertEquals(2, reads)
        assertEquals(1, session.view.value.progress?.level?.level)
        assertEquals(1000L, session.view.value.progress?.level?.xpToNextLevel)
        assertNull(session.view.value.failure)
    }

    @Test
    fun homeRefreshHonorsCooldownBeforeRetryingMissingProgress() = runTest {
        var clock = 0L
        var reads = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/login"))
                respond("""{"error":null,"data":{"tokens":$tokens}}""")
            else if (++reads == 1)
                respond(
                    "{}",
                    HttpStatusCode.ServiceUnavailable,
                    headersOf(HttpHeaders.RetryAfter, "60"),
                )
            else respond(passport)
        }
        val session =
            TravelerSession(
                NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.ANDROID),
                Store(),
                "en",
                { clock },
            )
        session.login("fixture@example.invalid", "password")
        session.refreshPassportProgress()
        assertEquals(1, reads)
        assertNull(session.view.value.progress)
        clock = 60_000
        session.refreshPassportProgress()
        assertEquals(2, reads)
        assertNotNull(session.view.value.progress)
    }

    @Test
    fun failedHomeRefreshKeepsLastKnownLevelAndXp() = runTest {
        var fail = false
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/login"))
                respond("""{"error":null,"data":{"tokens":$tokens}}""")
            else if (fail) respond("{}", HttpStatusCode.InternalServerError) else respond(passport)
        }
        val session =
            TravelerSession(
                NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.ANDROID),
                Store(),
                "en",
                { 0 },
            )
        session.login("fixture@example.invalid", "password")
        val previous = session.view.value.progress
        val screen = session.view.value.screen
        fail = true
        session.refreshPassportProgress()
        assertEquals(previous, session.view.value.progress)
        assertEquals(screen, session.view.value.screen)
        assertTrue(session.view.value.signedIn)
        assertFalse(session.view.value.busy)
    }

    @Test
    fun logoutDiscardsLateHomeProgressWithoutRestoringPrivateData() = runTest {
        val release = CompletableDeferred<Unit>()
        var hold = false
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/login") ->
                    respond("""{"error":null,"data":{"tokens":$tokens}}""")
                request.url.encodedPath.endsWith("/logout") -> respond("", HttpStatusCode.NoContent)
                else -> {
                    if (hold) release.await()
                    respond(passport)
                }
            }
        }
        val session =
            TravelerSession(
                NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.ANDROID),
                Store(),
                "en",
                { 0 },
            )
        session.login("fixture@example.invalid", "password")
        hold = true
        val refresh = launch { session.refreshPassportProgress() }
        runCurrent()
        session.logout()
        release.complete(Unit)
        refresh.join()
        assertFalse(session.view.value.signedIn)
        assertNull(session.view.value.progress)
    }

    @Test
    fun concurrentUnauthorizedReadsRotateOnceAndUseStoredSuccessor() = runTest {
        var reject = false
        var rotations = 0
        val release = CompletableDeferred<Unit>()
        val store = Store()
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/login") ->
                    respond("""{"error":null,"data":{"tokens":$tokens}}""")
                request.url.encodedPath.endsWith("/refresh") -> {
                    rotations++
                    release.await()
                    respond(
                        """{"error":null,"data":${tokens.replace("access\"", "renewed\"").replace("successor", "rotated")}}"""
                    )
                }
                reject && request.headers["Authorization"] == "Bearer access" ->
                    respond("{}", HttpStatusCode.Unauthorized)
                else -> respond(passport)
            }
        }
        val session =
            TravelerSession(
                NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.ANDROID),
                store,
                "en",
                { 0 },
            )
        session.login("fixture@example.invalid", "password")
        reject = true
        val first = launch { session.loadPassport() }
        val second = launch { session.loadPassport() }
        runCurrent()
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, rotations)
        assertEquals("rotated", store.credential)
        assertTrue(session.view.value.signedIn)
        assertNotNull(session.view.value.progress)
    }

    @Test
    fun repeatedUnauthorizedReadClearsPrivateStateWithoutRefreshLoop() = runTest {
        var rotations = 0
        val store = Store()
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/login") ->
                    respond("""{"error":null,"data":{"tokens":$tokens}}""")
                request.url.encodedPath.endsWith("/refresh") -> {
                    rotations++
                    respond("""{"error":null,"data":$tokens}""")
                }
                else -> respond("{}", HttpStatusCode.Unauthorized)
            }
        }
        val session =
            TravelerSession(
                NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.IOS),
                store,
                "id",
                { 0 },
            )
        session.login("fixture@example.invalid", "password")
        assertEquals(1, rotations)
        assertFalse(session.view.value.signedIn)
        assertNull(session.view.value.progress)
        assertNull(store.credential)
        assertEquals(401, session.view.value.failure?.status)
    }

    @Test
    fun expiryRenewsBeforeReadAndLogoutDiscardsLateRefresh() = runTest {
        var clock = 0L
        var rotations = 0
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val store = Store()
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/login") ->
                    respond("""{"error":null,"data":{"tokens":$tokens}}""")
                request.url.encodedPath.endsWith("/refresh") -> {
                    rotations++
                    started.complete(Unit)
                    release.await()
                    respond("""{"error":null,"data":$tokens}""")
                }
                request.url.encodedPath.endsWith("/logout") -> respond("", HttpStatusCode.NoContent)
                else -> {
                    assertEquals(0L, clock)
                    respond(passport)
                }
            }
        }
        val session =
            TravelerSession(
                NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.ANDROID),
                store,
                "en",
                { clock },
            )
        session.login("fixture@example.invalid", "password")
        clock = kotlin.time.Instant.parse("2026-10-14T12:00:00Z").toEpochMilliseconds()
        val pending = launch { session.loadPassport() }
        started.await()
        assertEquals(1, rotations)
        session.logout()
        release.complete(Unit)
        pending.join()
        assertFalse(session.view.value.signedIn)
        assertNull(store.credential)
        assertNull(session.view.value.progress)
    }

    @Test
    fun cancellationDuringRotationDiscardsUncertainCredential() = runTest {
        val started = CompletableDeferred<Unit>()
        val store = Store("old")
        val engine = MockEngine {
            started.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        val session =
            TravelerSession(
                NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.IOS),
                store,
                "en",
                { 0 },
            )
        val pending = launch { session.restore() }
        started.await()
        pending.cancel()
        pending.join()
        assertNull(store.credential)
        assertFalse(session.view.value.busy)
        assertFalse(session.view.value.signedIn)
    }

    @Test
    fun lateQuestListCannotReplacePassportAfterNavigation() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/login") ->
                    respond("""{"error":null,"data":{"tokens":$tokens}}""")
                request.url.encodedPath.endsWith("/quests") -> {
                    started.complete(Unit)
                    release.await()
                    respond(
                        """{"error":null,"meta":{"pagination":{"page":1,"total_pages":0}},"data":[]}"""
                    )
                }
                else -> respond(passport)
            }
        }
        val session =
            TravelerSession(
                NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.IOS),
                Store(),
                "en",
                { 0 },
            )
        session.login("fixture@example.invalid", "password")
        val pending = launch { session.loadQuests() }
        started.await()
        session.loadPassport()
        release.complete(Unit)
        pending.join()
        assertEquals(TravelerScreen.PASSPORT, session.view.value.screen)
        assertNull(session.view.value.questPage)
        assertNotNull(session.view.value.progress)
    }

    @Test
    fun catalogIdentityRequiresLoginAndDiscardsResponseAfterLogout() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/login") ->
                    respond("""{"error":null,"data":{"tokens":$tokens}}""")
                request.url.encodedPath.endsWith("/logout") -> respond("", HttpStatusCode.NoContent)
                else -> respond(passport)
            }
        }
        val session =
            TravelerSession(
                NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.ANDROID),
                Store(),
                "en",
                { 0 },
            )
        var calls = 0
        assertFailsWith<ApiFailure> {
            session.withIdentity(true) {
                calls++
                "private"
            }
        }
        assertEquals(0, calls)
        session.login("fixture@example.invalid", "password")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = launch {
            assertFailsWith<ApiFailure> {
                session.withIdentity(true) {
                    started.complete(Unit)
                    release.await()
                    "old account"
                }
            }
        }
        started.await()
        session.logout()
        release.complete(Unit)
        pending.join()
        assertFalse(session.view.value.signedIn)
        assertNull(session.view.value.progress)
    }

    @Test
    fun catalogReadRefreshesOnceAndMaintenanceRetainsIdentity() = runTest {
        var rotations = 0
        val store = Store()
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/login") ->
                    respond("""{"error":null,"data":{"tokens":$tokens}}""")
                request.url.encodedPath.endsWith("/refresh") -> {
                    rotations++
                    respond(
                        """{"error":null,"data":${tokens.replace("\"access\"", "\"renewed\"")}}"""
                    )
                }
                else -> respond(passport)
            }
        }
        val session =
            TravelerSession(
                NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.ANDROID),
                store,
                "en",
                { 0 },
            )
        session.login("fixture@example.invalid", "password")
        var calls = 0
        val result =
            session.withIdentity(true) { token ->
                calls++
                if (token == "access") throw ApiFailure(401, null, null)
                "confirmed"
            }
        assertEquals("confirmed", result)
        assertEquals(2, calls)
        assertEquals(1, rotations)
        assertFailsWith<ApiFailure> {
            session.withIdentity(true) { throw ApiFailure(503, null, 60) }
        }
        assertTrue(session.view.value.signedIn)
        assertNotNull(store.credential)
    }

    @Test
    fun catalogMutationIsNotReplayedOnUnauthorized() = runTest {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/login"))
                respond("""{"error":null,"data":{"tokens":$tokens}}""")
            else respond(passport)
        }
        val session =
            TravelerSession(
                NativeApi(HttpClient(engine), "https://fixture.invalid", AppPlatform.ANDROID),
                Store(),
                "en",
                { 0 },
            )
        session.login("fixture@example.invalid", "password")
        var calls = 0
        assertFailsWith<ApiFailure> {
            session.withIdentity(false) {
                calls++
                throw ApiFailure(401, null, null)
            }
        }
        assertEquals(1, calls)
        assertFalse(session.view.value.signedIn)
    }

    @Test
    fun rateLimitAndMaintenancePreserveSessionAndNeverReplayWrites() = runTest {
        for (status in listOf(429, 503)) for (readOnly in listOf(true, false)) {
            var now = 0L
            var refreshes = 0
            val store = Store()
            val engine = MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("/login") ->
                        respond("""{"error":null,"data":{"tokens":$tokens}}""")
                    request.url.encodedPath.endsWith("/refresh") -> {
                        refreshes++
                        respond("unexpected", HttpStatusCode.BadRequest)
                    }
                    else -> respond(passport)
                }
            }
            val client = HttpClient(engine)
            try {
                val session =
                    TravelerSession(
                        NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID),
                        store,
                        "en",
                        { now },
                    )
                session.login("fixture@example.invalid", "password")
                val generation = session.identityGeneration
                val credential = store.credential
                var attempts = 0
                val failure =
                    assertFailsWith<ApiFailure> {
                        session.withIdentity(readOnly) {
                            attempts++
                            throw ApiFailure(status, null, 2)
                        }
                    }
                assertEquals(status, failure.status)
                assertEquals(2L, failure.retryAfterSeconds)
                assertTrue(session.view.value.signedIn)
                assertEquals(credential, store.credential)
                assertEquals(generation, session.identityGeneration)
                assertEquals(1, attempts)
                assertFailsWith<ApiFailure> {
                    session.withIdentity(readOnly) {
                        attempts++
                        "unexpected"
                    }
                }
                assertEquals(1, attempts)
                now = 2001
                assertEquals("recovered", session.withIdentity(readOnly) { "recovered" })
                assertTrue(session.view.value.signedIn)
                assertEquals(0, refreshes)
            } finally {
                client.close()
            }
        }
    }
}
