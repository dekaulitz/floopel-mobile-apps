package com.floopel.feature.traveler.presentation

import androidx.lifecycle.viewModelScope
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.core.network.AppPlatform
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.data.repository.NativeTravelerIdentity
import com.floopel.feature.traveler.domain.repository.RefreshCredentialStore
import com.floopel.feature.traveler.feature.auth.TravelerSession
import com.floopel.feature.traveler.testing.TravelerFixture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Real Session + NativeIdentity + VM, fresh synthetic refresh credential and the real saved-stack format. */
@OptIn(ExperimentalCoroutinesApi::class)
class QuestCompletionRestoreTest {
    private val c = "00000000-0000-4000-8000-000000002001"
    private fun runCase(block: suspend TestScope.(Fixture, TravelerViewModel) -> Unit) = runTest {
        val ui = StandardTestDispatcher(testScheduler); Dispatchers.setMain(ui)
        val f = Fixture(ui); val m = TravelerViewModel(f.runtime, Json.encodeToString(listOf(
            TravelerRoute(Destination.HOME), TravelerRoute(Destination.QUEST_PROGRESS, c), TravelerRoute(Destination.PASSPORT))), worker = ui)
        try { advanceUntilIdle(); block(f, m) } finally { m.viewModelScope.cancel(); f.client.close(); Dispatchers.resetMain() }
    }
    private fun assertCompleted(m: TravelerViewModel) {
        assertEquals(Destination.QUEST_PROGRESS, m.state.value.route.destination)
        assertEquals(c, m.state.value.route.id); assertEquals(c, m.state.value.identity.running?.id)
        assertTrue(m.state.value.identity.completed); assertEquals(2, m.state.value.identity.running?.completed)
        assertEquals(2, m.state.value.identity.running?.total)
    }
    @Test fun freshSessionRestoresPassportThenBackReadsTheCompletedOwnerOutsideTheActiveCache() = runCase { f, m ->
        assertEquals(Destination.PASSPORT, m.state.value.route.destination); assertTrue(m.state.value.identity.signedIn)
        assertNotNull(m.state.value.identity.passport); assertTrue(m.state.value.identity.passport?.running.orEmpty().isEmpty())
        assertNull(m.state.value.identity.running); assertTrue(f.reads.isEmpty())
        m.back(); advanceUntilIdle(); assertCompleted(m); assertEquals(listOf(c), f.reads)
        assertEquals(0, f.businessWrites)
        val restored = Json.decodeFromString<List<TravelerRoute>>(m.savedStack())
        assertEquals(TravelerRoute(Destination.QUEST_PROGRESS, c), restored.last())
    }
    @Test fun sameSessionPassportRoundTripStillReopensTheSameSnapshotWithoutMutation() = runCase { f, m ->
        m.back(); advanceUntilIdle(); assertCompleted(m)
        m.open(TravelerRoute(Destination.PASSPORT)); advanceUntilIdle(); m.back(); advanceUntilIdle()
        assertCompleted(m); assertEquals(listOf(c, c), f.reads); assertEquals(0, f.businessWrites)
    }
    @Test fun activeParticipationStillReadsItsOwnerProjection() = runCase { f, m ->
        f.completed = false; f.activeCache = true; m.refresh(); advanceUntilIdle()
        assertEquals(1, m.state.value.identity.passport?.running?.size)
        m.back(); advanceUntilIdle(); assertFalse(m.state.value.identity.completed)
        assertEquals(c, m.state.value.identity.running?.id); assertEquals(0, f.businessWrites)
    }
    @Test fun failedPassportRefreshDoesNotSuppressTheIndependentOwnerRead() = runCase { f, m ->
        f.failProgress = true; m.refresh(); advanceUntilIdle(); assertEquals(500, m.state.value.identity.failure?.status)
        m.back(); advanceUntilIdle(); assertCompleted(m); assertEquals(listOf(c), f.reads); assertEquals(0, f.businessWrites)
    }
    @Test fun foreignOrMissingParticipationIsDeniedByTheServerAndCannotProduceCompletion() = runCase { f, m ->
        for (status in listOf(403, 404)) {
            f.ownerStatus = status; m.open(TravelerRoute(Destination.PASSPORT)); advanceUntilIdle()
            if (m.state.value.route.destination == Destination.PASSPORT) m.back() else m.refresh()
            advanceUntilIdle(); assertEquals(status, m.state.value.identity.failure?.status)
            assertNull(m.state.value.identity.running); assertFalse(m.state.value.identity.completed)
        }
        assertEquals(listOf(c, c), f.reads); assertEquals(0, f.businessWrites)
    }
    @Test fun unauthorizedOwnerReadRenewsOnceThenReturnsToLoginWithoutPrivateState() = runCase { f, m ->
        f.ownerStatus = 401; m.back(); advanceUntilIdle()
        assertEquals(listOf(c, c), f.reads); assertFalse(m.state.value.identity.signedIn)
        assertNull(m.state.value.identity.running); assertEquals(Destination.LOGIN, m.state.value.route.destination)
        assertEquals(0, f.businessWrites)
    }
    @Test fun malformedSavedIdNeverBecomesAnHttpPathOrAnOwnershipProof() = runCase { f, m ->
        m.open(TravelerRoute(Destination.QUEST_PROGRESS, "../../other?owner=foreign")); advanceUntilIdle()
        assertTrue(f.reads.isEmpty()); assertNotNull(m.state.value.identity.failure)
        assertFalse(m.state.value.identity.completed); assertEquals(0, f.businessWrites)
    }
    @Test fun aDifferentResponseIdCannotPopulateTheRestoredParticipation() = runCase { f, m ->
        f.responseId = "00000000-0000-4000-8000-000000002002"; m.back(); advanceUntilIdle()
        assertEquals(502, m.state.value.identity.failure?.status); assertNull(m.state.value.identity.running)
        assertFalse(m.state.value.identity.completed); assertEquals(0, f.businessWrites)
    }
    @Test fun logoutDiscardsAnUncooperativeOldOwnerResponse() = runCase { f, m ->
        val gate = CompletableDeferred<Unit>(); f.beforeOwner = { withContext(NonCancellable) { gate.await() } }
        m.back(); runCurrent(); assertEquals(listOf(c), f.reads)
        f.session.logout(); runCurrent(); gate.complete(Unit); advanceUntilIdle()
        assertFalse(m.state.value.identity.signedIn); assertNull(m.state.value.identity.running)
        assertFalse(m.state.value.identity.completed); assertEquals(Destination.LOGIN, m.state.value.route.destination)
        assertEquals(0, f.businessWrites)
    }
    @Test fun newerPassportNavigationDiscardsThePendingCompletedResponse() = runCase { f, m ->
        val gate = CompletableDeferred<Unit>(); f.beforeOwner = { withContext(NonCancellable) { gate.await() } }
        m.back(); runCurrent(); m.open(TravelerRoute(Destination.PASSPORT)); runCurrent()
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(Destination.PASSPORT, m.state.value.route.destination)
        assertNull(m.state.value.identity.running); assertFalse(m.state.value.identity.completed)
        assertNotNull(m.state.value.identity.passport); assertEquals(0, f.businessWrites)
    }

    private inner class Fixture(dispatcher: CoroutineDispatcher) {
        var failProgress = false
        var ownerStatus = 200
        var completed = true
        var activeCache = false
        var responseId = c
        var businessWrites = 0
        var beforeOwner: suspend () -> Unit = {}
        val reads = mutableListOf<String>()
        private fun participation(id: String = responseId) = """{"id":"$id","status":"${if (completed) "completed" else "active"}","quest_title":"Completed owner fixture","quest_revision_id":"frozen-revision","quest_execution_status":"available","completed_activity_count":${if (completed) 2 else 0},"total_activity_count":2,"activities":[{"activity_id":"step-a","status":"completed"},{"activity_id":"step-b","status":"completed"}]}"""
        val client = HttpClient(MockEngine(MockEngineConfig().apply {
            this.dispatcher = dispatcher
            addHandler { request ->
            val path = request.url.encodedPath
            fun ok(data: String) = respond("""{"error":null,"data":$data}""")
            fun failure(status: Int) = respond("""{"error":{"message":"Synthetic owner failure","code":"DENIED"},"data":null}""", HttpStatusCode.fromValue(status))
            when {
                path.endsWith("/refresh") -> ok("""{"token_type":"Bearer","access_token":"synthetic-access","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"synthetic-refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}""")
                path.endsWith("/logout") -> ok("null")
                request.method != HttpMethod.Get -> { businessWrites++; error("Unexpected synthetic business write") }
                path.endsWith("/progress") -> if (failProgress) failure(500) else ok("""{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[],"active_participations":[${if (activeCache) participation(c) else ""}],"recent_completions":[{"completion_id":"receipt-fixture","quest_title":"Completed owner fixture","quest_slug":"fixture","reward_status":"awarded"}]}""")
                path.contains("/gamification/participations/") -> {
                    assertEquals("Bearer synthetic-access", request.headers[HttpHeaders.Authorization])
                    reads += path.substringAfterLast('/'); beforeOwner()
                    if (ownerStatus == 200) ok(participation()) else failure(ownerStatus)
                }
                else -> error("Unexpected synthetic read")
            }
            }
        }))
        private val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
        val session = TravelerSession(api, object : RefreshCredentialStore {
            private var saved: String? = "synthetic-restorable-refresh"
            override fun read() = saved
            override fun write(credential: String) { saved = credential }
            override fun clear() { saved = null }
        }, "id")
        private val identity = NativeTravelerIdentity(session, api)
        val runtime = TravelerFixture().runtime.let { r -> TravelerRuntime(identity, r.catalog, r.itineraries, r.distribution, r.trips,
            r.notifications, r.account, r.legal, r.registration, r.publicContent, r.savedSpots, r.questGroups, r.newOperationKey, r.startup) }
    }
}
