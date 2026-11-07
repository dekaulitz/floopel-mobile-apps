package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocationDiscoveryTest {
    @Test fun activePublicQuestsAreScopedAndUseServerPagination() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/apps/v1/gamification/quests", request.url.encodedPath)
            assertEquals("location", request.url.parameters["location_id"])
            assertEquals("active", request.url.parameters["status"])
            assertEquals("2", request.url.parameters["page"])
            assertEquals("8", request.url.parameters["per_page"])
            assertEquals("en", request.url.parameters["locale"])
            respond("""{"meta":{"code":"OK","pagination":{"page":2,"total_pages":3}},"error":null,"data":[{"id":"q","slug":"walk","locations":[],"title":"Walk","reward_preview":{"xp":500}}]}""")
        })
        try {
            val api = AppsTravelerCatalog(FloopelRestClient("https://fixture.invalid", client))
            val page = assertIs<CatalogResult.Ready<LocationQuestPage>>(api.locationQuests("location", "en", 2)).value
            assertEquals(LocationQuest("q", "walk", "Walk", 500), page.items.single())
            assertEquals(2, page.page); assertEquals(3, page.totalPages)
        } finally { client.close() }
    }

    @Test fun questBadgeImageUsesTheExistingMediaResolver() = runTest {
        val client = HttpClient(MockEngine {
            respond("""{"meta":{"code":"OK","pagination":{"page":1,"total_pages":1}},"error":null,"data":[{"id":"q","slug":"walk","locations":[],"title":"Walk","reward_preview":{"xp":500,"badge":{"name":"Explorer","image":{"url":"https://fixture.invalid/media/badge.webp"}}}}]}""")
        })
        try {
            val api = AppsTravelerCatalog(FloopelRestClient("https://fixture.invalid", client))
            val page = assertIs<CatalogResult.Ready<LocationQuestPage>>(api.locationQuests("location", "en", 1)).value
            assertEquals(CatalogPhoto("https://fixture.invalid/media/badge.webp", "Explorer"), page.items.single().badgeImage)
        } finally { client.close() }
    }

    @Test fun emptyErrorsAndMalformedResponsesStayDistinct() = runTest {
        var body = """{"meta":{"code":"OK","pagination":{"page":1,"total_pages":0}},"error":null,"data":[]}"""
        var status = HttpStatusCode.OK
        var network = false
        val client = HttpClient(MockEngine {
            if (network) error("Offline fixture")
            respond(body, status)
        })
        try {
            val api = AppsTravelerCatalog(FloopelRestClient("https://fixture.invalid", client))
            assertEquals(CatalogResult.Ready(LocationQuestPage(emptyList(), 1, 0)), api.locationQuests("location", "id", 1))
            assertEquals(CatalogResult.HttpError(422), api.locationQuests("", "id", 1))
            assertEquals(CatalogResult.HttpError(422), api.locationQuests("location", "id", 0))
            for (bad in listOf(
                body.replace("\"page\":1", "\"page\":2"),
                body.replace("\"page\":1", "\"page\":\"1\""),
                body.replace("\"total_pages\":0", "\"total_pages\":-1"),
                body.replace("\"data\":[]", "\"data\":[{}]"),
                body.replace("\"data\":[]", "\"data\":[{\"id\":\"q\",\"slug\":\"q\",\"title\":\"Q\",\"reward_preview\":{\"xp\":-1}}]"))) {
                body = bad
                assertEquals(CatalogResult.InvalidResponse, api.locationQuests("location", "id", 1))
            }
            status = HttpStatusCode.ServiceUnavailable
            assertEquals(CatalogResult.Maintenance(), api.locationQuests("location", "id", 1))
            status = HttpStatusCode.TooManyRequests
            assertEquals(CatalogResult.HttpError(429), api.locationQuests("location", "id", 1))
            network = true
            assertEquals(CatalogResult.NetworkError, api.locationQuests("location", "id", 1))
        } finally { client.close() }
    }
}
