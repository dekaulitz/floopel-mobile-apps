package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.FloopelRestClient
import com.floopel.feature.traveler.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CatalogLocationCoverageTest {
    @Test fun collectionKeepsOwnCoverageAndEligibleItineraryMemberWithoutInventingItsGeography() = runTest {
        val payload = """{"id":"collection","slug":"collection","title":"Collection","locations":$coverage,"members":[{"target_type":"itinerary","target":{"id":"plan","slug":"plan","name":"Published plan"}}]}"""
        val client = HttpClient(MockEngine { request ->
            val data = if (request.url.encodedPath.contains("comment-totals")) """{"items":[]}""" else payload
            respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""")
        })
        try {
            val catalog = AppsTravelerCatalog(FloopelRestClient("https://fixture.invalid", client))
            val entry = CatalogEntry("collection", "collection", "Collection", null, CatalogKind.COLLECTION, null)
            val detail = assertIs<CatalogResult.Ready<CatalogEntry>>(catalog.detail(entry, "id")).value
            assertEquals(coverageLocations, detail.locations)
            assertEquals(CatalogKind.ITINERARY, detail.related.single().kind)
            assertEquals("Published plan", detail.related.single().title)
            assertTrue(detail.related.single().locations.isEmpty())
        } finally { client.close() }
    }

    @Test fun catalogListsAndReadersKeepAllCoverageAndNeverHideMalformedEntities() = runTest {
        for (kind in listOf(CatalogKind.COLLECTION, CatalogKind.STORY, CatalogKind.ITINERARY)) {
            var payload = """{"id":"item","slug":"item","title":"Entity","locations":$coverage}"""
            var detail = false
            val client = HttpClient(MockEngine { request ->
                val data = if (request.url.encodedPath.contains("comment-totals")) """{"items":[]}"""
                    else if (detail) payload else "[$payload]"
                respond("""{"meta":{"code":"OK","pagination":{"page":1,"total_pages":1}},"error":null,"data":$data}""")
            })
            try {
                val catalog = AppsTravelerCatalog(FloopelRestClient("https://fixture.invalid", client))
                val entry = assertIs<CatalogResult.Ready<CatalogPage>>(catalog.browse(kind, "en", 1)).value.items.single()
                assertEquals(coverageLocations, entry.locations)
                detail = true
                assertEquals(coverageLocations, assertIs<CatalogResult.Ready<CatalogEntry>>(catalog.detail(entry, "id")).value.locations)
                detail = false
                payload = payload.replace("\"locations\":$coverage", "\"locations\":null")
                assertEquals(CatalogResult.InvalidResponse, catalog.browse(kind, "en", 1))
                payload = payload.replace(",\"locations\":null", "")
                assertEquals(CatalogResult.InvalidResponse, catalog.browse(kind, "en", 1))
                payload = """{"id":"item","slug":"item","title":"Entity","locations":[]}"""
                assertTrue(assertIs<CatalogResult.Ready<CatalogPage>>(catalog.browse(kind, "id", 1)).value.items.single().locations.isEmpty())
            } finally { client.close() }
        }
    }
}
