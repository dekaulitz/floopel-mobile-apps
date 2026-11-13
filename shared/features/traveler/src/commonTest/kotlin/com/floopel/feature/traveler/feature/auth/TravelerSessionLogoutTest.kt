package com.floopel.feature.traveler.feature.auth

import com.floopel.feature.traveler.core.network.AppPlatform
import com.floopel.feature.traveler.core.network.ApiFailure
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.domain.repository.RefreshCredentialStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TravelerSessionLogoutTest {
    @Test fun anExpiredSessionDuringBindingCleanupStillClearsPrivateRecovery() = runTest {
        val store = Store()
        var privateCleanup = 0
        val client = client()
        lateinit var session: TravelerSession
        session = TravelerSession(NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID), store, "en", { 0L },
            onLogout = { privateCleanup++ }, beforeLogout = {
                runCatching { session.withIdentity(false) { throw ApiFailure(401, null, null) } }
            })
        try {
            session.login("fixture@example.invalid", "fixture")
            session.logout()
            assertFalse(session.view.value.signedIn)
            assertNull(store.value)
            assertEquals(1, privateCleanup)
        } finally { client.close() }
    }
    @Test fun nativeCleanupRetainsAuthorizationWithoutHoldingTheSessionGuard() = runTest {
        val events = mutableListOf<String>()
        val store = Store()
        val client = client { events += "server" }
        lateinit var session: TravelerSession
        session = TravelerSession(NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID), store, "en", { 0L },
            onLogout = { events += "local" }, beforeLogout = {
                assertTrue(session.view.value.signedIn)
                assertNotNull(store.value)
                session.withIdentity(false) { assertEquals("fixture-access", it); events += "authorized-before" }
            })
        try {
            session.login("fixture@example.invalid", "fixture")
            session.logout()
            assertEquals(listOf("authorized-before", "local", "server"), events)
            assertFalse(session.view.value.signedIn)
            assertNull(store.value)
        } finally { client.close() }
    }

    @Test fun cancellingTheCallerStillDisposesCredentialsAfterBoundedNativeCleanup() = runTest {
        val started = CompletableDeferred<Unit>()
        val store = Store()
        val client = client()
        val session = TravelerSession(NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID), store, "en", { 0L },
            beforeLogout = { started.complete(Unit); awaitCancellation() })
        try {
            session.login("fixture@example.invalid", "fixture")
            val logout = launch { session.logout() }
            started.await(); logout.cancel()
            advanceUntilIdle(); logout.join()
            assertTrue(logout.isCancelled)
            assertFalse(session.view.value.signedIn)
            assertNull(store.value)
            assertEquals(LogoutLifecyclePolicy.CleanupMillis, testScheduler.currentTime)
        } finally { client.close() }
    }

    @Test fun nativeCleanupFailureCannotPreventSignOut() = runTest {
        val store = Store()
        var revoked = false
        val client = client { revoked = true }
        val session = TravelerSession(NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID), store, "en", { 0L },
            beforeLogout = { error("Provider unavailable") })
        try {
            session.login("fixture@example.invalid", "fixture")
            session.logout()
            assertTrue(revoked)
            assertFalse(session.view.value.signedIn)
            assertNull(store.value)
        } finally { client.close() }
    }

    @Test fun previousLogoutCannotClearAnIdentityCommittedDuringNativeCleanup() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val store = Store()
        val client = client()
        val session = TravelerSession(NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID), store, "en", { 0L },
            beforeLogout = { started.complete(Unit); finish.await() })
        try {
            session.login("first@example.invalid", "fixture")
            val logout = launch { session.logout() }
            started.await()
            session.login("second@example.invalid", "fixture")
            val next = session.identityGeneration
            finish.complete(Unit); logout.join()
            assertTrue(session.view.value.signedIn)
            assertEquals(next, session.identityGeneration)
            assertNotNull(store.value)
        } finally { client.close() }
    }

    @Test fun refreshFamilyRevocationCannotHoldLocalSignOutIndefinitely() = runTest {
        val store = Store()
        val client = client { awaitCancellation() }
        val session = TravelerSession(NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID), store, "en", { 0L })
        try {
            session.login("fixture@example.invalid", "fixture")
            val logout = launch { session.logout() }
            runCurrent()
            assertFalse(session.view.value.signedIn)
            assertNull(store.value)
            advanceUntilIdle(); logout.join()
            assertEquals(LogoutLifecyclePolicy.RevokeMillis, testScheduler.currentTime)
        } finally { client.close() }
    }

    private fun TestScope.client(onRevoke: suspend () -> Unit = {}) = HttpClient(MockEngine(MockEngineConfig().apply {
        dispatcher = StandardTestDispatcher(testScheduler)
        addHandler { request ->
        when {
            request.url.encodedPath.endsWith("/login") -> respond("""{"error":null,"data":{"tokens":{"token_type":"Bearer","access_token":"fixture-access","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"fixture-refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}}""")
            request.url.encodedPath.endsWith("/logout") -> { onRevoke(); respond("", HttpStatusCode.NoContent) }
            else -> respond("""{"error":null,"data":{"level":1,"current_xp":0,"xp_to_next_level":100,"badges":[],"quests":[]}}""")
        }
        }
    }))

    private class Store : RefreshCredentialStore {
        var value: String? = null
        override fun read() = value
        override fun write(credential: String) { value = credential }
        override fun clear() { value = null }
    }
}
