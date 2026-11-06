package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CatalogPaginationTest {
    @Test fun homeActiveQuestsUseTheExistingGlobalEndpointWithoutLocationScope() = runTest {
        val requests = mutableListOf<Url>()
        val client = HttpClient(MockEngine { request ->
            requests += request.url
            respond("""{"meta":{"code":"OK","pagination":{"page":1,"total_pages":1}},"error":null,"data":[{"id":"q","slug":"q","locations":[],"title":"Global quest","reward_preview":{"xp":100,"badge":null}}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val catalog = AppsTravelerCatalog(FloopelRestClient("https://fixture.invalid", client))
            assertEquals("q", assertIs<CatalogResult.Ready<LocationQuestPage>>(catalog.activeQuests("en", 1)).value.items.single().id)
            assertEquals("/api/apps/v1/gamification/quests", requests.single().encodedPath)
            assertEquals("active", requests.single().parameters["status"])
            assertNull(requests.single().parameters["location_id"])
            assertEquals(CatalogResult.HttpError(422), catalog.activeQuests("en", 0))
            assertEquals(1, requests.size)
        } finally { client.close() }
    }

    @Test fun rankedGlobalFeedsUseExactLimitsWithoutTripOrRegionFilters() = runTest {
        val requests = mutableListOf<Url>()
        val client = HttpClient(MockEngine { request ->
            requests += request.url
            val data = if (request.url.encodedPath.contains("comment-totals")) """{"items":[]}""" else
                """[{"id":"z","slug":"z-place","name":"Z first"},{"id":"a","slug":"a-place","name":"A second"}]"""
            respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val catalog = AppsTravelerCatalog(FloopelRestClient("https://fixture.invalid", client))
            assertEquals(listOf("z", "a"), assertIs<CatalogResult.Ready<List<CatalogEntry>>>(catalog.spots("en", perPage = 10)).value.map { it.id })
            assertEquals(listOf("z", "a"), assertIs<CatalogResult.Ready<List<CatalogEntry>>>(catalog.topLocations("en", 5)).value.map { it.id })
            val feeds = requests.filter { it.encodedPath.endsWith("/spots") || it.encodedPath.endsWith("/locations") }
            assertEquals(listOf("10", "5"), feeds.map { it.parameters["per_page"] })
            assertTrue(feeds.all { url -> listOf("location_id", "parent_id", "level", "lat", "lng", "radius_m").all { url.parameters[it] == null } })
            assertFailsWith<IllegalArgumentException> { catalog.topLocations("en", 0) }
        } finally { client.close() }
    }

    @Test fun browseUsesServerPagesAndOnlySupportedTargetFilters() = runTest {
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            paths += request.url.toString()
            val kind = request.url.encodedPath.substringAfterLast('/')
            val titleField = if (kind in setOf("locations", "spots")) "name" else "title"
            val data = """[{"id":"entry","slug":"entry","locations":[],"$titleField":"Entry"}]"""
            respond("""{"meta":{"code":"OK","pagination":{"page":2,"total_pages":3}},"error":null,"data":$data}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val catalog = AppsTravelerCatalog(FloopelRestClient("https://fixture.invalid", client))
            CatalogKind.entries.forEach { kind ->
                val page = assertIs<CatalogResult.Ready<CatalogPage>>(catalog.browse(kind, "en", 2, StoryType.ARTICLE, "city", "parent")).value
                assertEquals(2, page.page); assertEquals(3, page.totalPages)
                assertEquals(kind, page.items.single().kind)
            }
            assertTrue(paths.any { it.contains("/locations?") && it.contains("parent_id=parent") && !it.contains("location_id") })
            assertTrue(paths.any { it.contains("/spots?") && it.contains("location_id=city") && !it.contains("type=") })
            assertTrue(paths.any { it.contains("/collections?") && it.contains("location_id=city") })
            assertTrue(paths.any { it.contains("/locations/city/content?") && !it.contains("type=") })
            assertTrue(paths.any { it.contains("/itineraries?") && it.contains("source=curated") && it.contains("location_id=city") })
            val pageRequests = paths.filterNot { it.contains("/comment-totals/") }
            assertEquals(CatalogKind.entries.size, pageRequests.size)
            assertTrue(pageRequests.all { it.contains("page=2") && it.contains(if (it.contains("/spots?")) "per_page=10" else "per_page=20") })
            assertEquals(CatalogResult.HttpError(422), catalog.browse(CatalogKind.SPOT, "en", 0))
        } finally { client.close() }
    }
    @Test fun malformedPagesAreNotAnEmptyArchiveAndUnavailableResponsesRemainRecoverable() = runTest {
        var body = """{"meta":{"code":"OK","pagination":{"page":1,"total_pages":0}},"error":null,"data":[]}"""
        var status = HttpStatusCode.OK
        var network = false
        val client = HttpClient(MockEngine {
            if (network) error("Fixture response loss")
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val catalog = AppsTravelerCatalog(FloopelRestClient("https://fixture.invalid", client))
            assertEquals(CatalogResult.Ready(CatalogPage(emptyList(), 1, 0)), catalog.browse(CatalogKind.SPOT, "en", 1))
            body = body.replace("\"page\":1", "\"page\":\"1\"")
            assertEquals(CatalogResult.InvalidResponse, catalog.browse(CatalogKind.SPOT, "en", 1))
            body = """{"meta":{"code":"OK","pagination":{"page":1,"total_pages":2}},"error":null,"data":[{}]}"""
            assertEquals(CatalogResult.InvalidResponse, catalog.browse(CatalogKind.SPOT, "en", 1))
            body = """{"meta":{"code":"OK","pagination":{"page":2,"total_pages":2}},"error":null,"data":[]}"""
            assertEquals(CatalogResult.InvalidResponse, catalog.browse(CatalogKind.SPOT, "en", 1))
            status = HttpStatusCode.ServiceUnavailable
            assertEquals(CatalogResult.Maintenance(), catalog.browse(CatalogKind.STORY, "en", 1))
            status = HttpStatusCode.TooManyRequests
            assertEquals(CatalogResult.HttpError(429), catalog.browse(CatalogKind.COLLECTION, "en", 1))
            network = true
            assertEquals(CatalogResult.NetworkError, catalog.browse(CatalogKind.LOCATION, "en", 1))
        } finally { client.close() }
    }

    @Test fun catalogRetainsServerCooldownForRateLimitAndMaintenance() = runTest {
        var status = HttpStatusCode.TooManyRequests
        val client = HttpClient(MockEngine {
            respond("temporary unavailable", status, headersOf(HttpHeaders.RetryAfter, "20"))
        })
        try {
            val catalog = AppsTravelerCatalog(FloopelRestClient("https://fixture.invalid", client))
            assertEquals(CatalogResult.HttpError(429, 20), catalog.browse(CatalogKind.SPOT, "en", 1))
            status = HttpStatusCode.ServiceUnavailable
            assertEquals(CatalogResult.Maintenance(20), catalog.browse(CatalogKind.SPOT, "en", 1))
        } finally { client.close() }
    }

}
