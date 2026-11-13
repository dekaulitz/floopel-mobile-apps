package com.floopel.feature.traveler.feature.auth

import com.floopel.feature.traveler.core.network.AppPlatform
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.domain.model.QuestLocation
import com.floopel.feature.traveler.domain.model.QuestLocationUnavailable
import com.floopel.feature.traveler.domain.repository.QuestAttestation
import com.floopel.feature.traveler.domain.repository.QuestLocationSource
import com.floopel.feature.traveler.domain.repository.RefreshCredentialStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LinkedHiddenQuestTest {
    @Test
    fun coldRecipientOffersExplicitRevealWithoutLocationOrProof() = runTest {
        Fixture().use { f ->
            f.session.login("fixture@example.invalid", "password")
            f.session.openQuest("hidden")
            assertTrue(f.session.view.value.hiddenRevealOffered)
            assertFalse(f.session.view.value.ownerSnapshotOnly)
            assertNull(f.session.view.value.questDetail)
            assertNull(f.session.view.value.questOwner)
            assertNull(f.session.view.value.failure)
            assertEquals(0, f.locations)
            assertEquals(0, f.credentials)
            f.session.revealLinkedQuest()
            val state = f.session.view.value
            assertEquals("hidden", state.questDetail?.slug)
            assertTrue(state.hiddenDetail)
            assertFalse(state.hiddenRevealOffered)
            assertFalse(state.ownerSnapshotOnly)
            assertEquals("not_completed", state.questOwner?.status)
            assertEquals(1, f.locations)
            assertEquals(1, f.credentials)
            assertEquals("hidden", f.reveals.single().url.parameters["quest_slug"])
            assertEquals("Bearer execution", f.reveals.single().headers[HttpHeaders.Authorization])
        }
    }

    @Test
    fun coldOwnersKeepBoundFactsWithoutInventingFullDetailOrRevealingLocation() = runTest {
        for (status in listOf("active", "completed")) Fixture().use { f ->
            f.owner = owner(status)
            f.session.login("fixture@example.invalid", "password")
            f.session.openQuest("hidden")
            val state = f.session.view.value
            assertEquals(status, state.questOwner?.status)
            assertTrue(state.ownerSnapshotOnly)
            assertFalse(state.hiddenDetail)
            assertFalse(state.hiddenRevealOffered)
            assertNull(state.questDetail)
            assertNull(state.failure)
            assertEquals(0, f.locations)
            assertEquals(0, f.credentials)
        }
    }

    @Test
    fun oldHiddenCompletionNeverUsesPublicSuccessorAsBoundSnapshot() = runTest {
        Fixture().use { f ->
            f.publicDetail = detail("hidden", "Public successor")
            f.owner = owner("completed")
            f.session.login("fixture@example.invalid", "password")
            f.session.openQuest("hidden")
            assertTrue(f.session.view.value.ownerSnapshotOnly)
            assertNull(f.session.view.value.questDetail)
            assertEquals("Bound title", f.session.view.value.questOwner?.completion?.title)
        }
    }

    @Test
    fun outsideRadiusAndMalformedOrCrossSlugResponsesNeverBecomeEligibleDetail() = runTest {
        for (response in listOf("[]", "[${detail("other")}]", "[${detail()},${detail("other")}]", "[{\"slug\":\"hidden\"}]")) Fixture().use { f ->
            f.revealData = response
            f.session.login("fixture@example.invalid", "password")
            f.session.openQuest("hidden")
            f.session.revealLinkedQuest()
            val state = f.session.view.value
            assertTrue(state.hiddenRevealOffered)
            assertNull(state.questDetail)
            assertFalse(state.hiddenDetail)
            assertEquals(if (response == "[]") 404 else 502, state.failure?.status)
            assertFalse(state.busy)
        }
    }

    @Test
    fun foreignOwnerSlugIsRejectedWithoutOfferingOrTakingLocation() = runTest {
        Fixture().use { f ->
            f.owner = owner("active").replace("\"quest_slug\":\"hidden\"", "\"quest_slug\":\"other\"")
            f.session.login("fixture@example.invalid", "password")
            f.session.openQuest("hidden")
            assertEquals(502, f.session.view.value.failure?.status)
            assertNull(f.session.view.value.questOwner)
            assertFalse(f.session.view.value.hiddenRevealOffered)
            assertEquals(0, f.locations)
        }
    }

    @Test
    fun permissionDenialKeepsExplicitRecoveryAndNeverSendsProof() = runTest {
        Fixture().use { f ->
            f.location = { throw QuestLocationUnavailable() }
            f.session.login("fixture@example.invalid", "password")
            f.session.openQuest("hidden")
            f.session.revealLinkedQuest()
            assertTrue(f.session.view.value.locationUnavailable)
            assertTrue(f.session.view.value.hiddenRevealOffered)
            assertFalse(f.session.view.value.busy)
            assertEquals(0, f.credentials)
            assertTrue(f.reveals.isEmpty())
            f.location = { point }
            f.session.revealLinkedQuest()
            assertEquals("hidden", f.session.view.value.questDetail?.slug)
            assertFalse(f.session.view.value.locationUnavailable)
            assertNull(f.session.view.value.failure)
        }
    }

    @Test
    fun cancellationAndBackgroundDiscardLateForegroundLocation() = runTest {
        for (background in listOf(false, true)) Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val fix = CompletableDeferred<QuestLocation>()
            f.location = { entered.complete(Unit); fix.await() }
            f.session.login("fixture@example.invalid", "password")
            f.session.openQuest("hidden")
            val request = launch { f.session.revealLinkedQuest() }
            entered.await()
            if (background) { f.session.onBackground(); fix.complete(point) } else request.cancel()
            request.join()
            assertTrue(f.session.view.value.hiddenRevealOffered)
            assertFalse(f.session.view.value.busy)
            assertNull(f.session.view.value.questDetail)
            assertEquals(0, f.credentials)
            assertTrue(f.reveals.isEmpty())
        }
    }

    @Test
    fun backgroundRejectsLateProofBeforeCredentialIssuance() = runTest {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val proof = CompletableDeferred<String>()
            f.proof = { entered.complete(Unit); proof.await() }
            f.session.login("fixture@example.invalid", "password")
            f.session.openQuest("hidden")
            val request = launch { f.session.revealLinkedQuest() }
            entered.await()
            f.session.onBackground()
            proof.complete("late-proof")
            request.join()
            assertEquals(0, f.credentials)
            assertTrue(f.reveals.isEmpty())
            assertNull(f.session.view.value.questDetail)
        }
    }

    @Test
    fun accountSwitchDiscardsLateTargetResponseAndDoesNotReadOwnerForNewAccount() = runTest {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            f.beforeReveal = { entered.complete(Unit); response.await() }
            f.session.login("first@example.invalid", "password")
            f.session.openQuest("hidden")
            val request = launch { f.session.revealLinkedQuest() }
            entered.await()
            val reads = f.ownerReads
            f.session.logout()
            f.session.login("second@example.invalid", "password")
            response.complete(Unit)
            request.join()
            assertTrue(f.session.view.value.signedIn)
            assertNull(f.session.view.value.questDetail)
            assertNull(f.session.view.value.questOwner)
            assertFalse(f.session.view.value.hiddenRevealOffered)
            assertEquals(reads, f.ownerReads)
        }
    }

    @Test
    fun accountSwitchDiscardsLatePrivateOwnerSnapshot() = runTest {
        Fixture().use { f ->
            val entered = CompletableDeferred<Unit>()
            val response = CompletableDeferred<Unit>()
            f.owner = owner("completed")
            f.beforeOwner = { entered.complete(Unit); response.await() }
            f.session.login("first@example.invalid", "password")
            val request = launch { f.session.openQuest("hidden") }
            entered.await()
            f.session.logout()
            f.session.login("second@example.invalid", "password")
            response.complete(Unit)
            request.join()
            assertTrue(f.session.view.value.signedIn)
            assertNull(f.session.view.value.questOwner)
            assertFalse(f.session.view.value.ownerSnapshotOnly)
            assertFalse(f.session.view.value.hiddenRevealOffered)
            assertEquals(0, f.locations)
        }
    }

    private class Fixture : AutoCloseable {
        var publicDetail: String? = null
        var owner: String? = null
        var revealData = "[${detail()}]"
        var location: suspend () -> QuestLocation = { point }
        var proof: suspend () -> String = { "proof" }
        var beforeReveal: suspend () -> Unit = {}
        var beforeOwner: suspend () -> Unit = {}
        var locations = 0
        var credentials = 0
        var ownerReads = 0
        val reveals = mutableListOf<HttpRequestData>()
        private val client = HttpClient(MockEngine { request ->
            fun ok(value: String) = respond("""{"meta":{"request_id":"$questId","timestamp":0,"code":"OK"},"error":null,"data":$value}""")
            when {
                request.url.encodedPath.endsWith("/login") -> ok("""{"tokens":{"token_type":"Bearer","access_token":"identity","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}""")
                request.url.encodedPath.endsWith("/logout") -> respond("", HttpStatusCode.NoContent)
                request.url.encodedPath.endsWith("/progress") -> ok("""{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}""")
                request.url.encodedPath.endsWith("/challenges") -> ok("""{"challenge_id":"challenge","platform":"android","challenge":"challenge","expires_at":"2099-01-01T00:00:00Z"}""")
                request.url.encodedPath.endsWith("/credentials") -> { credentials++; ok("""{"access_token":"execution","token_type":"Bearer","expires_in":600}""") }
                request.url.encodedPath.endsWith("/reveal") -> { reveals += request; beforeReveal(); respond("""{"error":null,"data":$revealData,"meta":{"pagination":{"page":1,"total_pages":1}}}""") }
                request.url.encodedPath.endsWith("/me") -> { ownerReads++; beforeOwner(); owner?.let(::ok) ?: respond("{}", HttpStatusCode.NotFound) }
                request.url.encodedPath.endsWith("/hidden") -> publicDetail?.let(::ok) ?: respond("{}", HttpStatusCode.NotFound)
                else -> error("Unexpected request ${request.url.encodedPath}")
            }
        })
        val session = TravelerSession(NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID), object : RefreshCredentialStore {
            override fun read(): String? = null
            override fun write(credential: String) {}
            override fun clear() {}
        }, "en", attestation = object : QuestAttestation {
            override val available = true
            override suspend fun androidIntegrityToken(challenge: String) = proof()
        }, locationSource = object : QuestLocationSource {
            override suspend fun current(): QuestLocation { locations++; return location() }
        })
        override fun close() = client.close()
    }

    companion object {
        private const val questId = "00000000-0000-4000-8000-000000000001"
        private val point = QuestLocation(0.0, 0.0, 5.0, "2026-10-25T00:00:00Z")
        private fun detail(slug: String = "hidden", title: String = "Revealed title") = """{"id":"$questId","slug":"$slug","locations":[],"title":"$title","description":"Story","availability":"active","execution_status":"available","proof_summary":"GPS","reward_preview":{"xp":100,"badge":null},"quota":null,"activities":[]}"""
        private fun owner(status: String): String {
            val facts = if (status == "active") """, "active_participation":{"id":"00000000-0000-4000-8000-000000000002","status":"active","quest_title":"Bound title","quest_revision_id":"00000000-0000-4000-8000-000000000003","quest_execution_status":"available","completed_activity_count":1,"total_activity_count":2}"""
                else """, "completion":{"completion_id":"00000000-0000-4000-8000-000000000004","locations":[],"quest_slug":"hidden","quest_title":"Bound title","share_available":true,"reward_status":"granted"}"""
            return """{"quest_id":"$questId","locations":[],"quest_slug":"hidden","is_hidden":true,"status":"$status"$facts}"""
        }
    }
}
