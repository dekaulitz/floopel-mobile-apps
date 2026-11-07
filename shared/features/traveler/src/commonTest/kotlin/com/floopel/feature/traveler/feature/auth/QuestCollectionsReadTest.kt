package com.floopel.feature.traveler.feature.auth

import com.floopel.feature.traveler.core.network.AppPlatform
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.domain.model.QuestDiscoveryFilter
import com.floopel.feature.traveler.domain.model.QuestCatalogPage
import com.floopel.feature.traveler.domain.model.QuestReader
import com.floopel.feature.traveler.domain.model.QuestRunning
import com.floopel.feature.traveler.domain.model.CatalogResult
import com.floopel.feature.traveler.data.repository.NativeTravelerIdentity
import com.floopel.feature.traveler.domain.repository.RefreshCredentialStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Account catalog facts and owned running snapshots use distinct authenticated reads. */
class QuestCollectionsReadTest {
    @Test fun nativeCatalogProjectionPreservesAccountFactsAndServerCriteria() = runTest {
        val fixture = Fixture()
        try {
            fixture.session.login("fixture@example.invalid", "password")
            val identity = NativeTravelerIdentity(fixture.session, fixture.api)
            val filter = QuestDiscoveryFilter(0, 500, "00000000-0000-4000-8000-000000000301", "Magelang", "group")
            val page = assertIs<CatalogResult.Ready<QuestCatalogPage>>(identity.questCatalog(filter, 1)).value
            val row = page.items.single(); assertEquals(100L, row.card.xp); assertEquals("group", row.card.mode); assertEquals(true, row.isTaken)
            val query = fixture.requests.last().parameters
            assertEquals("0", query["min_xp"]); assertEquals("500", query["max_xp"]); assertEquals(filter.locationId, query["location_id"])
            assertEquals("group", query["participation_mode"]); assertEquals("/api/apps/v1/gamification/me/quests", fixture.requests.last().encodedPath)
        } finally { fixture.api.close() }
    }
    @Test fun nativeTakenProjectionUsesOwnedParticipationAndContinuationOnlyReads() = runTest {
        val fixture = Fixture()
        try {
            fixture.session.login("fixture@example.invalid", "password")
            val identity = NativeTravelerIdentity(fixture.session, fixture.api)
            assertTrue(assertIs<CatalogResult.Ready<List<QuestRunning>>>(identity.takenQuests("personal")).value.isEmpty())
            val rows = assertIs<CatalogResult.Ready<List<QuestRunning>>>(identity.takenQuests("group")).value
            assertEquals(fixture.id, rows.single().id); assertEquals("group", rows.single().mode)
            identity.continueQuest(rows.single().id)
            assertEquals("/api/apps/v1/gamification/participations/${fixture.id}", fixture.requests.last().encodedPath)
            assertTrue(fixture.requests.none { it.encodedPath.endsWith("/start") })
        } finally { fixture.api.close() }
    }
    @Test fun nativeDetailBindsOwnerProgressToTheSameStableQuest() = runTest {
        val fixture = Fixture()
        try {
            fixture.session.login("fixture@example.invalid", "password")
            val identity = NativeTravelerIdentity(fixture.session, fixture.api)
            val reader = assertIs<CatalogResult.Ready<QuestReader>>(identity.quest("quest")).value
            assertEquals(reader.card.id, reader.owner?.questId); assertEquals("active", reader.owner?.status)
            assertEquals(fixture.id, reader.owner?.participation?.id); assertEquals("Public guidance", reader.instructions)
            assertTrue(fixture.requests.none { it.encodedPath.contains("/activities/") })
        } finally { fixture.api.close() }
    }
    @Test fun catalogModeAndTakenStatusUseTheAccountEndpoint() = runTest {
        val fixture = Fixture()
        try {
            fixture.session.login("fixture@example.invalid", "password")
            fixture.session.loadQuests(filter = QuestDiscoveryFilter(participationMode = "group"))
            assertEquals("/api/apps/v1/gamification/me/quests", fixture.requests.last().encodedPath)
            assertEquals("group", fixture.requests.last().parameters["participation_mode"])
            assertEquals(true, fixture.session.view.value.questPage?.items?.single()?.isTaken)
            assertEquals("group", fixture.session.view.value.questPage?.items?.single()?.participationMode)
        } finally { fixture.api.close() }
    }

    @Test fun ownedContinuationAndRetryRetainTakenOriginAndRejectForeignIDs() = runTest {
        val fixture = Fixture()
        try {
            fixture.session.login("fixture@example.invalid", "password")
            fixture.session.loadTakenQuests("group")
            assertEquals(QuestCollection.TAKEN, fixture.session.view.value.questCollection)
            val reads = fixture.requests.size
            fixture.session.continueTakenQuest("00000000-0000-4000-8000-000000000999")
            assertEquals(reads, fixture.requests.size)
            fixture.session.continueTakenQuest(fixture.id)
            assertTrue(fixture.session.view.value.returnToTakenQuests)
            assertEquals(TravelerScreen.QUEST_PLAY, fixture.session.view.value.screen)
            fixture.session.retryRead()
            assertTrue(fixture.session.view.value.returnToTakenQuests)
            assertEquals("group", fixture.session.view.value.participation?.participationMode)
            fixture.session.loadTakenQuests()
            fixture.failProgress = true
            fixture.session.loadTakenQuests()
            assertEquals(500, fixture.session.view.value.failure?.status)
            fixture.failProgress = false
            fixture.session.retryRead()
            assertNull(fixture.session.view.value.failure)
            assertEquals("group", fixture.session.view.value.takenQuestMode)
            assertEquals(QuestCollection.TAKEN, fixture.session.view.value.questCollection)
            fixture.session.logout()
            assertNull(fixture.session.view.value.progress)
            assertFalse(fixture.session.view.value.signedIn)
        } finally { fixture.api.close() }
    }

    private class Fixture {
        val id = "00000000-0000-4000-8000-000000000101"
        val requests = mutableListOf<Url>()
        var failProgress = false
        private fun participation() = """{"id":"$id","status":"active","quest_title":"Owned snapshot","quest_revision_id":"00000000-0000-4000-8000-000000000201","quest_execution_status":"available","completed_activity_count":0,"total_activity_count":1,"participation_mode":"group"}"""
        val api = NativeApi(HttpClient(MockEngine { request ->
            requests += request.url
            val path = request.url.encodedPath
            if (path.endsWith("/logout")) return@MockEngine respond("", HttpStatusCode.NoContent)
            if (path.endsWith("/progress") && failProgress) return@MockEngine respond("{}", HttpStatusCode.InternalServerError)
            val data = when {
                path.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"fixture","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"fixture-refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
                path.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[],"active_participations":[${participation()}]}"""
                path.endsWith("/me/quests") -> """[{"id":"quest","slug":"quest","locations":[],"title":"Group","availability":"active","execution_status":"available","activity_count":1,"reward_preview":{"xp":100,"badge":null},"quota":null,"participation_mode":"group","group_size_limit":3,"is_taken":true}]"""
                path.endsWith("/quests/quest/me") -> """{"quest_id":"quest","quest_slug":"quest","status":"active","active_participation":${participation()},"locations":[]}"""
                path.endsWith("/quests/quest") -> """{"id":"quest","slug":"quest","title":"Group","description":"Public description","proof_summary":"Public guidance","availability":"active","execution_status":"available","reward_preview":{"xp":100,"badge":null},"quota":null,"participation_mode":"group","group_size_limit":3,"activities":[],"travelers":[],"locations":[]}"""
                path.endsWith("/participations/$id") -> participation()
                else -> error("Unexpected fixture path: $path")
            }
            if (!path.endsWith("/login") && !path.endsWith("/quests/quest")) assertNotNull(request.headers[HttpHeaders.Authorization])
            respond("""{"meta":{"code":"OK","pagination":{"page":1,"total_pages":1}},"error":null,"data":$data}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }), "https://fixture.invalid", AppPlatform.ANDROID)
        val session = TravelerSession(api, object : RefreshCredentialStore {
            override fun read(): String? = null
            override fun write(credential: String) = Unit
            override fun clear() = Unit
        }, "en")
    }
}
