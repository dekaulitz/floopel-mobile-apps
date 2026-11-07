package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.AppPlatform
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.TravelerSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DistributionRecoveryTest {
    @Test fun bodylessCommandCanRecoverWithItsOriginalKey() = runTest {
        val fixture = RecoveryFixture()
        try {
            fixture.login(); fixture.failWrite = true
            assertEquals(CatalogResult.NetworkError, fixture.repository().unpublish("plan", "unpublish-key"))
            fixture.failWrite = false
            assertIs<CatalogResult.Ready<ItineraryDistribution>>(fixture.repository().unpublish("plan", "replacement-ui-key"))
            assertEquals(listOf<String?>("unpublish-key", "unpublish-key"), fixture.keys)
            assertEquals(listOf("", ""), fixture.bodies); assertNull(fixture.record)
        } finally { fixture.api.close() }
    }
    @Test fun imageRetryAfterRecreationKeepsKeyAndMultipartAndRejectsOtherContent() = runTest {
        val fixture = RecoveryFixture(); fixture.mediaMode = true
        try {
            fixture.login(); fixture.failWrite = true
            val bytes = "synthetic-photo-content".encodeToByteArray()
            assertEquals(CatalogResult.NetworkError, fixture.mediaRepository().uploadCover("plan", bytes, "image/png", "original-image"))
            val record = assertNotNull(fixture.record)
            assertFalse(record.contains("synthetic-photo-content")); assertTrue(record.contains("sha256"))
            fixture.failWrite = false
            assertEquals(CatalogResult.HttpError(409, code = "PENDING_OPERATION"), fixture.mediaRepository().uploadCover("plan", byteArrayOf(9), "image/png", "different-image"))
            assertEquals(1, fixture.keys.size)
            assertIs<CatalogResult.Ready<ItineraryCoverStatus>>(fixture.mediaRepository().uploadCover("plan", bytes, "image/png", "new-ui-key"))
            assertEquals(listOf<String?>("original-image", "original-image"), fixture.keys)
            assertEquals(fixture.bodies[0], fixture.bodies[1]); assertNull(fixture.record)
        } finally { fixture.api.close() }
    }

    @Test fun lostPublishResponseReusesFrozenKeyAfterRepositoryRecreation() = runTest {
        val fixture = RecoveryFixture()
        try {
            fixture.login()
            fixture.failWrite = true
            assertEquals(CatalogResult.NetworkError, fixture.repository().publish("plan", fixture.review, "original-key"))
            assertNotNull(fixture.record)
            fixture.failWrite = false
            val recreated = fixture.repository()
            assertEquals(fixture.review, assertIs<CatalogResult.Ready<ItineraryPublicationReview?>>(recreated.pendingPublication("plan")).value)
            assertIs<CatalogResult.Ready<ItineraryDistribution>>(recreated.publish("plan", fixture.review, "new-ui-key"))
            assertEquals(listOf<String?>("original-key", "original-key"), fixture.keys)
            assertEquals(fixture.bodies[0], fixture.bodies[1])
            assertNull(fixture.record)
        } finally { fixture.api.close() }
    }

    @Test fun changedReviewCannotReplaceAnUncertainPublication() = runTest {
        val fixture = RecoveryFixture()
        try {
            fixture.login(); fixture.failWrite = true
            fixture.repository().publish("plan", fixture.review, "original-key")
            fixture.failWrite = false
            val result = fixture.repository().publish("plan", fixture.review.copy(allowClone = false), "replacement-key")
            assertEquals(CatalogResult.HttpError(409, code = "PENDING_OPERATION"), result)
            assertEquals(1, fixture.keys.size)
            assertNotNull(fixture.record)
        } finally { fixture.api.close() }
    }

    @Test fun concurrentPublishDoesNotSendTwoRequests() = runTest {
        val fixture = RecoveryFixture()
        try {
            fixture.login()
            fixture.release = CompletableDeferred()
            val repository = fixture.repository()
            val first = async { repository.publish("plan", fixture.review, "first-key") }
            fixture.started.await()
            assertEquals(CatalogResult.HttpError(409, code = "PENDING_OPERATION"), repository.publish("plan", fixture.review, "second-key"))
            fixture.release?.complete(Unit)
            assertIs<CatalogResult.Ready<ItineraryDistribution>>(first.await())
            assertEquals(1, fixture.keys.size)
        } finally { fixture.api.close() }
    }

    @Test fun expiredIntentIsNotReissuedUnderANewKey() = runTest {
        val fixture = RecoveryFixture()
        try {
            fixture.login(); fixture.failWrite = true
            fixture.repository().publish("plan", fixture.review, "old-key")
            fixture.failWrite = false; fixture.time += 86_400_001
            assertEquals(CatalogResult.HttpError(409, code = "EXPIRED_OPERATION"), fixture.repository().publish("plan", fixture.review, "new-key"))
            assertEquals(1, fixture.keys.size)
        } finally { fixture.api.close() }
    }

    @Test fun cancellationPreservesTheIntentAndPropagates() = runTest {
        val fixture = RecoveryFixture()
        try {
            fixture.login(); fixture.release = CompletableDeferred()
            val first = async { fixture.repository().publish("plan", fixture.review, "cancelled-key") }
            fixture.started.await(); first.cancelAndJoin()
            assertTrue(first.isCancelled); assertNotNull(fixture.record)
            fixture.release = null
            assertIs<CatalogResult.Ready<ItineraryDistribution>>(fixture.repository().publish("plan", fixture.review, "replacement-key"))
            assertEquals(listOf<String?>("cancelled-key", "cancelled-key"), fixture.keys)
        } finally { fixture.api.close() }
    }
}

private class RecoveryFixture {
    var record: String? = null
    var time = 1_000L
    var failWrite = false
    var mediaMode = false
    var release: CompletableDeferred<Unit>? = null
    val started = CompletableDeferred<Unit>()
    val keys = mutableListOf<String?>()
    val bodies = mutableListOf<String>()
    val review = ItineraryPublicationReview("2026-11-06T00:00:00Z", true, true, true)
    val client = HttpClient(MockEngine { request ->
        val data = when (request.url.encodedPath) {
            "/api/mobile/v1/auth/login" -> """{"tokens":{"token_type":"Bearer","access_token":"synthetic-access","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"synthetic-refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
            "/api/apps/v1/gamification/me/progress" -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
            "/api/apps/v1/me" -> """{"profile":{"id":"00000000-0000-4000-8000-000000000001"}}"""
            else -> {
                assertEquals("Bearer synthetic-access", request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie]); assertNull(request.headers["X-CSRF-Token"])
                keys += request.headers["Idempotency-Key"]
                bodies += (request.body as? TextContent)?.text ?: request.body.toByteArray().decodeToString()
                started.complete(Unit); release?.await()
                if (failWrite) error("Synthetic lost response")
                if (mediaMode) """{"media_id":"media","processing_status":"processing","retryable":false}"""
                else """{"id":"plan","title":"Synthetic plan","visibility":"public","allow_clone":true,"locations":[],"stop_count":1,"updated_at":"2026-11-06T01:00:00Z"}"""
            }
        }
        respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""", headers = headersOf(HttpHeaders.ContentType,"application/json"))
    })
    val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
    val session = TravelerSession(api, object : RefreshCredentialStore {
        override fun read(): String? = null
        override fun write(credential: String) = Unit
        override fun clear() = Unit
    }, "id")
    private val store = object : TripRecoveryStore {
        override fun read() = record
        override fun write(record: String) { this@RecoveryFixture.record = record }
        override fun clear() { record = null }
    }
    suspend fun login() = session.login("synthetic@example.invalid", "synthetic-password")
    fun repository() = NativeItineraryDistributionApi(api.catalogTransport(), session, store, now = { time })
    fun mediaRepository() = NativeItineraryDistributionApi(api.catalogTransport(), session,
        mediaUploads = NativeMediaUploads(api.catalogTransport(), session, store))
}
