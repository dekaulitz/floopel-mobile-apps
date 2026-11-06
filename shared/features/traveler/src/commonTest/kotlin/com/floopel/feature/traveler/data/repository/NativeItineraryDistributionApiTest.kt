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

class NativeItineraryDistributionApiTest {
    @Test fun spotPreviewImagesNeverCountAsTheRequiredItineraryCover() = runTest {
        val f = DistributionFixture()
        try {
            f.login()
            val payload = Json.parseToJsonElement(f.owner).jsonObject.toMutableMap()
            payload["cover_image"] = JsonNull
            payload["preview_images"] = JsonArray(listOf(Json.parseToJsonElement("""{"variants":{"medium":{"url":"https://media.invalid/spot"}}}""")))
            f.response = JsonObject(payload).toString()
            val state = assertIs<CatalogResult.Ready<ItineraryDistribution>>(f.repository.read("plan")).value
            assertTrue(state.coverUrls.isEmpty())
            assertTrue(state.coverPhotos.isEmpty())
            assertEquals(2, state.stopCount)
        } finally { f.client.close() }
    }
    @Test fun publicationUsesReviewedVersionAndOwnerMutationsDoNotInventReadiness() = runTest {
        val f = DistributionFixture()
        try {
            f.login()
            val state = assertIs<CatalogResult.Ready<ItineraryDistribution>>(f.repository.read("plan")).value
            assertEquals(listOf("https://media.invalid/cover"), state.coverUrls)
            assertEquals("2026-10-16T00:00:00Z", state.updatedAt)
            assertEquals(2, state.stopCount)
            val review = ItineraryPublicationReview(state.updatedAt!!, true, true, true)
            assertEquals(CatalogResult.HttpError(422), f.repository.publish("plan", review.copy(notesReviewed = false), "key"))
            assertEquals(0, f.writes)
            val published = assertIs<CatalogResult.Ready<ItineraryDistribution>>(f.repository.publish("plan", review, "key")).value
            assertEquals("public", published.visibility)
            assertNull(published.updatedAt)
            val payload = Json.parseToJsonElement(f.body!!).jsonObject
            assertEquals(setOf("visibility", "allow_clone", "public_note_reviewed", "pii_acknowledged", "readiness_policy_version", "expected_updated_at"), payload.keys)
            assertEquals("v1", payload["readiness_policy_version"]!!.jsonPrimitive.content)
            assertEquals(state.updatedAt, payload["expected_updated_at"]!!.jsonPrimitive.content)
            assertEquals("key", f.key)
            assertIs<CatalogResult.Ready<*>>(f.repository.clonePolicy("plan", false, "key"))
            assertEquals("""{"allow_clone":false}""", f.body)
            assertEquals("private", assertIs<CatalogResult.Ready<ItineraryDistribution>>(f.repository.unpublish("plan", "key")).value.visibility)
            f.response = f.owner.replace("\"allow_clone\":true", "\"allow_clone\":\"true\"")
            assertEquals(CatalogResult.InvalidResponse, f.repository.read("plan"))
            f.response = null; f.status = HttpStatusCode.Conflict
            val before = f.writes
            assertEquals(CatalogResult.HttpError(409), f.repository.publish("plan", review, "key"))
            assertEquals(before + 1, f.writes)
            assertTrue(f.session.view.value.signedIn)
        } finally { f.client.close() }
    }
    @Test fun shareUrlIsOneTimeAndNeverIncludedInModelStringOrStatusReplay() = runTest {
        val f = DistributionFixture()
        try {
            f.login()
            assertNull(assertIs<CatalogResult.Ready<ItineraryShareLink>>(f.repository.shareLink("plan")).value.url)
            val created = assertIs<CatalogResult.Ready<ItineraryShareLink>>(f.repository.rotateShareLink("plan", "key")).value
            assertEquals("https://traveler.invalid/i/share/secret", created.url)
            assertFalse(created.toString().contains("secret"))
            f.response = f.link
            assertNull(assertIs<CatalogResult.Ready<ItineraryShareLink>>(f.repository.rotateShareLink("plan", "key")).value.url)
            assertEquals(CatalogResult.Ready(Unit), f.repository.revokeShareLink("plan", "key"))
            f.response = f.link.dropLast(1) + ",\"url\":\"javascript:secret\"}"
            assertEquals(CatalogResult.InvalidResponse, f.repository.shareLink("plan"))
            f.response = null; f.status = HttpStatusCode.ServiceUnavailable
            val before = f.writes
            assertEquals(CatalogResult.Maintenance(), f.repository.rotateShareLink("plan", "key"))
            assertEquals(before + 1, f.writes)
            assertTrue(f.session.view.value.signedIn)
            f.status = HttpStatusCode.Forbidden
            assertEquals(CatalogResult.HttpError(403), f.repository.shareLink("plan"))
            f.networkFailure = true
            assertEquals(CatalogResult.NetworkError, f.repository.read("plan"))
        } finally { f.client.close() }
    }
    @Test fun coverUploadIsBoundedParentScopedMultipartAndNeverAutomaticallyReplayed() = runTest {
        val f = DistributionFixture()
        try {
            assertEquals(CatalogResult.HttpError(401), f.repository.uploadCover("plan", byteArrayOf(1), "image/png", "key"))
            f.login()
            assertEquals(CatalogResult.HttpError(422), f.repository.uploadCover("plan", byteArrayOf(), "image/png", "key"))
            assertEquals(CatalogResult.HttpError(422), f.repository.uploadCover("plan", ByteArray(5 * 1024 * 1024 + 1), "image/png", "key"))
            assertEquals(CatalogResult.HttpError(422), f.repository.uploadCover("plan", byteArrayOf(1), "image/svg+xml", "key"))
            val uploaded = assertIs<CatalogResult.Ready<ItineraryCoverStatus>>(f.repository.uploadCover("plan", byteArrayOf(1,2,3), "image/png", "key")).value
            assertEquals(CoverProcessing.PROCESSING, uploaded.processing)
            assertTrue(f.multipart)
            assertEquals("key", f.key)
            assertEquals(CoverProcessing.READY, assertIs<CatalogResult.Ready<ItineraryCoverStatus>>(f.repository.cover("plan")).value.processing)
            f.status = HttpStatusCode.ServiceUnavailable
            val before = f.writes
            assertEquals(CatalogResult.Maintenance(), f.repository.uploadCover("plan", byteArrayOf(1), "image/jpeg", "key"))
            assertEquals(before + 1, f.writes)
            assertTrue(f.session.view.value.signedIn)
            f.status = HttpStatusCode.PayloadTooLarge
            assertEquals(CatalogResult.HttpError(413), f.repository.uploadCover("plan", byteArrayOf(1), "image/webp", "key"))
            f.networkFailure = true
            assertEquals(CatalogResult.NetworkError, f.repository.uploadCover("plan", byteArrayOf(1), "image/webp", "key"))
        } finally { f.client.close() }
    }
}
private class DistributionFixture {
    var status = HttpStatusCode.OK
    var response: String? = null
    var networkFailure = false
    var writes = 0
    var body: String? = null
    var key: String? = null
    var multipart = false
    val owner = """{"id":"plan","locations":[],"title":"Plan","description":"Public description","visibility":"private","allow_clone":true,"distribution_status":"active","has_unpublished_changes":false,"stop_count":2,"updated_at":"2026-10-16T00:00:00Z","cover_image":{"variants":{"medium":{"url":"https://media.invalid/cover"}}}}"""
    val link = """{"id":"link","status":"active","generation":1,"token_hint":"hint"}"""
    val client = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        val data = when {
            path.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"fixture","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"protected","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
            path.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
            else -> {
                assertEquals("Bearer fixture", request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                body = (request.body as? TextContent)?.text
                key = request.headers["Idempotency-Key"]
                if (request.method != HttpMethod.Get) writes++
                if (networkFailure) error("Fixture network failure")
                val payload = response ?: when {
                    path.endsWith("/cover") -> {
                        if (request.method == HttpMethod.Post) multipart = request.body.contentType?.match(ContentType.MultiPart.FormData) == true
                        """{"media_id":"media","processing_status":"${if (request.method == HttpMethod.Post) "processing" else "ready"}","retryable":false}"""
                    }
                    path.endsWith("/share-link") -> if (request.method == HttpMethod.Post) link.dropLast(1) + ",\"url\":\"https://traveler.invalid/i/share/secret\"}" else link
                    path.endsWith("/publish") -> owner.replace("\"visibility\":\"private\"", "\"visibility\":\"public\"").replace(",\"updated_at\":\"2026-10-16T00:00:00Z\"", "")
                    else -> owner
                }
                val actualStatus = if (status == HttpStatusCode.OK && request.method == HttpMethod.Delete) HttpStatusCode.NoContent else status
                return@MockEngine respond("""{"meta":{"code":"OK"},"error":null,"data":$payload}""", actualStatus, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })
    val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
    val session = TravelerSession(api, object : RefreshCredentialStore {
        override fun read(): String? = null
        override fun write(credential: String) = Unit
        override fun clear() = Unit
    }, "en")
    val repository = NativeItineraryDistributionApi(api.catalogTransport(), session)
    suspend fun login() { session.login("fixture@example.invalid", "fixture-password") }
}
