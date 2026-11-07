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

class NativeCommunityApiTest {
    @Test fun authorPhotoNeverComesFromAnotherTravelersMatchingDisplayLabel() = runTest {
        val f = CommunityFixture()
        try {
            f.login()
            f.metadataPhoto = "https://fixture.invalid/media/unrelated-traveler.webp"
            val photos = listOf("https://fixture.invalid/media/actual-author.webp", null).map { source ->
                f.authorPhoto = source
                assertIs<CatalogResult.Ready<CommentPage>>(f.comments.page(CommentTarget.SPOT, "spot")).value.items.single().avatarUrl
            }
            assertEquals(listOf("https://fixture.invalid/media/actual-author.webp", null), photos)
        } finally { f.client.close() }
    }
    @Test fun commentsRespectUnicodeCursorDepthPlainTextAndServerLikeState() = runTest {
        val f = CommunityFixture()
        try {
            assertEquals(CatalogResult.HttpError(401), f.comments.page(CommentTarget.SPOT, "spot"))
            f.login()
            val page = assertIs<CatalogResult.Ready<CommentPage>>(f.comments.page(CommentTarget.SPOT, "spot", "a+/=", null)).value
            assertEquals("next", page.nextCursor)
            assertTrue(f.query.contains("cursor=a%2B%2F%3D"))
            assertEquals("<b>plain</b>", page.items.single().body)
            assertNull(page.items.single().avatarUrl)
            assertEquals(CatalogResult.Ready(2L), f.comments.total(CommentTarget.SPOT, "spot"))
            val emoji = "\uD83D\uDE00"
            assertEquals(100, visibleCharacterCount("  " + emoji.repeat(100) + "  "))
            assertIs<CatalogResult.Ready<*>>(f.comments.create(CommentTarget.CONTENT, "story", "  " + emoji.repeat(100) + "  ", null))
            assertEquals(emoji.repeat(100), Json.parseToJsonElement(f.body!!).jsonObject["body"]!!.jsonPrimitive.content)
            assertNull(f.lastKey) // Creation has no idempotency contract.
            val before = f.writes
            assertEquals(CatalogResult.HttpError(422), f.comments.create(CommentTarget.SPOT, "spot", emoji.repeat(101), null))
            assertEquals(CatalogResult.HttpError(422), f.comments.create(CommentTarget.SPOT, "spot", " ", null))
            assertEquals(before, f.writes)
            f.response = """{"items":[${f.comment("reply", "comment")}],"next_cursor":null}"""
            assertEquals("comment", assertIs<CatalogResult.Ready<CommentPage>>(f.comments.page(CommentTarget.SPOT, "spot", parentId = "comment")).value.items.single().parentId)
            f.response = """{"items":[${f.comment("reply", "other")}],"next_cursor":null}"""
            assertEquals(CatalogResult.InvalidResponse, f.comments.page(CommentTarget.SPOT, "spot", parentId = "comment"))
            f.response = """{"items":[${f.comment(status = "hidden")}],"next_cursor":null}"""
            assertEquals("", assertIs<CatalogResult.Ready<CommentPage>>(f.comments.page(CommentTarget.SPOT, "spot")).value.items.single().body)
            f.response = """{"comment_id":"comment","like_count":7,"liked":true}"""
            assertEquals(CatalogResult.Ready(CommentLike("comment", 7, true)), f.comments.like("comment", true, "key"))
            assertEquals("key", f.lastKey)
            f.response = """{"comment_id":"comment","like_count":6,"liked":false}"""
            assertEquals(CatalogResult.Ready(CommentLike("comment", 6, false)), f.comments.like("comment", false, "key"))
            f.response = """{"comment_id":"wrong","like_count":6,"liked":false}"""
            assertEquals(CatalogResult.InvalidResponse, f.comments.like("comment", false, "key"))
            f.response = """{"comment_id":"comment","like_count":6,"liked":"false"}"""
            assertEquals(CatalogResult.InvalidResponse, f.comments.like("comment", false, "key"))
        } finally { f.client.close() }
    }
    @Test fun commentConsentAndUnavailableWritesNeverReplayOrLoseSession() = runTest {
        val f = CommunityFixture()
        try {
            f.login()
            f.allowed = false
            assertEquals(CatalogResult.HttpError(409), f.comments.create(CommentTarget.SPOT, "spot", "Hello", null))
            assertEquals(0, f.writes)
            f.allowed = true; f.status = HttpStatusCode.ServiceUnavailable
            assertEquals(CatalogResult.Maintenance(), f.comments.create(CommentTarget.SPOT, "spot", "Hello", null))
            assertEquals(1, f.writes)
            assertTrue(f.session.view.value.signedIn)
            f.status = HttpStatusCode.Forbidden
            assertEquals(CatalogResult.HttpError(403), f.comments.like("comment", true, "key"))
            f.status = HttpStatusCode.OK; f.networkFailure = true
            assertEquals(CatalogResult.NetworkError, f.comments.page(CommentTarget.LOCATION, "city"))
            assertTrue(f.session.view.value.signedIn)
        } finally { f.client.close() }
    }
    @Test fun reportsAndAppealsUseOnlyOwnerProjectionAndExactReasonFamilies() = runTest {
        val f = CommunityFixture()
        try {
            f.login()
            CatalogReportReason.entries.forEach { reason ->
                assertIs<CatalogResult.Ready<ReportReceipt>>(f.moderation.catalogReport(CatalogReportTarget.SPOT, "spot", reason, "  Incorrect details  ", "key"))
                val body = Json.parseToJsonElement(f.body!!).jsonObject
                assertEquals(reason.wire, body["reason_code"]!!.jsonPrimitive.content)
                assertEquals("plain", body["details_format"]!!.jsonPrimitive.content)
                assertEquals("Incorrect details", body["details"]!!.jsonPrimitive.content)
                assertEquals("key", f.lastKey)
            }
            assertIs<CatalogResult.Ready<*>>(f.moderation.catalogReport(CatalogReportTarget.LOCATION, "city", CatalogReportReason.OTHER, "Details", "key"))
            val before = f.writes
            assertEquals(CatalogResult.HttpError(422), f.moderation.catalogReport(CatalogReportTarget.SPOT, "spot", CatalogReportReason.OTHER, "xx", "key"))
            assertEquals(CatalogResult.HttpError(422), f.moderation.submitAppeal("action", "short", "key"))
            assertEquals(before, f.writes)
            assertEquals(3, assertIs<CatalogResult.Ready<ItineraryReportPreview>>(f.moderation.itineraryPreview("plan")).value.stopCount)
            ItineraryReportReason.entries.forEach {
                assertIs<CatalogResult.Ready<*>>(f.moderation.itineraryReport("plan", it, "", "key"))
                assertEquals(JsonNull, Json.parseToJsonElement(f.body!!).jsonObject["details"])
            }
            val action = assertIs<CatalogResult.Ready<List<OwnerModerationAction>>>(f.moderation.actions()).value.single()
            assertTrue(action.appealAvailable)
            assertEquals(ModerationActionKind.HIDE, action.action)
            assertFalse(action.toString().contains("internal-secret"))
            assertEquals(AppealStatus.SUBMITTED, assertIs<CatalogResult.Ready<TravelerAppeal>>(f.moderation.submitAppeal("action", "Please review this decision", "key")).value.status)
            assertEquals("Please review this decision", Json.parseToJsonElement(f.body!!).jsonObject["reason"]!!.jsonPrimitive.content)
            assertIs<CatalogResult.Ready<*>>(f.moderation.appeal("action"))
            f.response = """[]"""
            assertEquals(CatalogResult.InvalidResponse, f.moderation.appeal("action"))
            f.response = null; f.status = HttpStatusCode.ServiceUnavailable
            val count = f.writes
            assertEquals(CatalogResult.Maintenance(), f.moderation.submitAppeal("action", "Please review this decision", "key"))
            assertEquals(count + 1, f.writes)
            assertTrue(f.session.view.value.signedIn)
            f.status = HttpStatusCode.NotFound
            assertEquals(CatalogResult.HttpError(404), f.moderation.itineraryPreview("plan"))
            f.networkFailure = true
            assertEquals(CatalogResult.NetworkError, f.moderation.actions())
        } finally { f.client.close() }
    }
}

private class CommunityFixture {
    var status = HttpStatusCode.OK
    var response: String? = null
    var allowed = true
    var networkFailure = false
    var body: String? = null
    var query = ""
    var writes = 0
    var lastKey: String? = null
    var authorPhoto: String? = "http://unsafe.invalid/avatar"
    var metadataPhoto: String? = null
    fun comment(id: String = "comment", parent: String? = null, status: String = "visible") = """{"id":"$id","author":{"profile_id":"profile","username":"traveler","avatar_url":${authorPhoto?.let { JsonPrimitive(it) } ?: JsonNull}},"body":"<b>plain</b>","status":"$status","like_count":2,"liked":false,"created_at":"2026-10-16T00:00:00Z","parent_comment_id":${parent?.let { "\"$it\"" } ?: "null"},"reply_count":0}"""
    private val appeal = """{"id":"appeal","moderation_action_id":"action","status":"submitted","submitted_at":"2026-10-16T00:00:00Z","decided_at":null}"""
    val client = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        val data = when {
            path.endsWith("/profile-identities") -> """{"traveler":{"level":2,"avatar_url":${metadataPhoto?.let { JsonPrimitive(it) } ?: JsonNull}}}"""
            path.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"fixture","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"protected","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
            path.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
            else -> {
                assertEquals("Bearer fixture", request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                query = request.url.encodedQuery
                body = (request.body as? TextContent)?.text
                lastKey = request.headers["Idempotency-Key"]
                if (request.method != HttpMethod.Get) writes++
                if (networkFailure) error("Fixture network failure")
                val payload = response ?: when {
                    path.endsWith("/total") -> """{"total_comments":2}"""
                    path.contains("/comment-threads/") && request.method == HttpMethod.Get -> """{"items":[${comment()}],"next_cursor":"next"}"""
                    path.contains("/comment-threads/") -> comment()
                    path.endsWith("/report-preview") -> """{"id":"plan","locations":[],"title":"Plan","description":"Public summary","stop_count":3}"""
                    path.endsWith("/itinerary-moderation-actions") -> """[{"id":"action","itinerary_id":"plan","itinerary_title":"Plan","action":"hide","reason_code":"privacy","created_at":"2026-10-16T00:00:00Z","appeal_available":true,"appeal":null,"internal_notes":"internal-secret"}]"""
                    path.endsWith("/appeal") -> appeal
                    else -> """{"id":"report","status":"open","internal_notes":"internal-secret"}"""
                }
                return@MockEngine respond("""{"meta":{"code":"OK"},"error":null,"data":$payload}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })
    private val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
    val session = TravelerSession(api, object : RefreshCredentialStore {
        override fun read(): String? = null
        override fun write(credential: String) = Unit
        override fun clear() = Unit
    }, "en")
    val comments = NativeCommentApi(api.catalogTransport(), session, object : TravelerConsentGate {
        override suspend fun ensure(): CatalogResult<Unit> = if (allowed) CatalogResult.Ready(Unit) else CatalogResult.HttpError(409)
    })
    val moderation = NativeModerationApi(api.catalogTransport(), session)
    suspend fun login() { session.login("fixture@example.invalid", "fixture-password") }
}
