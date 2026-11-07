package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.FloopelRestClient
import com.floopel.feature.traveler.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class LocationParentTest {
    private fun envelope(data: String) = """{"meta":{"code":"OK"},"error":null,"data":$data}"""
    private val city = CatalogEntry("city", "city", "Magelang", null, CatalogKind.LOCATION, null, locationLevel = "city", parentLocationId = "region")

    @Test fun locationParentsAreOrderedAndSharedParentsAreReadOncePerBatch() = runTest {
        val locations = mutableListOf<String>()
        val rest = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine { request ->
            val data = when (request.url.encodedPath) {
                "/api/apps/v1/locations/region" -> { locations += "region"; """{"id":"region","name":"Jawa Tengah","level":"region","parent_id":"country"}""" }
                "/api/apps/v1/locations/country" -> { locations += "country"; """{"id":"country","name":"Indonesia","level":"country","parent_id":null}""" }
                else -> """{"items":[]}"""
            }
            respond(envelope(data))
        }))
        try {
            val result = AppsTravelerCatalog(rest).withSpotLocations(listOf(city, city.copy(id = "city-2")), "id")
            assertEquals(listOf("region", "country"), locations)
            result.forEach { assertEquals(listOf("Jawa Tengah", "Indonesia"), it.ancestors.map(LocationAncestor::name)) }
        } finally { rest.close() }
    }
    @Test fun anUnrelatedParentResponseIsNeverShownAsTheLocationsContext() = runTest {
        val rest = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine {
            respond(envelope("""{"id":"somewhere-else","name":"Wrong place","level":"region"}"""))
        }))
        try { assertTrue(AppsTravelerCatalog(rest).withSpotLocations(listOf(city), "id").single().ancestors.isEmpty()) }
        finally { rest.close() }
    }
    @Test fun cyclicAndOverlongParentsTerminateWithoutManufacturingACountry() = runTest {
        var reads = 0
        val rest = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine { request ->
            if (request.url.encodedPath.contains("/locations/")) reads++
            respond(envelope("""{"id":"region","name":"Jawa Tengah","level":"region","parent_id":"city"}"""))
        }))
        try {
            val parents = AppsTravelerCatalog(rest).withSpotLocations(listOf(city), "id").single().ancestors
            assertEquals(listOf("region"), parents.map(LocationAncestor::id)); assertEquals(1, reads)
        } finally { rest.close() }
        var deepReads = 0
        val deep = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine { request ->
            if (!request.url.encodedPath.contains("/locations/")) return@MockEngine respond(envelope("""{"items":[]}"""))
            val id = request.url.encodedPath.substringAfterLast('/')
            val n = id.substringAfter("level-").toInt()
            deepReads++; check(deepReads <= 9)
            respond(envelope("""{"id":"$id","name":"Region $n","level":"region","parent_id":"level-${n + 1}"}"""))
        }))
        try {
            val parents = AppsTravelerCatalog(deep).withSpotLocations(listOf(city.copy(parentLocationId = "level-0")), "id").single().ancestors
            assertEquals(8, parents.size); assertEquals(8, deepReads)
            assertTrue(parents.none { it.level == "country" })
        } finally { deep.close() }
    }
    @Test fun unavailableOptionalParentsDoNotInventAPlaceLabel() = runTest {
        val rest = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine { respond("Unavailable", HttpStatusCode.ServiceUnavailable) }))
        try {
            val result = AppsTravelerCatalog(rest).withSpotLocations(listOf(city), "id").single()
            assertEquals("Magelang", result.title); assertTrue(result.ancestors.isEmpty()); assertNull(result.locationName)
        } finally { rest.close() }
    }
    @Test fun locationArticlesUseTheTypeFilterBeforePaginationAndRejectOlderUnfilteredData() = runTest {
        for (type in listOf("article", "news")) {
            val rest = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine { request ->
                if (request.url.encodedPath.contains("/comment-totals/")) return@MockEngine respond(envelope("""{"items":[]}"""))
                assertEquals("/api/apps/v1/locations/city/content", request.url.encodedPath)
                assertEquals("article", request.url.parameters["type"]); assertEquals("2", request.url.parameters["page"])
                respond("""{"meta":{"code":"OK","pagination":{"page":2,"total_pages":2}},"error":null,"data":[{"id":"article","title":"Story","type":"$type","locations":[]}]}""")
            }))
            try {
                val result = AppsTravelerCatalog(rest).browse(CatalogKind.STORY, "id", 2, StoryType.ARTICLE, "city")
                if (type == "article") assertIs<CatalogResult.Ready<CatalogPage>>(result) else assertIs<CatalogResult.InvalidResponse>(result)
            } finally { rest.close() }
        }
    }
}
