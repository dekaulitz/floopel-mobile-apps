package com.floopel.feature.traveler.data.remote

import com.floopel.feature.traveler.core.network.AppPlatform
import com.floopel.feature.traveler.core.network.ApiFailure
import com.floopel.feature.traveler.data.repository.coverage
import com.floopel.feature.traveler.data.repository.coverageLocations
import com.floopel.feature.traveler.data.repository.toDomain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class QuestLocationCoverageTest {
    @Test fun currentQuestAndOwnedRevisionKeepTheirSeparateCoverageAndRejectRetiredWire() = runTest {
        var locations: String? = coverage
        val client = HttpClient(MockEngine { request ->
            val geography = locations?.let { "\"locations\":$it," }.orEmpty()
            val data = if (request.url.encodedPath.endsWith("/me"))
                """{$geography"quest_id":"quest","quest_slug":"coffee","status":"active"}"""
            else """{$geography"id":"quest","slug":"coffee","title":"Coffee","description":"Story","availability":"active","execution_status":"available","proof_summary":"Answer","reward_preview":{"xp":100,"badge":null},"quota":null,"activities":[]}"""
            respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""")
        })
        try {
            val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
            val current = api.quest("coffee", "id")
            assertEquals(coverageLocations, current.locations.map { it.toDomain() })
            locations = coverage.replace("Bali", "Bound revision Bali")
            val owned = api.questOwner("coffee", "en", "fixture-token")
            assertEquals("Bound revision Bali", owned.locations.first().name)
            assertEquals("Bali", current.locations.first().name)
            locations = "[]"
            assertTrue(api.quest("coffee", "id").locations.isEmpty())
            for (malformed in listOf(null, "null", "[{}]")) {
                locations = malformed
                assertEquals(502, assertFailsWith<ApiFailure> { api.quest("coffee", "id") }.status)
            }
        } finally { client.close() }
    }
}
