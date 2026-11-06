package com.floopel.feature.traveler.data.remote

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.repository.AppsTravelerCatalog
import com.floopel.feature.traveler.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class DiscoveryCriteriaTest {
    @Test fun untakenEntryUsesOwnedQueryWithoutChangingTheGeneralCatalog() = runTest {
        val requests = mutableListOf<Url>()
        var taken = "false"
        val client = HttpClient(MockEngine { request ->
            requests += request.url
            val data = """[{"id":"quest","slug":"coffee","title":"Coffee","is_taken":$taken,"availability":"active","execution_status":"available","activity_count":4,"reward_preview":{"xp":100,"badge":null},"quota":null,"locations":[]}]"""
            respond("""{"meta":{"code":"OK","pagination":{"page":1,"total_pages":1}},"error":null,"data":$data}""")
        })
        try {
            val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
            val filter = QuestDiscoveryFilter(locationId = "00000000-0000-4000-8000-000000000001", locationName = "Bali", excludeTaken = true, availability = "active")
            assertFalse(api.quests(1, "id", filter, "fixture-token").items.single().isTaken!!)
            assertEquals("/api/apps/v1/gamification/me/quests", requests.last().encodedPath)
            assertEquals("true", requests.last().parameters["exclude_taken"])
            assertEquals("active", requests.last().parameters["status"])
            assertEquals(filter.locationId, requests.last().parameters["location_id"])
            for (invalid in listOf("true", "null", "\"false\"")) {
                taken = invalid
                assertEquals(502, assertFailsWith<ApiFailure> { api.quests(1, "id", filter, "fixture-token") }.status)
            }
            api.quests(1, "id", filter.copy(excludeTaken = false), "fixture-token")
            assertNull(requests.last().parameters["exclude_taken"])
            val count = requests.size
            assertFailsWith<IllegalArgumentException> { api.quests(1, "id", filter) }
            assertEquals(count, requests.size)
        } finally { client.close() }
    }
    @Test
    fun questCriteriaAreSentToTheServerBeforePagination() = runTest {
        val requests = mutableListOf<Url>()
        val client =
            HttpClient(
                MockEngine { request ->
                    requests += request.url
                    respond(
                        """{"meta":{"code":"QUESTS","pagination":{"page":2,"total_pages":3}},"error":null,"data":[]}"""
                    )
                }
            )
        try {
            val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
            val region = "00000000-0000-4000-8000-000000000001"
            val page = api.quests(2, "id", QuestDiscoveryFilter(0, 500, region, "Bali"))
            assertEquals(2, page.pagination.page)
            val query = requests.single().parameters
            assertEquals("0", query["min_xp"])
            assertEquals("500", query["max_xp"])
            assertEquals(region, query["location_id"])
            assertEquals("20", query["per_page"])
            assertNull(query["location_name"])
            api.quests(1, "en")
            assertNull(requests.last().parameters["min_xp"])
            assertNull(requests.last().parameters["location_id"])
        } finally {
            client.close()
        }
    }

    @Test
    fun invalidQuestBoundsAndLocationIdsNeverReachTransport() = runTest {
        var reads = 0
        val client =
            HttpClient(
                MockEngine {
                    reads++
                    error("Invalid criteria reached transport")
                }
            )
        try {
            val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
            for (filter in
                listOf(
                    QuestDiscoveryFilter(-1),
                    QuestDiscoveryFilter(maxXP = -1),
                    QuestDiscoveryFilter(500, 100),
                    QuestDiscoveryFilter(locationId = "../private"),
                )) {
                assertFailsWith<IllegalArgumentException> { api.quests(1, "en", filter) }
            }
            assertEquals(0, reads)
        } finally {
            client.close()
        }
    }

    @Test
    fun itineraryPicksCombineTrimmedSearchRegionAndAuthoritativePage() = runTest {
        val requests = mutableListOf<Url>()
        val client =
            HttpClient(
                MockEngine { request ->
                    requests += request.url
                    respond(
                        """{"meta":{"code":"ITINERARIES","pagination":{"page":2,"total_pages":4}},"error":null,"data":[]}"""
                    )
                }
            )
        try {
            val catalog = AppsTravelerCatalog(FloopelRestClient("https://fixture.invalid", client))
            val result =
                assertIs<CatalogResult.Ready<CatalogPage>>(
                        catalog.curatedItineraries("  museum & park  ", "region", "id", 2)
                    )
                    .value
            assertEquals(4, result.totalPages)
            assertEquals("curated", requests.single().parameters["source"])
            assertEquals("museum & park", requests.single().parameters["q"])
            assertEquals("region", requests.single().parameters["location_id"])
            assertEquals(
                CatalogResult.HttpError(422),
                catalog.curatedItineraries("x".repeat(161), null, "id", 1),
            )
            assertEquals(1, requests.size)
        } finally {
            client.close()
        }
    }
}
