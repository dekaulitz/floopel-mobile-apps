package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.AppPlatform
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TripRecoveryStore
import com.floopel.feature.traveler.domain.repository.RefreshCredentialStore
import com.floopel.feature.traveler.feature.auth.TravelerSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NativeQuestGroupRecoveryTest {
    @Test fun lostCreateReadAndBlockedInviteRequireReviewedExplicitReleaseBeforeExactlyOneInvite() = runTest {
        val f = QuestGroupRecoveryFixture()
        try {
            f.login(); f.loseCreateReply = true
            assertEquals(CatalogResult.NetworkError, f.repository.createGroup(f.questId, "id", "create-a"))
            val record = f.record
            assertEquals(f.groupId, assertIs<CatalogResult.Ready<QuestGroupBatch<QuestGroup>>>(f.repository.groups(1, "id", f.questId)).value.items.single().id)
            assertEquals(CatalogResult.HttpError(409, code = "PENDING_OPERATION"), f.repository.invite(f.groupId, "guest_b", "id", "invite-b"))
            assertEquals(record, f.record); assertEquals<List<String?>>(listOf("create-a"), f.writeKeys)
            assertEquals(CatalogResult.HttpError(409), f.repository.startNewAction())
            val review = assertIs<CatalogResult.Ready<QuestGroupRecoveryReview>>(f.repository.reviewPendingMutation("id"))
            assertEquals(f.groupId, review.value.groups.single().id); assertEquals(record, f.record)
            assertEquals(1, f.writeKeys.size)
            assertEquals(CatalogResult.Ready(Unit), f.repository.startNewAction()); assertNull(f.record)
            assertEquals(1, f.writeKeys.size)
            assertIs<CatalogResult.Ready<QuestGroupInvitation>>(f.repository.invite(f.groupId, "guest_b", "id", "invite-b"))
            assertEquals<List<String?>>(listOf("create-a", "invite-b"), f.writeKeys); assertEquals(1, f.createdCount)
        } finally { f.client.close() }
    }
    @Test fun exactCreateRetryKeepsTheOriginalKeyAndReceiptAfterTheGroupBecomesVisible() = runTest {
        val f = QuestGroupRecoveryFixture()
        try {
            f.login(); f.loseCreateReply = true; f.repository.createGroup(f.questId, "id", "create-a")
            f.repository.groups(1, "id", f.questId)
            assertIs<CatalogResult.Ready<QuestGroup>>(f.repository.createGroup(f.questId, "id", "discarded-proposal"))
            assertEquals<List<String?>>(listOf("create-a", "create-a"), f.writeKeys); assertEquals(1, f.createdCount); assertNull(f.record)
        } finally { f.client.close() }
    }
    @Test fun expiryUsesFakeClockAndDoesNotRotateOrReplayTheOldIntent() = runTest {
        val f = QuestGroupRecoveryFixture()
        try {
            f.login(); f.loseCreateReply = true; f.repository.createGroup(f.questId, "id", "create-a")
            val record = f.record; f.now += 86_400_001
            assertEquals(CatalogResult.HttpError(409, code = "EXPIRED_OPERATION"), f.repository.createGroup(f.questId, "id", "unused"))
            assertEquals(CatalogResult.HttpError(409, code = "PENDING_OPERATION"), f.repository.invite(f.groupId, "guest_b", "id", "unused"))
            assertEquals(record, f.record); assertEquals(1, f.writeKeys.size)
            assertIs<CatalogResult.Ready<*>>(f.repository.reviewPendingMutation("id")); assertEquals(record, f.record)
            assertEquals(CatalogResult.Ready(Unit), f.repository.startNewAction()); assertEquals(1, f.writeKeys.size)
        } finally { f.client.close() }
    }
    @Test fun failedChangedAndForeignActorReviewCannotReleaseTheJournal() = runTest {
        val f = QuestGroupRecoveryFixture()
        try {
            f.login(); f.loseCreateReply = true; f.repository.createGroup(f.questId, "id", "create-a")
            val record = requireNotNull(f.record)
            f.failReads = true
            assertEquals(CatalogResult.NetworkError, f.repository.reviewPendingMutation("id"))
            assertEquals(CatalogResult.HttpError(409), f.repository.startNewAction()); assertEquals(record, f.record)
            f.failReads = false; f.beforeGroups = { f.record = record.replace("create-a", "changed") }
            assertEquals(CatalogResult.HttpError(409), f.repository.reviewPendingMutation("id"))
            assertEquals(CatalogResult.HttpError(409), f.repository.startNewAction())
            f.beforeGroups = {}; assertIs<CatalogResult.Ready<*>>(f.repository.reviewPendingMutation("id"))
            f.record = record; assertEquals(CatalogResult.HttpError(409), f.repository.startNewAction())
            assertIs<CatalogResult.Ready<*>>(f.repository.reviewPendingMutation("id"))
            f.actor = "other-actor"; f.login()
            assertEquals(CatalogResult.HttpError(409), f.repository.startNewAction())
            assertEquals(CatalogResult.HttpError(401), f.repository.reviewPendingMutation("id"))
            assertEquals(record, f.record); assertEquals(1, f.writeKeys.size)
            f.storageFails = true
            assertEquals(CatalogResult.InvalidResponse, f.repository.reviewPendingMutation("id"))
            assertEquals(CatalogResult.HttpError(409), f.repository.startNewAction())
        } finally { f.client.close() }
    }
    @Test fun authorizedReviewReadsAllInvitationPagesIncludingTheManagersSentListWithoutWrites() = runTest {
        val f = QuestGroupRecoveryFixture()
        try {
            f.login(); f.created = true; f.invitationTotal = 21
            val review = assertIs<CatalogResult.Ready<QuestGroupRecoveryReview>>(f.repository.reviewPendingMutation("en"))
            assertEquals(21, review.value.invitations.size)
            assertTrue(f.invitationReads.contains(Triple(null, 2, "en")))
            assertTrue(f.invitationReads.contains(Triple(f.groupId, 2, "en")))
            assertEquals(0, f.writeKeys.size)
        } finally { f.client.close() }
    }
}

/** Actual transport adapter and durable store, with no real API or device-clock dependency. */
internal class QuestGroupRecoveryFixture {
    val questId = "11111111-1111-4111-8111-111111111111"
    val groupId = "22222222-2222-4222-8222-222222222222"
    var actor = "fixture-owner"
    var now = 1_800_000_000_000L
    var record: String? = null
    var storageFails = false
    var created = false
    var createdCount = 0
    var loseCreateReply = false
    var failReads = false
    var failPageTwo = false
    var forbiddenPageTwo = false
    var conflictWrite = false
    var overlapPageTwo = false
    var invitationTotal = 0
    var beforeGroups: suspend () -> Unit = {}
    var beforeInvitations: suspend (String?, Int) -> Unit = { _, _ -> }
    val writeKeys = mutableListOf<String?>()
    val invitationReads = mutableListOf<Triple<String?, Int, String>>()
    fun invitationId(n: Int) = "33333333-3333-4333-8333-${n.toString().padStart(12, '0')}"
    private fun group() = """{"id":"$groupId","quest_id":"$questId","quest_title":"Quest fixture","quest_slug":"quest-fixture","manager_profile_id":"$actor","status":"active","member_count":1,"group_size_limit":4,"created_at":"2026-11-08T00:00:00Z"}"""
    private fun invitation(n: Int) = """{"id":"${invitationId(n)}","group_id":"$groupId","quest_id":"$questId","quest_title":"Invitation $n","profile_id":"guest-profile","inviter_profile_id":"$actor","status":"pending","created_at":"2026-11-08T00:00:00Z","decided_at":null,"username":"guest_b","inviter_username":"fixture_owner"}"""
    val client = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        var status = HttpStatusCode.OK
        var pagination = ""
        val data = when {
            path.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"fixture","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"protected","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
            path.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
            path.endsWith("/me") -> """{"profile":{"id":"$actor"}}"""
            request.method != HttpMethod.Get -> {
                writeKeys += request.headers["Idempotency-Key"]
                if (conflictWrite) return@MockEngine respond("""{"meta":{"code":"CONFLICT"},"error":{"code":"CONFLICT"},"data":null}""", HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType,"application/json"))
                status = HttpStatusCode.Created
                if (path.endsWith("/quest-groups")) {
                    if (!created) { created = true; createdCount++ }
                    if (loseCreateReply) { loseCreateReply = false; error("fixture accepted response lost") }
                    group()
                } else invitation(1)
            }
            else -> {
                if (failReads) error("fixture offline")
                when {
                    path.endsWith("/quest-groups") -> {
                        beforeGroups()
                        val total = if (created) 1 else 0
                        pagination = ",\"pagination\":{\"page\":1,\"per_page\":20,\"total\":$total,\"total_pages\":$total}"
                        if (created) "[${group()}]" else "[]"
                    }
                    path.endsWith("/members") -> {
                        pagination = ",\"pagination\":{\"page\":1,\"per_page\":20,\"total\":0,\"total_pages\":0}"
                        "[]"
                    }
                    path.endsWith("/quest-group-invitations") || path.endsWith("/invitations") -> {
                        val page = requireNotNull(request.url.parameters["page"]).toInt()
                        val filter = if (path.endsWith("/quest-group-invitations")) null else path.substringAfter("/quest-groups/").substringBefore('/')
                        invitationReads += Triple(filter, page, requireNotNull(request.url.parameters["locale"]))
                        beforeInvitations(filter, page)
                        if (page == 2 && forbiddenPageTwo) return@MockEngine respond("""{"meta":{"code":"FORBIDDEN"},"error":{"code":"FORBIDDEN"},"data":null}""",HttpStatusCode.Forbidden,headersOf(HttpHeaders.ContentType,"application/json"))
                        if (page == 2 && failPageTwo) error("fixture page two offline")
                        assertEquals("20", request.url.parameters["per_page"])
                        pagination = ",\"pagination\":{\"page\":$page,\"per_page\":20,\"total\":$invitationTotal,\"total_pages\":${(invitationTotal+19)/20}}"
                        val from = if (page == 2 && overlapPageTwo) 20 else (page-1)*20+1
                        (from..minOf(page*20,invitationTotal)).joinToString(prefix="[",postfix="]") { invitation(it) }
                    }
                    path.contains("/quest-group-invitations/") -> invitation(path.substringAfterLast('-').toInt())
                    else -> group()
                }
            }
        }
        respond("""{"meta":{"code":"OK","request_id":"44444444-4444-4444-8444-444444444444","timestamp":1792922400000$pagination},"error":null,"data":$data}""", status, headersOf(HttpHeaders.ContentType,"application/json"))
    })
    private val api = NativeApi(client,"https://fixture.invalid",AppPlatform.ANDROID)
    val session = TravelerSession(api, object : RefreshCredentialStore {
        var credential: String? = null
        override fun read() = credential
        override fun write(credential: String) { this.credential=credential }
        override fun clear() { credential=null }
    },"id")
    private val store = object : TripRecoveryStore {
        override fun read(): String? { check(!storageFails); return record }
        override fun write(record: String) { check(!storageFails); this@QuestGroupRecoveryFixture.record=record }
        override fun clear() { check(!storageFails); record=null }
    }
    val repository = NativeQuestGroupApi(api.catalogTransport(),session,store) { now }
    suspend fun login() { session.login("fixture@example.invalid","fixture-password") }
}
