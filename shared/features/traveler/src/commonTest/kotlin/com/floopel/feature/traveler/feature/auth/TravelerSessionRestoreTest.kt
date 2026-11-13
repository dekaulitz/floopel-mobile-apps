package com.floopel.feature.traveler.feature.auth

import com.floopel.feature.traveler.core.network.AppPlatform
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.domain.repository.RefreshCredentialStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Restoration tests distinguish a pending/temporary failure from a terminal loss of identity. */
@OptIn(ExperimentalCoroutinesApi::class)
class TravelerSessionRestoreTest {
    @Test fun emptyCredentialRevokesPersistedEligibilityWithoutAnInMemoryOwner() = runTest {
        val store = Store()
        var eligible = true
        withSession(store, onLogout = { eligible = false }) { session ->
            assertTrue(eligible)
            session.restore()
            assertFalse(eligible)
            assertFalse(session.view.value.signedIn)
            assertFalse(session.view.value.busy)
            assertNull(session.view.value.failure)
            assertEquals(1L, session.identityGeneration)
        }
    }

    @Test fun cleanupStillRunsWhenClearingAnEmptyCredentialStoreFails() = runTest {
        val store = Store().apply { failClear = true }
        var revoked = false
        withSession(store, onLogout = { revoked = true }) { session ->
            session.restore()
            assertTrue(revoked)
            assertFalse(session.view.value.signedIn)
            assertEquals(0, session.view.value.failure?.status)
        }
    }

    @Test fun pendingAndSuccessfulRestoreKeepTheExistingOwnerEligible() = runTest {
        val refresh = CompletableDeferred<Unit>()
        var revocations = 0
        withSession(Store("saved-refresh"), onLogout = { revocations++ }, beforeRefresh = { refresh.await() }) { session ->
            val restore = launch { session.restore() }
            runCurrent()
            assertTrue(session.view.value.busy)
            assertEquals(0, revocations)
            refresh.complete(Unit)
            restore.join()
            assertTrue(session.view.value.signedIn)
            assertEquals(0, revocations)
            assertEquals(0L, session.identityGeneration)
        }
    }

    @Test fun unreadableStorageDisposesEligibilityInsteadOfKeepingAnUnrecoverableOwner() = runTest {
        val store = Store("unreadable-refresh").apply { failRead = true }
        var revoked = false
        withSession(store, onLogout = { revoked = true }) { session ->
            session.restore()
            assertTrue(revoked)
            assertNull(store.value)
            assertFalse(session.view.value.signedIn)
            assertEquals(0, session.view.value.failure?.status)
        }
    }

    @Test fun cleanupCallbackFailureStillLeavesTheSessionSignedOutWithRecovery() = runTest {
        withSession(Store(), onLogout = { error("Synthetic native cleanup failure") }) { session ->
            session.restore()
            assertFalse(session.view.value.signedIn)
            assertFalse(session.view.value.busy)
            assertEquals(0, session.view.value.failure?.status)
        }
    }

    @Test fun maintenanceAndRateLimitPreserveCredentialAndCanRestoreAfterExplicitRetry() = runTest {
        for (status in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.TooManyRequests)) {
            val store = Store("saved-refresh")
            var response = status
            var revocations = 0
            var now = 0L
            withSession(store, { now }, { revocations++ }, { response }) { session ->
                session.restore()
                assertEquals("saved-refresh", store.value)
                assertEquals(0, revocations)
                assertEquals(status.value, session.view.value.failure?.status)
                response = HttpStatusCode.OK
                now = 1_001L
                session.restore()
                assertTrue(session.view.value.signedIn)
                assertEquals("successor-refresh", store.value)
                assertEquals(0, revocations)
            }
        }
    }

    @Test fun revokedRefreshClearsCredentialAndRevokesLocalEligibility() = runTest {
        val store = Store("revoked-refresh")
        var revocations = 0
        withSession(store, onLogout = { revocations++ }, status = { HttpStatusCode.Unauthorized }) { session ->
            session.restore()
            assertNull(store.value)
            assertEquals(1, revocations)
            assertFalse(session.view.value.signedIn)
            assertEquals(401, session.view.value.failure?.status)
        }
    }

    @Test fun oldRestoreCannotRevokeAnIdentityCommittedAfterLogout() = runTest {
        val refresh = CompletableDeferred<Unit>()
        var revocations = 0
        withSession(Store("saved-refresh"), onLogout = { revocations++ }, beforeRefresh = {
            withContext(NonCancellable) { refresh.await() }
        }) { session ->
            val restore = launch { session.restore() }
            runCurrent()
            session.logout()
            session.login("fixture@example.invalid", "fixture")
            val next = session.identityGeneration
            refresh.complete(Unit)
            restore.join()
            assertTrue(session.view.value.signedIn)
            assertEquals(next, session.identityGeneration)
            assertEquals(1, revocations)
        }
    }

    @Test fun cancelledRefreshRevokesEligibilityBecauseRotationMayHaveCommitted() = runTest {
        var revocations = 0
        withSession(Store("saved-refresh"), onLogout = { revocations++ }, beforeRefresh = { awaitCancellation() }) { session ->
            val restore = launch { session.restore() }
            runCurrent()
            restore.cancelAndJoin()
            assertFalse(session.view.value.signedIn)
            assertEquals(1, revocations)
        }
    }

    private suspend fun TestScope.withSession(
        store: Store,
        now: () -> Long = { 0L },
        onLogout: () -> Unit = {},
        status: () -> HttpStatusCode = { HttpStatusCode.OK },
        beforeRefresh: suspend () -> Unit = {},
        test: suspend TestScope.(TravelerSession) -> Unit,
    ) {
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                when {
                    request.url.encodedPath.endsWith("/refresh") -> {
                        beforeRefresh()
                        if (status() == HttpStatusCode.OK) respond("""{"data":$tokens,"error":null}""")
                        else respond("""{"data":null,"error":null}""", status(), headersOf(HttpHeaders.RetryAfter, "1"))
                    }
                    request.url.encodedPath.endsWith("/login") -> respond("""{"data":{"tokens":$tokens},"error":null}""")
                    request.url.encodedPath.endsWith("/logout") -> respond("", HttpStatusCode.NoContent)
                    else -> respond("""{"data":{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]},"error":null}""")
                }
            }
        }))
        try { test(TravelerSession(NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID), store, "en", now, onLogout = onLogout)) }
        finally { client.close() }
    }

    private class Store(var value: String? = null) : RefreshCredentialStore {
        var failClear = false
        var failRead = false
        override fun read(): String? { check(!failRead); return value }
        override fun write(credential: String) { value = credential }
        override fun clear() { check(!failClear); value = null }
    }

    private companion object {
        const val tokens = """{"token_type":"Bearer","access_token":"fixture-access","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"successor-refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}"""
    }
}
