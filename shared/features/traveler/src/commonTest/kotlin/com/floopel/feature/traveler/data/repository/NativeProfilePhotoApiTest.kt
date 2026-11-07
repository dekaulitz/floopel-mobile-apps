package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.RefreshCredentialStore
import com.floopel.feature.traveler.feature.auth.TravelerSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NativeProfilePhotoApiTest {
    @Test fun optimizedWebpUsesTheMatchingMultipartMimeAndExtension() = runTest {
        val f = PhotoApiFixture()
        try {
            f.login()
            assertIs<CatalogResult.Ready<ProfilePhotoUpload>>(f.photos.uploadProfilePhoto(ProfilePhotoImage(f.image.bytes, "image/webp")))
            assertTrue(f.multipart!!.contains("image/webp"))
            assertTrue(f.multipart!!.contains("filename=cover.webp") || f.multipart!!.contains("filename=\"cover.webp\""))
            assertEquals(1, f.writes)
        } finally { f.client.close() }
    }

    @Test fun oneNativeMultipartAdmissionIsSeparateFromReadOnlyStatus() = runTest {
        val f = PhotoApiFixture()
        try {
            f.login()
            val admitted = assertIs<CatalogResult.Ready<ProfilePhotoUpload>>(f.photos.uploadProfilePhoto(f.image)).value
            assertEquals(ProfilePhotoStatus.PROCESSING, admitted.status)
            assertEquals(1, f.writes)
            assertEquals("/api/apps/v1/me/profile-photo-uploads", f.lastPath)
            assertTrue(f.multipart!!.contains("name=file") || f.multipart!!.contains("name=\"file\""))
            assertTrue(f.multipart!!.contains("photo-bytes"))
            assertNull(f.idempotency)
            f.photoState = "ready"
            val revision = f.readRevision
            val ready = assertIs<CatalogResult.Ready<ProfilePhotoUpload>>(f.photos.profilePhotoUpload(admitted.id)).value
            assertEquals(ProfilePhotoStatus.READY, ready.status)
            assertEquals(1, f.writes)
            assertTrue(f.readRevision > revision)
            assertEquals("/api/apps/v1/me/profile-photo-uploads/${f.id}", f.lastPath)
            f.photoState = "failed"
            assertEquals(ProfilePhotoStatus.FAILED, assertIs<CatalogResult.Ready<ProfilePhotoUpload>>(f.photos.profilePhotoUpload(f.id)).value.status)
        } finally { f.client.close() }
    }

    @Test fun malformedSuccessCannotBecomeCompletedPhoto() = runTest {
        val f = PhotoApiFixture()
        try {
            f.login()
            for (body in listOf(
                f.envelope().replace("\"OK\"", "\"ok\""),
                f.envelope().replace("1792886400000", "-1"),
                f.envelope().replace("\"request_id\":\"${f.id}\"", "\"request_id\":\"invalid\""),
                f.envelope().replace("processing", "ready"),
                f.envelope().replace("processing", "superseded"),
                f.envelope().replace("\"error\":null,", ""),
                f.envelope().replace("\"error\":null", "\"error\":{}"),
                f.envelope().replace("\"id\":\"${f.id}\"", "\"id\":\"foreign/path\""),
            )) {
                f.reply = body
                assertEquals(CatalogResult.InvalidResponse, f.photos.uploadProfilePhoto(f.image))
            }
        } finally { f.client.close() }
    }

    @Test fun invalidSelectionAndPathAreRejectedBeforeNetwork() = runTest {
        val f = PhotoApiFixture()
        try {
            f.login()
            for (image in listOf(ProfilePhotoImage(byteArrayOf(), "image/png"), ProfilePhotoImage(byteArrayOf(1), "image/svg+xml"))) {
                assertEquals(CatalogResult.HttpError(422, code = "VALIDATION"), f.photos.uploadProfilePhoto(image))
            }
            assertEquals(CatalogResult.HttpError(413, code = "PAYLOAD_TOO_LARGE"), f.photos.uploadProfilePhoto(ProfilePhotoImage(ByteArray(ProfilePhotoPolicy.MaxBytes + 1), "image/png")))
            assertEquals(CatalogResult.HttpError(422, code = "VALIDATION"), f.photos.profilePhotoUpload("../other-profile"))
            assertEquals(0, f.photoCalls)
        } finally { f.client.close() }
    }

    @Test fun canonicalCodesAndNullableDetailsSurviveNativeThrottleWithoutReplay() = runTest {
        for ((status, code, details) in listOf(
            Triple(422, "VALIDATION", "null"), Triple(422, "FUTURE_PHOTO_RULE", "[]"),
            Triple(403, "FORBIDDEN", "null"), Triple(404, "NOT_FOUND", "[]"),
            Triple(429, "RATE_LIMITED", "null"), Triple(401, "UNAUTHORIZED", "[]"),
        )) {
            val f = PhotoApiFixture()
            try {
                f.login(); f.status = HttpStatusCode.fromValue(status)
                f.reply = f.failure(code, details)
                val result = assertIs<CatalogResult.HttpError>(f.photos.uploadProfilePhoto(f.image))
                assertEquals(code, result.code)
                assertEquals(status, result.status)
                if (status == 429) assertEquals(5, result.retryAfterSeconds)
                assertEquals(1, f.writes)
            } finally { f.client.close() }
        }
    }

    @Test fun malformedErrorCannotSupplyBranchCodeAndMaintenanceKeepsCredential() = runTest {
        val f = PhotoApiFixture()
        try {
            f.login(); f.status = HttpStatusCode.UnprocessableEntity
            f.reply = """{"meta":{"code":"VALIDATION"},"error":{"message":"synthetic"},"data":null}"""
            assertNull(assertIs<CatalogResult.HttpError>(f.photos.uploadProfilePhoto(f.image)).code)
            f.status = HttpStatusCode.ServiceUnavailable
            assertIs<CatalogResult.Maintenance>(f.photos.uploadProfilePhoto(f.image))
            assertTrue(f.session.view.value.signedIn)
            assertEquals(2, f.writes)
        } finally { f.client.close() }
    }

    @Test fun meAvatarProjectionSupportsLegacyAndRejectsInvalidWireType() = runTest {
        val f = PhotoApiFixture()
        try {
            f.login()
            for (field in listOf("", ",\"avatar_url\":null", ",\"avatar_url\":\"https://fixture.supabase.co/storage/v1/object/public/photos/thumb.webp\"")) {
                f.avatarField = field
                val profile = assertIs<CatalogResult.Ready<PersonalProfile>>(f.account.profile()).value
                if (field.contains("https")) assertTrue(profile.avatarUrl!!.contains("/object/public/")) else assertNull(profile.avatarUrl)
            }
            f.avatarField = ",\"avatar_url\":7"
            assertEquals(CatalogResult.InvalidResponse, f.account.profile())
        } finally { f.client.close() }
    }

    @Test fun cancellationPropagatesWithoutAnotherUpload() = runTest {
        val f = PhotoApiFixture()
        try {
            f.login(); f.blockUpload = CompletableDeferred()
            val request = async { f.photos.uploadProfilePhoto(f.image) }
            f.uploadStarted.await(); request.cancelAndJoin()
            assertTrue(request.isCancelled)
            assertEquals(1, f.writes)
        } finally { f.client.close() }
    }

    @Test fun unrelatedReadyRequestAndNetworkFailureCannotReplaceSelectedPhoto() = runTest {
        val f = PhotoApiFixture()
        try {
            f.login(); f.photoState = "ready"
            assertEquals(CatalogResult.InvalidResponse, f.photos.profilePhotoUpload("22222222-2222-4222-8222-222222222222"))
            f.networkFailure = true
            assertEquals(CatalogResult.NetworkError, f.photos.uploadProfilePhoto(f.image))
            assertEquals(1, f.writes)
        } finally { f.client.close() }
    }
}

private class PhotoApiFixture {
    val id = "11111111-1111-4111-8111-111111111111"
    val image = ProfilePhotoImage("photo-bytes".encodeToByteArray(), "image/png")
    var photoState = "processing"
    var reply: String? = null
    var status = HttpStatusCode.Accepted
    var photoCalls = 0
    var writes = 0
    var lastPath = ""
    var multipart: String? = null
    var idempotency: String? = null
    var avatarField = ""
    var blockUpload: CompletableDeferred<Unit>? = null
    var networkFailure = false
    val uploadStarted = CompletableDeferred<Unit>()
    private var credential: String? = null
    fun envelope() = """{"meta":{"request_id":"$id","timestamp":1792886400000,"code":"OK"},"error":null,"data":{"id":"$id","status":"$photoState"}}"""
    fun failure(code: String, details: String) = """{"meta":{"request_id":"$id","timestamp":1792886400000,"code":"$code"},"error":{"message":"Synthetic failure","details":$details},"data":null}"""
    val client = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        when {
            path.endsWith("/login") -> respond("""{"meta":{"code":"OK"},"error":null,"data":{"tokens":{"token_type":"Bearer","access_token":"fixture","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"protected","refresh_expires_at":"2099-02-01T00:00:00Z"}}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            path.endsWith("/progress") -> respond("""{"meta":{"code":"OK"},"error":null,"data":{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            path.endsWith("/me") -> respond("""{"meta":{"code":"OK"},"error":null,"data":{"profile":{"first_name":"Traveler","last_name":"Fixture","username":"fixture","nationality_country_code":"ID"$avatarField}}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            else -> {
                photoCalls++; lastPath = path
                assertEquals("Bearer fixture", request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                assertEquals("ANDROID", request.headers["X-App-Platform"])
                assertEquals("0.1.0-dev", request.headers["X-App-Version"])
                idempotency = request.headers["Idempotency-Key"]
                if (request.method == HttpMethod.Post) {
                    writes++; multipart = request.body.toByteArray().decodeToString(); uploadStarted.complete(Unit)
                    if (networkFailure) error("Synthetic network failure")
                    blockUpload?.await()
                }
                val resultStatus = if (status == HttpStatusCode.Accepted && request.method == HttpMethod.Get) HttpStatusCode.OK else status
                respond(reply ?: envelope(), resultStatus, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("5")))
            }
        }
    })
    private val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID, readCache = TravelerReadCache(), appVersion = "0.1.0-dev")
    val readRevision get() = api.pageReadRevision
    val session = TravelerSession(api, object : RefreshCredentialStore {
        override fun read() = credential
        override fun write(credential: String) { this@PhotoApiFixture.credential = credential }
        override fun clear() { credential = null }
    }, "en")
    val photos = NativeProfilePhotoApi(api.catalogTransport(), session)
    val account = NativeAccountApi(api.catalogTransport(), session)
    suspend fun login() { session.login("fixture@example.invalid", "fixture-password") }
}
