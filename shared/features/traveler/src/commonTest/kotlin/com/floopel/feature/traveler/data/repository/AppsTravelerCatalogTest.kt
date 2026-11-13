package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.domain.model.*

import com.floopel.feature.traveler.domain.model.CatalogEntry
import com.floopel.feature.traveler.domain.model.CatalogKind
import com.floopel.feature.traveler.domain.model.CatalogResult
import com.floopel.feature.traveler.domain.model.SearchSort
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppsTravelerCatalogTest {
    @Test fun curatedCopyCapabilityRequiresACanonicalBoolean() = runBlocking {
        for ((wire, expected) in listOf("true" to true, "false" to false, "null" to null, "\"true\"" to null)) {
            val client = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine {
                respond("""{"meta":{"code":"OK","pagination":{"page":1,"total_pages":1}},"error":null,"data":[{"id":"curated","title":"Curated","description":"Route","locations":[],"allow_clone":$wire}]}""")
            }))
            try {
                val result = assertIs<CatalogResult.Ready<CatalogPage>>(AppsTravelerCatalog(client).curatedItineraries("", null, "id", 1))
                assertEquals(expected, result.value.items.single().allowClone)
            } finally { client.close() }
        }
    }

    @Test fun spotDetailUsesItsImmediateLocationZoneEvenWithAnExistingPlaceLabel() = runBlocking {
        for (zone in listOf("\"Asia/Makassar\"", "null")) {
            val client = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine { request ->
                val data = when (request.url.encodedPath) {
                    "/api/apps/v1/locations/location" -> """{"id":"location","slug":"city-slug","name":"City","level":"city","parent_id":"country"}"""
                    "/api/apps/v1/locations/city-slug" -> """{"id":"location","name":"City","level":"city","parent_id":"country","timezone":$zone}"""
                    "/api/apps/v1/locations/country" -> """{"id":"country","name":"Country","level":"country","timezone":"Asia/Jakarta"}"""
                    else -> """{"items":[]}"""
                }
                respond(envelope(data))
            }))
            try {
                val spot = CatalogEntry("spot", "spot", "Spot", null, CatalogKind.SPOT, null,
                    locationId = "location", locationName = "City", detailLoaded = true)
                val enriched = AppsTravelerCatalog(client).withSpotLocations(listOf(spot), "id").single()
                assertEquals(if (zone == "null") null else "Asia/Makassar", enriched.timezone)
                assertEquals("City", enriched.locationName)
            } finally { client.close() }
        }
    }

    @Test fun attachedArticlesRejectAnUnfilteredOlderResponse() = runBlocking {
        val client = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine { request ->
            if (request.url.encodedPath.contains("/comment-totals/")) return@MockEngine respond(envelope("""{"comment_count":0}"""))
            respond("""{"meta":{"code":"OK","pagination":{"page":1,"total_pages":1}},"error":null,"data":[{"id":"news","slug":"news","title":"News","type":"news","locations":[]}]}""")
        }))
        try { assertIs<CatalogResult.InvalidResponse>(AppsTravelerCatalog(client).spotArticles("spot-1", "id", 1)) }
        finally { client.close() }
        Unit
    }

    @Test fun attachedArticlesSelectTheirTypeBeforePaging() = runBlocking {
        val client = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine { request ->
            if (request.url.encodedPath.contains("/comment-totals/")) return@MockEngine respond(envelope("""{"comment_count":0}"""))
            assertEquals("/api/apps/v1/spots/spot-1/content", request.url.encodedPath)
            assertEquals("article", request.url.parameters["type"])
            assertEquals("2", request.url.parameters["page"])
            respond("""{"meta":{"code":"OK","pagination":{"page":2,"total_pages":2}},"error":null,"data":[{"id":"article","slug":"spot-history","title":"Spot history","type":"article","locations":[]}]}""")
        }))
        try {
            val result = assertIs<CatalogResult.Ready<CatalogPage>>(AppsTravelerCatalog(client).spotArticles("spot-1", "id", 2))
            assertEquals("spot-history", result.value.items.single().slug)
        } finally { client.close() }
    }

    @Test fun spotQuestScopeIsEncodedBeforePagination() = runBlocking {
        val client = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine { request ->
            assertEquals("/api/apps/v1/gamification/quests", request.url.encodedPath)
            assertEquals("spot & one", request.url.parameters["spot_id"])
            assertNull(request.url.parameters["location_id"])
            assertEquals("2", request.url.parameters["page"])
            respond("""{"meta":{"code":"OK","pagination":{"page":2,"total_pages":2}},"error":null,"data":[{"id":"quest","slug":"spot-quest","title":"Spot checkpoint","reward_preview":{"xp":50},"locations":[]}]}""")
        }))
        try {
            val result = assertIs<CatalogResult.Ready<LocationQuestPage>>(AppsTravelerCatalog(client).spotQuests("spot & one", "id", 2))
            assertEquals("spot-quest", result.value.items.single().slug)
            assertEquals(2, result.value.page)
        } finally { client.close() }
    }

    @Test fun locationDetailPreservesNullableSourceTimezoneWithoutInferringItFromSpotOrDevice() = runBlocking {
        for (zone in listOf("\"Asia/Makassar\"", "null")) {
            val client = FloopelRestClient("https://api.example.test", HttpClient(MockEngine { request ->
                if (request.url.encodedPath.contains("/comment-totals/")) return@MockEngine respond(envelope("""{"comment_count":0}"""))
                assertEquals("/api/apps/v1/locations/location", request.url.encodedPath)
                respond(envelope("""{"id":"location","slug":"location","name":"Location","level":"city","timezone":$zone}"""))
            }))
            try {
                val entry = CatalogEntry("location", "location", "Location", null, CatalogKind.LOCATION, null)
                val detail = assertIs<CatalogResult.Ready<CatalogEntry>>(AppsTravelerCatalog(client).detail(entry, "id")).value
                assertEquals(if (zone == "null") null else "Asia/Makassar", detail.timezone)
            } finally { client.close() }
        }
    }

    @Test fun exploreNearbyUsesAllowedRadiusAndAuthoritativePagination() = runBlocking {
        val requests = mutableListOf<Pair<String?,String?>>()
        val client = FloopelRestClient("https://api.example.test", HttpClient(MockEngine { request ->
            if (request.url.encodedPath.contains("/comment-totals/")) respond(envelope("""{"items":[]}""")) else {
                assertEquals("/api/apps/v1/spots",request.url.encodedPath)
                assertEquals("-6.3",request.url.parameters["lat"]); assertEquals("106.5",request.url.parameters["lng"])
                assertNull(request.url.parameters["location_id"])
                requests += request.url.parameters["radius_m"] to request.url.parameters["page"]
                respond("""{"meta":{"code":"SPOTS","pagination":{"page":2,"total_pages":4}},"error":null,"data":[{"id":"spot","slug":"spot","name":"Spot","geo":{"lat":-6.3,"lng":106.5},"reference_rating":{"origin":"ai","suppressed":false,"score":4.8}}]}""")
            }
        }))
        try {
            val api = AppsTravelerCatalog(client)
            for (radius in TravelerExploreRadius.OPTIONS_METERS) {
                val page = assertIs<CatalogResult.Ready<CatalogPage>>(api.nearbySpots(GeoPoint(-6.3,106.5),"id",2,radius)).value
                assertEquals(2,page.page); assertEquals(4,page.totalPages); assertEquals(4.8,page.items.single().aiReference?.score)
            }
            assertEquals(listOf("5000","15000","20000"),requests.map { it.first })
            assertEquals(CatalogResult.HttpError(422),api.nearbySpots(GeoPoint(-6.3,106.5),"id",0))
            assertEquals(CatalogResult.HttpError(422),api.nearbySpots(GeoPoint(Double.NaN,106.5),"id",1))
            assertEquals(CatalogResult.HttpError(422),api.nearbySpots(GeoPoint(-6.3,106.5),"id",1,10000))
        } finally { client.close() }
    }
    @Test fun nearestCatalogContextDistinguishesEmptyFailureAndMalformedData() = runBlocking {
        for (body in listOf("null", """{"id":"nearest","slug":"nearest","name":"Banten","level":"region","parent_id":"country","geo":{"lat":-6.0,"lng":106.0}}""", "{}")) {
            val client = FloopelRestClient("https://api.example.test", HttpClient(MockEngine { request ->
                assertEquals("/api/apps/v1/locations/nearest", request.url.encodedPath)
                assertEquals("-6.0", request.url.parameters["lat"])
                assertEquals("106.0", request.url.parameters["lng"])
                respond(envelope(body))
            }))
            try {
                val api = AppsTravelerCatalog(client)
                val result = api.nearestLocation(GeoPoint(-6.0, 106.0))
                when (body) {
                    "null" -> assertNull(assertIs<CatalogResult.Ready<CatalogEntry?>>(result).value)
                    "{}" -> assertEquals(CatalogResult.InvalidResponse, result)
                    else -> assertEquals("country", assertIs<CatalogResult.Ready<CatalogEntry?>>(result).value?.parentLocationId)
                }
                assertEquals(CatalogResult.HttpError(422), api.nearestLocation(GeoPoint(Double.NaN, 106.0)))
            } finally { client.close() }
        }
        val client = FloopelRestClient("https://api.example.test", HttpClient(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) }))
        try { assertIs<CatalogResult.Maintenance>(AppsTravelerCatalog(client).nearestLocation(GeoPoint(-6.0, 106.0))) }
        finally { client.close() }
        Unit
    }

    @Test fun regionSearchUsesServerKindFacetAndPreservesBreadcrumb() = runBlocking {
        val client = FloopelRestClient("https://api.example.test", HttpClient(MockEngine { request ->
            if (request.url.encodedPath.contains("/comment-totals/")) respond(envelope("""{"items":[]}""")) else {
                assertEquals("location", request.url.parameters["kinds"])
                respond(envelope("""{"groups":[{"items":[{"id":"bandung","name":"Bandung","kind":"location","type":"regency","breadcrumb":[{"name":"Indonesia"},{"name":"Jawa Barat"}]}]}]}"""))
            }
        }))
        try {
            val entry = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(AppsTravelerCatalog(client).searchLocations("Bandung", "id")).value.single()
            assertEquals("regency", entry.locationLevel)
            assertEquals("Indonesia · Jawa Barat", entry.locationName)
        } finally { client.close() }
    }
    @Test fun articleAttachmentUsesPublicParentPhotoAndOptionalFailureKeepsArticleReadable() = runBlocking {
        for (available in listOf(true, false)) {
            val client = FloopelRestClient("http://10.0.2.2:8080", HttpClient(MockEngine { request ->
                when (request.url.encodedPath) {
                    "/api/apps/v1/content/article-one" -> respond(envelope("""{"id":"article","slug":"article-one","locations":[],"title":"Article","attachments":[{"location_id":"city","label":"Bandung"}]}"""))
                    "/api/apps/v1/locations/city" -> {
                        assertEquals("en", request.url.parameters["locale"])
                        if (available) respond(envelope("""{"id":"city","slug":"bandung","name":"Bandung","level":"city","cover_image":{"variants":{"medium":{"url":"http://localhost:8080/media/bandung.webp"}}}}"""))
                        else respond("", HttpStatusCode.ServiceUnavailable)
                    }
                    "/api/apps/v1/public/comment-totals" -> respond(envelope("[]"))
                    else -> error("Unexpected route: ${request.url.encodedPath}")
                }
            }))
            try {
                val result = assertIs<CatalogResult.Ready<CatalogEntry>>(AppsTravelerCatalog(client).detail(
                    CatalogEntry("article", "article-one", "Article", null, CatalogKind.STORY, null), "en")).value
                assertEquals("Article", result.title)
                assertEquals("Bandung", result.related.single().title)
                assertEquals(if (available) "http://10.0.2.2:8080/media/bandung.webp" else null, result.related.single().coverUrl)
            } finally { client.close() }
        }
    }

    private fun envelope(data: String) =
        """{"meta":{"request_id":"11111111-1111-1111-1111-111111111111","timestamp":1,"code":"OK"},"error":null,"data":""" +
            data + "}"

    private val spot = """{"id":"spot-1","slug":"spot-one","name":"Spot One","category_label":"Alam","why_snippet":"A lovely place","cover_hero_image":{"variants":{"medium":{"url":"https://cdn.example.test/spot.webp"}}}}"""
    private val location = """{"id":"loc-1","slug":"region-one","name":"Region One","level":"region"}"""
    private val collection = """{"id":"col-1","slug":"collection-one","locations":[],"title":"Collection One","cover_image":null}"""
    private val story = """{"id":"story-1","slug":"story-one","locations":[],"title":"Story One","type":"article","snippet":"About cultures"}"""
    private val itinerary = """{"id":"trip-1","locations":[],"title":"Trip One","source":"curated","visibility":"private","is_owner":false}"""

    @Test fun spotLocationNamesResolveOncePerLocationAndPreserveLocale() = runBlocking {
        var locationReads = 0
        val client = FloopelRestClient("https://api.example.test", HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/apps/v1/spots" -> respond(envelope(
                    """[{"id":"a","name":"Spot A","location_id":"city"},{"id":"b","name":"Spot B","location_id":"city"}]""",
                ))
                "/api/apps/v1/locations/city" -> {
                    locationReads++
                    assertEquals("en", request.url.parameters["locale"])
                    respond(envelope("""{"id":"city","name":"Bandung","level":"city","parent_id":"province"}"""))
                }
                "/api/apps/v1/locations/province" -> {
                    locationReads++
                    respond(envelope("""{"id":"province","name":"Jawa Barat","level":"region","parent_id":"country"}"""))
                }
                "/api/apps/v1/locations/country" -> {
                    locationReads++
                    respond(envelope("""{"id":"country","name":"Indonesia","level":"country","parent_id":null}"""))
                }
                else -> error("Unexpected route")
            }
        }))
        try {
            val result = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(AppsTravelerCatalog(client).spots("en")).value
            assertEquals(listOf("Bandung · Jawa Barat · Indonesia", "Bandung · Jawa Barat · Indonesia"), result.map { it.locationName })
            assertEquals(3, locationReads)
        } finally { client.close() }
    }

    @Test fun spotCollectionCoverUsesExistingParentReadAndOptionalFailureKeepsSpot() = runBlocking {
        for (available in listOf(true, false)) {
            val routes = mutableListOf<String>()
            val client = FloopelRestClient("http://10.0.2.2:8080", HttpClient(MockEngine { request ->
                routes += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/api/apps/v1/spots/spot-one" -> respond(envelope(
                        """{"id":"spot-1","slug":"spot-one","name":"Spot One","collections":[{"collection":$collection}]}""",
                    ))
                    "/api/apps/v1/collections/collection-one" -> {
                        assertEquals("en", request.url.parameters["locale"])
                        if (available) respond(envelope(
                            """{"id":"col-1","slug":"collection-one","locations":[],"title":"Collection One","cover_image":{"variants":{"small":{"url":"http://localhost:8080/media/collection.webp"}}}}""",
                        )) else respond("", HttpStatusCode.ServiceUnavailable)
                    }
                    else -> error("Unexpected route")
                }
            }))
            try {
                val entry = CatalogEntry("spot-1", "spot-one", "Spot One", null, CatalogKind.SPOT, null)
                val detail = assertIs<CatalogResult.Ready<CatalogEntry>>(AppsTravelerCatalog(client).detail(entry, "en")).value
                assertEquals("Spot One", detail.title)
                assertEquals("Collection One", detail.collections.single().title)
                assertEquals(if (available) "http://10.0.2.2:8080/media/collection.webp" else null,
                    detail.collections.single().coverUrl)
                assertEquals(listOf("/api/apps/v1/spots/spot-one", "/api/apps/v1/collections/collection-one", "/api/apps/v1/comment-totals/targets/spot"), routes)
            } finally { client.close() }
        }
    }

    @Test fun catalogListsFollowExistingRoutesAndParsePublicFields() {
        runBlocking {
            val requests = mutableListOf<String>()
            val client = FloopelRestClient("https://api.example.test", HttpClient(MockEngine { request ->
                val path = request.url.encodedPath
                requests.add(path + "?" + request.url.encodedQuery)
                val response = when (path) {
                    "/api/apps/v1/locations" -> "[$location]"
                    "/api/apps/v1/spots" -> "[$spot]"
                    "/api/apps/v1/collections" -> "[$collection]"
                    "/api/apps/v1/content" -> "[$story]"
                    "/api/apps/v1/itineraries" -> "[$itinerary]"
                    else -> error("Unexpected route")
                }
                respond(envelope(response))
            }))
            try {
                val catalog = AppsTravelerCatalog(client)
                val region = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(catalog.regions("id")).value.single()
                assertEquals(CatalogKind.LOCATION, region.kind)
                assertEquals("Region One", region.title)

                val spots = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(catalog.spots("en")).value
                assertEquals("Alam", spots.single().category)
                assertEquals("A lovely place", spots.single().subtitle)
                assertEquals("https://cdn.example.test/spot.webp", spots.single().coverUrl)

                val collections = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(catalog.collections("id")).value
                assertEquals(CatalogKind.COLLECTION, collections.single().kind)
                assertNull(collections.single().subtitle)

                val stories = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(catalog.stories("id")).value
                assertEquals("About cultures", stories.single().subtitle)
                assertEquals(CatalogKind.STORY, stories.single().kind)

                val trips = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(catalog.curatedItineraries("id")).value
                assertEquals(CatalogKind.ITINERARY, trips.single().kind)
                assertTrue(requests.any { it.contains("source=curated") })
                assertTrue(requests.any { it.contains("level=region") })
                assertTrue(requests.any { it.contains("locale=en") })
            } finally { client.close() }
        }
    }



    @Test fun publicAiReferenceScoreIsShownOnlyWhenEligible() {
        runBlocking {
            val cases = listOf(
                """{"origin":"ai","suppressed":false,"score":4.2}""" to 4.2,
                """{"origin":"ai","suppressed":true,"score":4.8}""" to null,
                """{"origin":"ai","suppressed":false,"score":null}""" to null,
                """{"origin":"human","suppressed":false,"score":5.0}""" to null,
                """{"origin":"ai","suppressed":false,"score":7.0}""" to null,
            )
            cases.forEach { (reference, expected) ->
                val client = FloopelRestClient(
                    "https://api.example.test",
                    HttpClient(MockEngine {
                        respond(envelope(
                            """[{"id":"spot-1","slug":"place","name":"Place","reference_rating":""" +
                                reference + "}]",
                        ))
                    }),
                )
                try {
                    val result = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(
                        AppsTravelerCatalog(client).spots("id"),
                    ).value.single()
                    assertEquals(expected, result.aiReference?.score)
                } finally {
                    client.close()
                }
            }
        }
    }

    @Test fun searchUsesCanonicalSortAndSpotCategoryWithoutInventingEndpoints() {
        runBlocking {
            val requests = mutableListOf<String>()
            val client = FloopelRestClient(
                "https://api.example.test",
                HttpClient(MockEngine { request ->
                    requests.add(request.url.toString())
                    respond(envelope(
                        """{"q":"Beach Bali","sort":"top_rated","kinds":["spot"],"groups":[{"kind":"spot","label":"search.group.spot","items":[{"kind":"spot","id":"spot-2","slug":"beach","name":"Beach","type":"beach","geo":{"lat":-8.5,"lng":115.2}}]}]}""",
                    ))
                }),
            )
            try {
                val repository = AppsTravelerCatalog(client)
                val filtered = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(
                    repository.search(" Beach Bali ", "en", SearchSort.TOP_RATED, "heritage & culture"),
                )
                assertEquals(CatalogKind.SPOT, filtered.value.single().kind)
                assertEquals("beach", filtered.value.single().category)
                val uri = requests.single { it.contains("/api/apps/v1/search?") }
                assertEquals(2, requests.size)
                assertTrue(requests.last().contains("/api/apps/v1/comment-totals/targets/spot?target_ids=spot-2"))
                assertTrue(uri.contains("/api/apps/v1/search?"))
                assertTrue(uri.contains("q=Beach%20Bali") || uri.contains("q=Beach+Bali"))
                assertTrue(uri.contains("sort=top_rated"))
                assertTrue(uri.contains("category=heritage%20%26%20culture") || uri.contains("category=heritage+%26+culture"))
                assertTrue(uri.contains("locale=en"))
            } finally {
                client.close()
            }
        }
    }

    @Test fun searchCollectionUsesTheCanonicalSearchHitName() = runBlocking {
        val client = FloopelRestClient("https://api.example.test", HttpClient(MockEngine {
            respond(envelope("""{"groups":[{"kind":"collection","label":"search.group.collection","items":[{"kind":"collection","id":"collection-result","slug":"city-picks","name":"City Picks","geo":{},"breadcrumb":[],"reference_rating":{"suppressed":true,"origin":"ai"}}]}]}"""))
        }))
        try {
            val result = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(AppsTravelerCatalog(client).search("city", "en"))
            assertEquals("City Picks", result.value.single().title)
            assertEquals(CatalogKind.COLLECTION, result.value.single().kind)
        } finally { client.close() }
    }

    @Test fun shortSearchDoesNotCallBackendEvenWithSortOrCategory() {
        runBlocking {
            var requestCount = 0
            val client = FloopelRestClient(
                "https://api.example.test",
                HttpClient(MockEngine {
                    requestCount++
                    respond(envelope("""{"groups":[]}"""))
                }),
            )
            try {
                val result = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(
                    AppsTravelerCatalog(client).search("  a ", "id", SearchSort.TOP_RATED, "temple"),
                )
                assertTrue(result.value.isEmpty())
                assertEquals(0, requestCount)
            } finally {
                client.close()
            }
        }
    }

    @Test fun searchHonorsThreeCharacterMinimumAndOfficialSearchGroups() {
        runBlocking {
            var called = 0
            val client = FloopelRestClient("https://api.example.test", HttpClient(MockEngine {
                called++
                respond(envelope("""{"q":"Borobudur","sort":"relevance","kinds":["spot"],"groups":[{"kind":"spot","label":"search.group.spot","items":[{"kind":"spot","id":"spot-1","slug":"borobudur","name":"Borobudur","geo":{"lat":0,"lng":0}}]}]}"""))
            }))
            try {
                val repository = AppsTravelerCatalog(client)
                val early = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(repository.search("Bo", "id"))
                assertTrue(early.value.isEmpty())
                assertEquals(0, called)
                val result = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(repository.search("Borobudur", "id"))
                assertEquals("Borobudur", result.value.single().title)
                assertEquals(CatalogKind.SPOT, result.value.single().kind)
                assertEquals(2, called) // Search and its optional stored comment totals.
            } finally { client.close() }
        }
    }

    @Test fun requestEncodesLocationParentAndSelectedSpotSlug() {
        runBlocking {
            val paths = mutableListOf<String>()
            val client = FloopelRestClient("https://api.example.test", HttpClient(MockEngine { request ->
                paths.add(request.url.toString())
                val body = if (request.url.encodedPath.contains("/spots/")) spot else "[$location]"
                respond(envelope(body))
            }))
            try {
                val repository = AppsTravelerCatalog(client)
                assertIs<CatalogResult.Ready<List<CatalogEntry>>>(repository.childRegions("parent id", "id"))
                val selected = CatalogEntry("spot-1", "spot one", "Spot One", null, CatalogKind.SPOT, null)
                assertEquals("Spot One", assertIs<CatalogResult.Ready<CatalogEntry>>(
                    repository.detail(selected, "en"),
                ).value.title)
                assertTrue(paths.any { it.contains("parent%20id") || it.contains("parent+id") })
                assertTrue(paths.any { it.contains("/spots/spot%20one") || it.contains("/spots/spot+one") })
            } finally { client.close() }
        }
    }

    @Test fun locationAndEditorialDetailsRespectContractBodyKeys() {
        runBlocking {
            val client = FloopelRestClient("https://api.example.test", HttpClient(MockEngine { request ->
                val body = if (request.url.encodedPath.contains("/content/")) {
                    """{"id":"story-1","slug":"story-one","locations":[],"title":"Story One","body_html":"<p>Native culture</p>"}"""
                } else {
                    """{"id":"loc-1","slug":"region-one","name":"Region One","why_it_matters":"<p>Historical place</p>"}"""
                }
                respond(envelope(body))
            }))
            try {
                val repository = AppsTravelerCatalog(client)
                val loc = CatalogEntry("loc-1", "region-one", "Region One", null, CatalogKind.LOCATION, null)
                assertEquals("<p>Historical place</p>", assertIs<CatalogResult.Ready<CatalogEntry>>(
                    repository.detail(loc, "id"),
                ).value.bodyHtml)
                val content = CatalogEntry("story-1", "story-one", "Story One", null, CatalogKind.STORY, null)
                assertEquals("<p>Native culture</p>", assertIs<CatalogResult.Ready<CatalogEntry>>(
                    repository.detail(content, "id"),
                ).value.bodyHtml)
            } finally { client.close() }
        }
    }

    @Test fun maintenanceAndHttpErrorsRemainDifferentFromAnEmptyResult() {
        runBlocking {
            val client = FloopelRestClient("https://api.example.test", HttpClient(MockEngine { request ->
                respond("Service temporarily unavailable", status = HttpStatusCode.ServiceUnavailable)
            }))
            try {
                assertIs<CatalogResult.Maintenance>(AppsTravelerCatalog(client).spots("id"))
            } finally { client.close() }

            val unauthorized = FloopelRestClient("https://api.example.test", HttpClient(MockEngine {
                respond("unauthorized", status = HttpStatusCode.Unauthorized)
            }))
            try {
                assertEquals(401, assertIs<CatalogResult.HttpError>(
                    AppsTravelerCatalog(unauthorized).collections("id"),
                ).status)
            } finally { unauthorized.close() }
        }
    }

    @Test fun malformedEnvelopeIsNotSilentlyShownAsAnEmptyCatalog() {
        runBlocking {
            val malformed = listOf(
                "not-json",
                "{}",
                """{"meta":{"code":"OK"},"data":[]}""",
                """{"meta":{"code":"OK"},"error":{"message":"no"},"data":[]}""",
                envelope("null"),
                envelope("{}"),
            )
            for (body in malformed) {
                val client = FloopelRestClient("https://api.example.test", HttpClient(MockEngine { respond(body) }))
                try {
                    assertIs<CatalogResult.InvalidResponse>(AppsTravelerCatalog(client).spots("id"))
                } finally { client.close() }
            }
        }
    }

    @Test fun emptySuccessfulListIsARealEmptyState() {
        runBlocking {
            val client = FloopelRestClient("https://api.example.test", HttpClient(MockEngine {
                respond(envelope("[]"))
            }))
            try {
                assertTrue(assertIs<CatalogResult.Ready<List<CatalogEntry>>>(
                    AppsTravelerCatalog(client).collections("id"),
                ).value.isEmpty())
            } finally { client.close() }
        }
    }

    @Test fun unsafeMediaVariantNeverRendersAsAnExternalImage() {
        runBlocking {
            val client = FloopelRestClient("https://api.example.test", HttpClient(MockEngine {
                respond(envelope("""[{"id":"s1","name":"No Photo","slug":"no-photo","cover_hero_image":{"variants":{"medium":{"url":"http://unsafe.test/photo"}}}}]"""))
            }))
            try {
                val item = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(
                    AppsTravelerCatalog(client).spots("id"),
                ).value.single()
                assertNull(item.coverUrl)
            } finally { client.close() }
        }
    }


    @Test fun homeRailPaginationMatchesWebTravelerLimits() {
        runBlocking {
            val paths = mutableListOf<String>()
            val client = FloopelRestClient(
                "https://api.example.test",
                HttpClient(MockEngine { request ->
                    paths.add(request.url.encodedPath + "?" + request.url.encodedQuery)
                    respond(envelope("[]"))
                }),
            )
            try {
                val catalog = AppsTravelerCatalog(client)
                catalog.spots("id", perPage = 15)
                catalog.collections("id", perPage = 10)
                catalog.stories("id", perPage = 6)
                assertTrue(paths.any { it.contains("/spots?") && it.contains("per_page=15") })
                assertTrue(paths.any { it.contains("/collections?") && it.contains("per_page=10") })
                assertTrue(paths.any { it.contains("/content?") && it.contains("per_page=6") })
            } finally {
                client.close()
            }
        }
    }

    @Test fun storyTypeFacetFollowsApprovedContentTypes() {
        runBlocking {
            val queries = mutableListOf<String>()
            val client = FloopelRestClient(
                "https://api.example.test",
                HttpClient(MockEngine { request ->
                    if (request.url.encodedPath == "/api/apps/v1/content") queries.add(request.url.encodedQuery)
                    respond(envelope("[$story]"))
                }),
            )
            try {
                val catalog = AppsTravelerCatalog(client)
                for (type in com.floopel.feature.traveler.domain.model.StoryType.entries) {
                    val result = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(
                        catalog.stories("id", type),
                    )
                    assertEquals(CatalogKind.STORY, result.value.single().kind)
                    assertTrue(queries.last().contains("type=" + type.wireValue))
                }
                catalog.stories("en")
                assertTrue(queries.last().contains("locale=en"))
                assertTrue(!queries.last().contains("type="))
            } finally {
                client.close()
            }
        }
    }

}
