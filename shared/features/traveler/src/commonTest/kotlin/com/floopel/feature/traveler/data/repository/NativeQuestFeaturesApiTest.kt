package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.domain.repository.RefreshCredentialStore
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.feature.auth.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NativeQuestFeaturesApiTest {
    @Test fun untakenPaginationAcceptsAnEmptyPageWhenConcurrentAdmissionsShrinkTheCatalog() = runTest {
        val f = QuestFeaturesFixture()
        try {
            f.session.login("fixture@example.invalid", "fixture-password")
            f.data = "[]"
            f.pagination = ""","pagination":{"page":2,"per_page":8,"total":0,"total_pages":0}"""
            val result = assertIs<CatalogResult.Ready<LocationQuestPage>>(f.catalog.untakenLocationQuests(f.id, "id", 2)).value
            assertEquals(LocationQuestPage(emptyList(), 2, 0), result)
            f.pagination = ""","pagination":{"page":"2","per_page":8,"total":0,"total_pages":0}"""
            assertEquals(CatalogResult.InvalidResponse, f.catalog.untakenLocationQuests(f.id, "id", 2))
        } finally { f.client.close() }
    }
    @Test fun explorerUntakenQuestsUseOwnedFiltersAndPreserveBadgeAndActivityMetadata() = runTest {
        val f = QuestFeaturesFixture()
        try {
            f.session.login("fixture@example.invalid", "fixture-password")
            f.data = """[{"id":"${f.id}","slug":"kopi","title":"Coffee","is_taken":false,"availability":"active","execution_status":"available","activity_count":4,"reward_preview":{"xp":100,"badge":{"id":"badge","code":"coffee","name":"Coffee badge","image":{"url":"https://fixture.invalid/media/badge.png"}}},"quota":null,"locations":[]}]"""
            f.pagination = ""","pagination":{"page":1,"per_page":8,"total":1,"total_pages":1}"""
            val q = assertIs<CatalogResult.Ready<LocationQuestPage>>(f.catalog.untakenLocationQuests(f.id, "en", 1)).value.items.single()
            assertEquals("/api/apps/v1/gamification/me/quests", f.path)
            assertEquals(mapOf("exclude_taken" to "true", "status" to "active", "locale" to "en", "location_id" to f.id, "page" to "1", "per_page" to "8"), f.query)
            assertEquals(4, q.activityCount)
            assertEquals(100L, q.xp)
            assertEquals("https://fixture.invalid/media/badge.png", q.badgeImage?.url)
            assertEquals("Coffee badge", q.badgeImage?.alt)
            assertEquals(1, f.reads)
            assertEquals(0, f.writes)
            assertEquals(CatalogResult.HttpError(422), f.catalog.untakenLocationQuests("bad/location", "en", 1))
            assertEquals(1, f.reads)
        } finally { f.client.close() }
    }

    @Test fun explorerNeverLabelsPublicTakenOrMalformedRowsAsUntaken() = runTest {
        val f = QuestFeaturesFixture()
        try {
            f.session.login("fixture@example.invalid", "fixture-password")
            val row = """{"id":"${f.id}","slug":"kopi","title":"Coffee","is_taken":false,"availability":"active","execution_status":"available","activity_count":4,"reward_preview":{"xp":100,"badge":null},"quota":null,"locations":[]}"""
            f.pagination = ""","pagination":{"page":1,"per_page":8,"total":1,"total_pages":1}"""
            for (invalid in listOf(row.replace("\"is_taken\":false,", ""),
                row.replace("\"is_taken\":false", "\"is_taken\":true"),
                row.replace("\"is_taken\":false", "\"is_taken\":\"false\""),
                row.replace("\"activity_count\":4", "\"activity_count\":-1"),
                row.replace("\"availability\":\"active\"", "\"availability\":\"ended\""))) {
                f.data = "[$invalid]"
                assertEquals(CatalogResult.InvalidResponse, f.catalog.untakenLocationQuests(f.id, "id", 1))
            }
            f.status = HttpStatusCode.Forbidden; f.code = "FORBIDDEN"; f.data = "null"
            f.error = """{"message":"Unavailable","details":[]}"""
            val denied = assertIs<CatalogResult.HttpError>(f.catalog.untakenLocationQuests(f.id, "id", 1))
            assertEquals("FORBIDDEN", denied.code)
            assertEquals(6, f.reads)
            assertEquals(0, f.writes)
        } finally { f.client.close() }
    }

    @Test fun groupCreateAndMutationUseNativeIdentityAndAnExplicitStableKey() = runTest {
        val f = QuestFeaturesFixture()
        try {
            f.session.login("fixture@example.invalid", "fixture-password")
            f.status = HttpStatusCode.Created
            f.data = f.group
            val created = assertIs<CatalogResult.Ready<QuestGroup>>(f.groups.createGroup(f.id.uppercase(), "en", "same-intent")).value
            assertEquals("profile-text", created.managerProfileId)
            assertEquals("/api/apps/v1/mobile/gamification/quest-groups", f.path)
            assertEquals("same-intent", f.key)
            assertEquals("en", f.query["locale"])
            assertEquals(1, f.writes)
            assertEquals(CatalogResult.HttpError(422), f.groups.createGroup(f.id, "en", ""))
            assertEquals(1, f.writes)
            f.status = HttpStatusCode.NoContent
            assertEquals(CatalogResult.Ready(Unit), f.groups.leaveGroup(f.id, "leave-intent"))
            assertEquals("/api/apps/v1/mobile/gamification/quest-groups/${f.id}/members/me", f.path)
            assertEquals("leave-intent", f.key)
        } finally { f.client.close() }
    }

    @Test fun groupsUseCanonicalBatchTransportAndRejectMalformedSuccess() = runTest {
        val f = QuestFeaturesFixture()
        try {
            f.session.login("fixture@example.invalid", "fixture-password")
            f.data = "[${f.group}]"
            f.pagination = ""","pagination":{"page":1,"per_page":20,"total":1,"total_pages":1}"""
            val result = assertIs<CatalogResult.Ready<QuestGroupBatch<QuestGroup>>>(f.groups.groups(1, "id", f.id)).value
            assertEquals(1, result.items.size)
            assertEquals("20", f.query["per_page"])
            assertEquals(f.id, f.query["quest_id"])
            assertEquals(0, f.writes)
            f.code = "ok"
            assertEquals(CatalogResult.InvalidResponse, f.groups.groups(1, "id"))
            f.code = "OK"
            f.data = "[${f.group.replace("\"quest_title\":null,", "")}]"
            assertEquals(CatalogResult.InvalidResponse, f.groups.groups(1, "id"))
        } finally { f.client.close() }
    }

    @Test fun failedMutationsAreNotReplayedAndRetainBusinessCodeAndRetryTiming() = runTest {
        val f = QuestFeaturesFixture()
        try {
            f.session.login("fixture@example.invalid", "fixture-password")
            f.status = HttpStatusCode.Conflict
            f.error = "{\"code\":\"CONFLICT\",\"message\":\"Changed\",\"details\":null}"
            f.data = "null"
            f.code = "CONFLICT"
            val conflict = assertIs<CatalogResult.HttpError>(f.groups.createGroup(f.id, "en", "same-intent"))
            assertEquals("CONFLICT", conflict.code)
            assertEquals(1, f.writes)
            f.status = HttpStatusCode.TooManyRequests
            f.code = "RATE_LIMITED"
            val throttled = assertIs<CatalogResult.HttpError>(f.groups.leaveGroup(f.id, "leave-intent"))
            assertEquals(17L, throttled.retryAfterSeconds)
            assertEquals(2, f.writes)
        } finally { f.client.close() }
    }

    @Test fun hiddenPolicyDoesNotTriggerPublicReadAndGrantedCompletionUsesOnlyOwnerFacts() = runTest {
        val f = QuestFeaturesFixture()
        try {
            f.session.login("fixture@example.invalid", "fixture-password")
            f.data = """{"quest_id":"${f.id}","locations":[],"quest_slug":"kopi","status":"not_completed","is_hidden":true}"""
            assertNull(assertIs<CatalogResult.Ready<QuestShareFacts?>>(f.sharing.sharingFacts("kopi", "en")).value)
            assertEquals(1, f.reads)
            f.data = """{"quest_id":"${f.id}","locations":[],"quest_slug":"kopi","status":"completed","is_hidden":true,"completion":{"quest_id":"${f.id}","locations":[],"quest_slug":"kopi","quest_revision_id":"${f.revision}","quest_title":"Bound title","completed_at":"2026-10-25T00:00:00Z","xp_awarded":100,"share_available":true,"reward_status":"granted"}}"""
            val facts = assertNotNull(assertIs<CatalogResult.Ready<QuestShareFacts?>>(f.sharing.sharingFacts("kopi", "en")).value)
            assertEquals("Bound title", facts.title)
            assertEquals(QuestShareContext.COMPLETION, facts.context)
            assertEquals(2, f.reads)
            assertEquals(0, f.writes)
            f.data = f.data.replace("\"reward_status\":\"granted\"", "\"reward_status\":\"revoked\"")
            assertNull(assertIs<CatalogResult.Ready<QuestShareFacts?>>(f.sharing.sharingFacts("kopi", "en")).value)
        } finally { f.client.close() }
    }

    @Test fun invitationAndManagerOperationsRetainObjectPathsAndRealProfileText() = runTest {
        val f = QuestFeaturesFixture()
        try {
            f.session.login("fixture@example.invalid", "fixture-password")
            f.status = HttpStatusCode.Created
            f.data = """{"id":"${f.id}","group_id":"${f.id}","quest_id":"${f.id}","quest_title":null,"profile_id":"recipient-text","inviter_profile_id":"profile-text","status":"pending","created_at":"2026-10-25T00:00:00Z","decided_at":null,"username":"deka","inviter_username":"organizer"}"""
            val invited = assertIs<CatalogResult.Ready<QuestGroupInvitation>>(f.groups.invite(f.id, "deka", "en", "invite-intent")).value
            assertEquals("deka", invited.username)
            assertEquals("organizer", invited.inviterUsername)
            assertEquals("/api/apps/v1/mobile/gamification/quest-groups/${f.id}/invitations", f.path)
            assertTrue(f.body.orEmpty().contains("\"username\":\"deka\""))
            f.status = HttpStatusCode.OK
            assertIs<CatalogResult.Ready<QuestGroupInvitation>>(f.groups.invitation(f.id, "en"))
            assertEquals("/api/apps/v1/mobile/gamification/quest-group-invitations/${f.id}", f.path)
            f.data = f.data.replace("\"status\":\"pending\"", "\"status\":\"accepted\"")
            assertIs<CatalogResult.Ready<QuestGroupInvitation>>(f.groups.decideInvitation(f.id, true, "en", "accept-intent"))
            assertTrue(f.body.orEmpty().contains("\"status\":\"accepted\""))
            f.data = f.group.replace("profile-text", "new-profile-text")
            assertIs<CatalogResult.Ready<QuestGroup>>(f.groups.transferManager(f.id, "new-profile-text", "en", "transfer-intent"))
            assertTrue(f.body.orEmpty().contains("\"profile_id\":\"new-profile-text\""))
            f.status = HttpStatusCode.NoContent
            assertIs<CatalogResult.Ready<Unit>>(f.groups.cancelInvitation(f.id, f.id, "cancel-intent"))
            assertEquals("/api/apps/v1/mobile/gamification/quest-groups/${f.id}/invitations/${f.id}", f.path)
            assertIs<CatalogResult.Ready<Unit>>(f.groups.disbandGroup(f.id, "disband-intent"))
            assertEquals("/api/apps/v1/mobile/gamification/quest-groups/${f.id}", f.path)
        } finally { f.client.close() }
    }

    @Test fun canonicalErrorDetailsAcceptArrayAndNullWithoutAcceptingMalformedDetails() = runTest {
        val f = QuestFeaturesFixture()
        try {
            f.session.login("fixture@example.invalid", "fixture-password")
            f.status = HttpStatusCode.Conflict; f.code = "CONFLICT"; f.data = "null"
            for (details in listOf("null", "[]")) {
                f.error = """{"message":"Changed","details":$details}"""
                assertEquals("CONFLICT", assertIs<CatalogResult.HttpError>(f.groups.leaveGroup(f.id, "same-intent")).code)
            }
            f.error = """{"message":"Changed","details":{}}"""
            assertNull(assertIs<CatalogResult.HttpError>(f.groups.leaveGroup(f.id, "same-intent")).code)
            assertEquals(3, f.writes)
        } finally { f.client.close() }
    }

    @Test fun groupDetailMembersAndBothInvitationListsUseTheirOwnedReadPaths() = runTest {
        val f = QuestFeaturesFixture()
        try {
            f.session.login("fixture@example.invalid", "fixture-password")
            f.data = f.group
            assertEquals("kopi", assertIs<CatalogResult.Ready<QuestGroup>>(f.groups.group(f.id, "en")).value.questSlug)
            assertEquals("/api/apps/v1/mobile/gamification/quest-groups/${f.id}", f.path)
            f.data = "[]"
            f.pagination = ""","pagination":{"page":1,"per_page":20,"total":0,"total_pages":0}"""
            assertTrue(assertIs<CatalogResult.Ready<QuestGroupBatch<QuestGroupMember>>>(f.groups.members(f.id, 1)).value.items.isEmpty())
            assertEquals("/api/apps/v1/mobile/gamification/quest-groups/${f.id}/members", f.path)
            assertIs<CatalogResult.Ready<QuestGroupBatch<QuestGroupInvitation>>>(f.groups.invitations(1, "en", f.id))
            assertEquals("/api/apps/v1/mobile/gamification/quest-groups/${f.id}/invitations", f.path)
            assertIs<CatalogResult.Ready<QuestGroupBatch<QuestGroupInvitation>>>(f.groups.invitations(1, "en"))
            assertEquals("/api/apps/v1/mobile/gamification/quest-group-invitations", f.path)
            assertEquals(CatalogResult.HttpError(422), f.groups.members("foreign/path", 1))
            assertEquals(CatalogResult.HttpError(422), f.groups.invitations(0, "en"))
            assertEquals(0, f.writes)
        } finally { f.client.close() }
    }

    @Test fun publicInvitationSharingReadsCurrentMetadataAndPreservesAccessErrors() = runTest {
        val f = QuestFeaturesFixture()
        try {
            f.session.login("fixture@example.invalid", "fixture-password")
            f.data = """{"quest_id":"${f.id}","locations":[],"quest_slug":"kopi","status":"not_completed","is_hidden":false}"""
            f.publicData = """{"id":"${f.id}","slug":"kopi","locations":[],"title":"Fresh title","availability":"active","execution_status":"available"}"""
            val ready = assertNotNull(assertIs<CatalogResult.Ready<QuestShareFacts?>>(f.sharing.sharingFacts("kopi", "en")).value)
            assertEquals(QuestShareContext.INVITATION, ready.context)
            assertEquals("Fresh title", ready.title)
            assertEquals(2, f.reads)
            f.publicData = f.publicData!!.replace(f.id, f.revision)
            assertEquals(CatalogResult.InvalidResponse, f.sharing.sharingFacts("kopi", "en"))
            assertEquals(CatalogResult.HttpError(422), f.sharing.sharingFacts("foreign/path", "en"))
            f.status = HttpStatusCode.Forbidden; f.code = "FORBIDDEN"; f.data = "null"
            f.error = """{"message":"Unavailable","details":[]}"""
            assertEquals("FORBIDDEN", assertIs<CatalogResult.HttpError>(f.sharing.sharingFacts("kopi", "en")).code)
            assertTrue(f.session.view.value.signedIn)
            assertEquals(0, f.writes)
        } finally { f.client.close() }
    }
}

private class QuestFeaturesFixture {
    val id = "abcdef11-1111-4111-8111-111111111111"
    val revision = "22222222-2222-4222-8222-222222222222"
    val group = """{"id":"$id","quest_id":"$id","quest_title":null,"locations":[],"quest_slug":"kopi","manager_profile_id":"profile-text","status":"active","member_count":1,"group_size_limit":4,"created_at":"2026-10-25T00:00:00Z"}"""
    var data = "{}"
    var publicData: String? = null
    var code = "OK"
    var error = "null"
    var pagination = ""
    var status = HttpStatusCode.OK
    var path = ""
    var key: String? = null
    var body: String? = null
    var query: Map<String, String> = emptyMap()
    var writes = 0
    var reads = 0
    private var credential: String? = null
    val client = HttpClient(MockEngine { request ->
        val current = request.url.encodedPath
        val response = when {
            current.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"fixture","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"protected","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
            current.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
            else -> {
                path = current
                key = request.headers["Idempotency-Key"]
                body = (request.body as? TextContent)?.text
                query = request.url.parameters.entries().associate { it.key to it.value.single() }
                assertEquals("Bearer fixture", request.headers[HttpHeaders.Authorization])
                assertEquals("ANDROID", request.headers["X-App-Platform"])
                assertEquals("0.1.0-dev", request.headers["X-App-Version"])
                assertNull(request.headers[HttpHeaders.Cookie])
                assertNull(request.headers["X-CSRF-Token"])
                if (request.method == HttpMethod.Get) reads++ else writes++
                val operationData = publicData?.takeIf { current.endsWith("/gamification/quests/kopi") } ?: data
                return@MockEngine respond(if (status == HttpStatusCode.NoContent) "" else """{"meta":{"code":"$code","request_id":"33333333-3333-4333-8333-333333333333","timestamp":1792922400000$pagination},"error":$error,"data":$operationData}""",
                    status, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("17")))
            }
        }
        respond("""{"meta":{"code":"OK","request_id":"33333333-3333-4333-8333-333333333333","timestamp":1792922400000},"error":null,"data":$response}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })
    private val native = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID, appVersion = "0.1.0-dev")
    val session = TravelerSession(native, object : RefreshCredentialStore {
        override fun read() = credential
        override fun write(credential: String) { this@QuestFeaturesFixture.credential = credential }
        override fun clear() { credential = null }
    }, "en")
    val groups = NativeQuestGroupApi(native.catalogTransport(), session)
    val sharing = NativeQuestSharingApi(native.catalogTransport(), session)
    val catalog = NativeTravelerCatalog(AppsTravelerCatalog(native.catalogTransport()), native.catalogTransport(), session)
}
