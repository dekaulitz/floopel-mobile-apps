package com.floopel.feature.traveler.presentation

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.core.network.AppPlatform
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.data.repository.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.RefreshCredentialStore
import com.floopel.feature.traveler.feature.auth.TravelerSession
import com.floopel.feature.traveler.testing.TravelerFixture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class TravelerModerationNotificationTest {
    private val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    private val jobs = mutableListOf<Job>()
    @BeforeTest fun before() { Dispatchers.setMain(ui) }
    @AfterTest fun after() {
        runBlocking { withContext(ui) { owners.forEach(ViewModelStore::clear) }; jobs.joinAll() }
        Dispatchers.resetMain(); ui.close()
    }
    private suspend fun act(call: () -> Unit) = withContext(ui) { call() }
    private suspend fun TravelerViewModel.awaitState(test: (TravelerState) -> Boolean) = withTimeout(10_000) { state.first(test) }
    private suspend fun ready(m: TravelerViewModel) = m.awaitState { !it.ownerModeration.loading && (it.ownerModeration.value != null || it.ownerModeration.failure != null) }
    private suspend fun model(f: ModerationNavigationFixture, restored: String = ""): TravelerViewModel {
        return withContext(ui) { TravelerViewModel(f.runtime, restored, worker = ui, now = { f.clockMillis }).also {
            owners += ViewModelStore().apply { put("vm", it) }; jobs += requireNotNull(it.viewModelScope.coroutineContext[Job])
        } }.also { it.awaitState { s -> s.identityRestored && s.intro?.completed == true } }
    }
    private suspend fun open(m: TravelerViewModel, f: ModerationNavigationFixture, settled: Boolean = true) {
        act { m.open(TravelerRoute(Destination.NOTIFICATIONS)) }; m.awaitState { it.notifications.value != null && !it.notifications.loading }
        act { m.openNotification(f.id) }; m.awaitState { it.route.destination != Destination.NOTIFICATION_OPEN || it.notification.failure != null && !it.notification.loading }
        if (settled && m.state.value.route.destination == Destination.OWNER_MODERATION) ready(m)
    }
    private suspend fun refresh(m: TravelerViewModel) { act { m.refresh() }; ready(m) }
    private suspend fun visible(m: TravelerViewModel) { act { m.targetVisible(m.state.value.route) }; withContext(ui) { yield() } }
    private suspend fun ack(f: ModerationNavigationFixture) = withTimeout(10_000) { while (f.writes.isEmpty()) delay(5) }
    @Test fun bothCanonicalKindsLoadTheActualOwnerAdapterAndAckOnlyTheVisibleInboxIdOnce() = runBlocking {
        for (kind in listOf("itinerary.moderation_decided", "itinerary.appeal_decided")) {
            val f = ModerationNavigationFixture()
            try {
                f.login(); f.kind = kind; val m = model(f); open(m, f)
                assertEquals(Destination.OWNER_MODERATION, m.state.value.route.destination)
                assertEquals(f.id, m.state.value.route.inboxId); assertEquals("Rencana fixture", m.state.value.ownerModeration.value?.single()?.title)
                assertEquals(0, f.writes.size); visible(m); visible(m); ack(f)
                assertEquals(listOf("PUT:/api/apps/v1/notifications/${f.id}/read"), f.writes)
                refresh(m); visible(m); assertEquals(1, f.writes.size)
                act { m.back() }; m.awaitState { it.route.destination == Destination.NOTIFICATIONS }; assertEquals(Destination.NOTIFICATIONS, m.state.value.route.destination)
            } finally { f.client.close() }
        }
    }
    @Test fun deniedRemovedEmptyMalformedAndFailedStatusReadsDoNotAcknowledge() = runBlocking {
        val f = ModerationNavigationFixture()
        try {
            f.login(); val m = model(f); open(m, f)
            for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.NotFound, HttpStatusCode.ServiceUnavailable)) {
                f.status = status; refresh(m); visible(m)
                assertNotNull(m.state.value.ownerModeration.failure); assertEquals(0, f.writes.size)
            }
            f.status = HttpStatusCode.OK; f.data = "[]"; refresh(m)
            visible(m); assertTrue(m.state.value.ownerModeration.value!!.isEmpty()); assertEquals(0, f.writes.size)
            f.data = "{}"; refresh(m); visible(m)
            assertEquals(502, m.state.value.ownerModeration.failure?.status); assertEquals(0, f.writes.size)
        } finally { f.client.close() }
    }
    @Test fun errorRetryAndPlanBackRetainTheOwnerReaderAndDoNotSubmitAnAppeal() = runBlocking {
        val f = ModerationNavigationFixture()
        try {
            f.login(); f.offline = true; val m = model(f); open(m, f)
            assertNotNull(m.state.value.ownerModeration.failure); assertEquals(0, f.writes.size)
            f.offline = false; refresh(m); val route = m.state.value.route
            act { m.open(TravelerRoute(Destination.ITINERARY, f.base.planId)) }; m.awaitState { it.itinerary.value != null && !it.itinerary.loading }
            act { m.back() }; ready(m)
            assertEquals(route, m.state.value.route); assertNotNull(m.state.value.ownerModeration.value)
            assertTrue(f.writes.isEmpty()); visible(m); ack(f); assertEquals(1, f.writes.size)
            assertTrue(f.requests.none { it.contains("/appeal") })
            f.status = HttpStatusCode.TooManyRequests; f.retryAfter = "2"; refresh(m)
            val requests = f.requests.size; act { m.refresh() }; assertEquals(requests, f.requests.size)
            f.clockMillis = 2_001; f.status = HttpStatusCode.OK; f.retryAfter = null; refresh(m)
            assertNotNull(m.state.value.ownerModeration.value, "state=${m.state.value.ownerModeration}; clock=${f.clockMillis}; requests=${f.requests}")
            assertEquals(1, f.writes.size)
        } finally { f.client.close() }
    }
    @Test fun lateOwnerReadAfterBackCannotRenderOrAcknowledgeTheOldTarget() = runBlocking {
        val f = ModerationNavigationFixture(); val gate = CompletableDeferred<Unit>()
        try {
            f.login(); f.beforeActions = { withContext(NonCancellable) { gate.await() } }
            val m = model(f); open(m, f, settled = false); f.actionStarted.await()
            val old = m.state.value.route; act { m.back() }; gate.complete(Unit)
            m.awaitState { it.route.destination == Destination.NOTIFICATIONS && !it.notifications.loading }; act { m.targetVisible(old) }
            assertEquals(Destination.NOTIFICATIONS, m.state.value.route.destination); assertNull(m.state.value.ownerModeration.value); assertTrue(f.writes.isEmpty())
        } finally { gate.complete(Unit); f.client.close() }
    }
    @Test fun lateOwnerReadAfterIdentityChangeCannotRestorePrivateDataOrAck() = runBlocking {
        val f = ModerationNavigationFixture(); val gate = CompletableDeferred<Unit>()
        try {
            f.login(); f.beforeActions = { withContext(NonCancellable) { gate.await() } }
            val m = model(f); open(m, f, settled = false); f.actionStarted.await(); val old = m.state.value.route
            act { f.base.identityState.value = TravelerIdentityView(generation = f.base.identityState.value.generation + 1) }
            m.awaitState { it.route.destination == Destination.LOGIN }; gate.complete(Unit); act { m.targetVisible(old) }
            assertEquals(Destination.LOGIN, m.state.value.route.destination); assertNull(m.state.value.ownerModeration.value); assertTrue(f.writes.isEmpty())
        } finally { gate.complete(Unit); f.client.close() }
    }
    @Test fun nullUnsafeAndRemovedNotificationsNeverReachTheOwnerEndpointOrAck() = runBlocking {
        val f = ModerationNavigationFixture()
        try {
            f.login(); val m = model(f)
            for (target in listOf(null, "/me/itinerary-moderation/", "/me/itinerary-moderation?actor=other", "https://fixture.invalid/me/itinerary-moderation")) {
                f.target = target; open(m, f); visible(m)
                assertNotEquals(Destination.OWNER_MODERATION, m.state.value.route.destination)
                act { m.returnToNotifications() }; m.awaitState { !it.notifications.loading }
            }
            f.target = "/me/itinerary-moderation"; f.lookupStatus = HttpStatusCode.NotFound
            act { m.openNotification(f.id) }; m.awaitState { it.notification.failure != null && !it.notification.loading }; visible(m)
            assertEquals(0, f.actionReads); assertTrue(f.writes.isEmpty())
        } finally { f.client.close() }
    }
    @Test fun restoringTheCanonicalTargetRequiresAFreshAuthorizedReadBeforeVisibleAck() = runBlocking {
        val f = ModerationNavigationFixture()
        try {
            f.login(); val m = model(f); open(m, f); val stack = m.savedStack()
            val reads = f.actionReads; val restored = model(f, stack); ready(restored)
            assertTrue(f.actionReads > reads); assertEquals(Destination.OWNER_MODERATION, restored.state.value.route.destination)
            assertTrue(f.writes.isEmpty()); visible(restored); ack(f); assertEquals(1, f.writes.size)
        } finally { f.client.close() }
    }
    @Test fun restoredOrRefreshedStaticTargetRechecksNullUnsafeAndRemovedSourceBeforeAnyOwnerReadOrAck() = runBlocking {
        val f = ModerationNavigationFixture()
        try {
            f.login(); val m = model(f); open(m, f); val stack = m.savedStack(); val reads = f.actionReads
            for (target in listOf(null, "https://fixture.invalid/me/itinerary-moderation")) {
                f.target = target; val restored = model(f, stack); ready(restored); visible(restored)
                assertNull(restored.state.value.ownerModeration.value); assertNotNull(restored.state.value.ownerModeration.failure)
                assertEquals(reads, f.actionReads); assertTrue(f.writes.isEmpty())
            }
            f.target = "/me/itinerary-moderation"; f.lookupStatus = HttpStatusCode.NotFound
            refresh(m); visible(m); assertEquals(404, m.state.value.ownerModeration.failure?.status)
            assertNull(m.state.value.ownerModeration.value); assertEquals(reads, f.actionReads); assertTrue(f.writes.isEmpty())
        } finally { f.client.close() }
    }
}

private class ModerationNavigationFixture {
    val base = TravelerFixture()
    val id = base.inboxId
    var kind = "itinerary.moderation_decided"
    var target: String? = "/me/itinerary-moderation"
    var status = HttpStatusCode.OK
    var lookupStatus = HttpStatusCode.OK
    var offline = false
    var clockMillis = 0L
    var retryAfter: String? = null
    var actionReads = 0
    val actionStarted = CompletableDeferred<Unit>()
    var beforeActions: suspend () -> Unit = {}
    val writes = CopyOnWriteArrayList<String>()
    val requests = CopyOnWriteArrayList<String>()
    var data = """[{"id":"00000000-0000-4000-8000-000000000202","itinerary_id":"${base.planId}","itinerary_title":"Rencana fixture","action":"hide","reason_code":"privacy","created_at":"2026-11-08T00:00:00Z","appeal_available":false,"appeal":null,"internal_notes":"not-projected"}]"""
    private fun notice() = """{"id":"$id","kind":"$kind","class":"system","title":"Moderation fixture","body":"Update","target_url":${target?.let { JsonPrimitive(it).toString() } ?: "null"},"occurred_at":"2026-11-08T00:00:00Z","created_at":"2026-11-08T00:00:00Z","read_at":null}"""
    val client = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath; requests += "${request.method.value}:$path"
        var responseStatus = HttpStatusCode.OK
        var pagination = ""
        val response = when {
            path.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"fixture","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"fixture-refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
            path.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
            else -> {
                assertEquals("Bearer fixture", request.headers[HttpHeaders.Authorization]); assertNull(request.headers[HttpHeaders.Cookie])
                when {
                    path.endsWith("/itinerary-moderation-actions") -> { actionReads++; actionStarted.complete(Unit); beforeActions(); if (offline) error("fixture offline"); responseStatus = status; data }
                    path.endsWith("/unread-count") -> """{"count":1}"""
                    path.endsWith("/notifications") -> { pagination = ",\"pagination\":{\"page\":1,\"per_page\":20,\"total\":1,\"total_pages\":1}"; "[${notice()}]" }
                    path.endsWith("/read") -> { writes += "${request.method.value}:$path"; """{"notification_id":"$id","read_at":"2026-11-08T00:01:00Z"}""" }
                    path.endsWith("/notifications/$id") -> { responseStatus = lookupStatus; notice() }
                    else -> error("Unexpected fixture path $path")
                }
            }
        }
        val ok = responseStatus == HttpStatusCode.OK
        respond("""{"meta":{"code":"${if (ok) "OK" else "FORBIDDEN"}","request_id":"11111111-1111-4111-8111-111111111111","timestamp":1792922400000$pagination},"error":${if (ok) "null" else "{\"message\":\"Unavailable\",\"details\":null}"},"data":${if (ok) response else "null"}}""",
            responseStatus, Headers.build {
                append(HttpHeaders.ContentType, "application/json")
                retryAfter?.let { append(HttpHeaders.RetryAfter, it) }
            })
    })
    private val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
    private val session = TravelerSession(api, object : RefreshCredentialStore {
        private var value: String? = null
        override fun read() = value
        override fun write(credential: String) { value = credential }
        override fun clear() { value = null }
    }, "id", nowMillis = { clockMillis })
    val runtime = base.runtime.let { r -> TravelerRuntime(r.identity, r.catalog, r.itineraries, r.distribution, r.trips,
        NativeNotificationApi(api.catalogTransport(), session), r.account, r.legal, r.registration, r.publicContent, r.savedSpots,
        r.questGroups, r.newOperationKey, r.startup, moderation = NativeModerationApi(api.catalogTransport(), session)) }
    suspend fun login() { session.login("fixture@example.invalid", "fixture-password"); base.identityState.value = base.identityState.value.copy(generation = session.identityGeneration) }
}
