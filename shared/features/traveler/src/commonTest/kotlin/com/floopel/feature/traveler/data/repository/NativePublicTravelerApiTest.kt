package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.data.remote.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.*
import com.floopel.feature.traveler.feature.legal.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

class NativePublicTravelerApiTest {
    @Test fun publishedStopPreservesZeroAndNullDurationButRejectsANegativeValue() = runTest {
        val fixture = PublicFixture()
        try {
            fixture.login()
            for (duration in listOf("0", "null", "-1")) {
                fixture.response = fixture.snapshot.replace("\"position\":0", "\"position\":0,\"duration_minutes\":$duration")
                val result = fixture.repository.itinerary("culture-day")
                if (duration == "-1") assertEquals(CatalogResult.InvalidResponse, result)
                else assertEquals(duration.toIntOrNull(), assertIs<CatalogResult.Ready<PublicItinerarySnapshot>>(result).value.stops.single().durationMinutes)
            }
        } finally { fixture.client.close() }
    }

    @Test
    fun boundActivityCoveragePreservesEveryLocationAndRecoversWithoutSuccessorReads() = runTest {
        val fixture = PublicFixture()
        try {
            fixture.login()
            val expected = (1..12).map { n -> LocationReference(
                "11111111-1111-4111-8111-${n.toString().padStart(12, '0')}", "Bound location $n", "bound-$n", "city") }
            val locations = JsonArray(expected.map { buildJsonObject {
                put("id", it.id); put("name", it.name); put("slug", it.slug); put("level", it.level)
            } })
            fixture.activityOverride = fixture.activityLocations(locations)
            fixture.response = """{"title":"Successor","locations":[]}"""
            for (locale in listOf("id", "en")) {
                val page = assertIs<CatalogResult.Ready<PublicAchievementPage<PublicQuestActivity>>>(
                    fixture.repository.questActivities(null, locale, 2)).value
                assertEquals(expected, page.items.single().locations)
            }
            assertFalse(fixture.paths.any { it.contains("/quests/") })
            fixture.status = HttpStatusCode.ServiceUnavailable
            assertIs<CatalogResult.Maintenance>(fixture.repository.questActivities(null, "en", 2))
            assertTrue(fixture.session.view.value.signedIn)
            fixture.status = HttpStatusCode.OK
            fixture.activityOverride = fixture.activityLocations(JsonArray(emptyList()))
            assertTrue(assertIs<CatalogResult.Ready<PublicAchievementPage<PublicQuestActivity>>>(
                fixture.repository.questActivities(null, "en", 2)).value.items.single().locations.isEmpty())
        } finally { fixture.client.close() }
    }

    @Test
    fun missingNullOrMalformedCoverageFailsInsteadOfHidingNonemptyActivities() = runTest {
        val fixture = PublicFixture()
        try {
            fixture.login()
            for (locations in listOf(null, JsonNull, JsonPrimitive("invalid"),
                JsonArray(listOf(buildJsonObject { put("id", "invalid") })),
                JsonArray(listOf(buildJsonObject {
                    put("id", "11111111-1111-4111-8111-111111111111"); put("name", "Bali")
                    put("slug", "bali"); put("level", "planet")
                })))) {
                fixture.activityOverride = fixture.activityLocations(locations)
                assertEquals(CatalogResult.InvalidResponse, fixture.repository.questActivities(null, "en", 2))
            }
            fixture.activityOverride = fixture.activityEnvelope
            assertIs<CatalogResult.Ready<PublicAchievementPage<PublicQuestActivity>>>(fixture.repository.questActivities(null, "en", 2))
        } finally { fixture.client.close() }
    }
    @Test
    fun communitySearchPreservesLiteralQueryAndRegionOnEveryPage() = runTest {
        val fixture = PublicFixture()
        try {
            fixture.login()
            val region = "00000000-0000-4000-8000-000000000123"
            val query = "Museum & culture + 100%_ #東京"
            for (page in 1..2) {
                val entries = assertIs<CatalogResult.Ready<List<PublicItinerarySummary>>>(
                    fixture.repository.searchItineraries(" $query ", region, page)
                ).value
                assertEquals("Public Author", entries.single().ownerDisplayName)
                assertEquals(1, entries.single().dayCount)
                assertEquals(true, entries.single().allowClone)
                assertEquals(kotlin.time.Instant.parse("2026-11-01T10:00:00Z"), entries.single().publishedAt)
            }
            assertEquals(2, fixture.publicationQueries.size)
            fixture.publicationQueries.forEachIndexed { index, url ->
                assertEquals(query, url.parameters["q"])
                assertEquals(region, url.parameters["location_id"])
                assertEquals((index + 1).toString(), url.parameters["page"])
                assertEquals(setOf("q", "location_id", "page", "per_page"), url.parameters.names())
            }
            assertEquals(
                CatalogResult.HttpError(422),
                fixture.repository.searchItineraries("x".repeat(161), null, 1),
            )
            assertEquals(
                CatalogResult.HttpError(422),
                fixture.repository.searchItineraries("", "invalid", 1),
            )
            assertEquals(2, fixture.publicationQueries.size)
        } finally {
            fixture.client.close()
        }
    }

    @Test
    fun guestActivityFeedUsesOnlyThePublicProjection() = runTest {
        val fixture = PublicFixture()
        try {
            fixture.allowGuestActivity = true
            val result = assertIs<CatalogResult.Ready<PublicAchievementPage<PublicQuestActivity>>>(fixture.repository.questActivities(null, "en", 2)).value
            assertEquals(2, result.page)
            assertFalse(result.toString().contains("private-secret"))
            assertFalse(fixture.session.view.value.signedIn)
        } finally { fixture.client.close() }
    }

    @Test
    fun activityFeedUsesCanonicalQuestLocationsAndFiveItemNearbyPreview() = runTest {
        val fixture = PublicFixture()
        try {
            assertTrue(fixture.activityQueries.isEmpty())
            fixture.login()
            val page =
                assertIs<CatalogResult.Ready<PublicAchievementPage<PublicQuestActivity>>>(
                        fixture.repository.questActivities(GeoPoint(-6.0, 106.0), "en", 2, 5)
                    )
                    .value
            assertEquals(2, page.page)
            assertEquals(listOf("Bali", "Bandung"), page.items.single().locations.map { it.name })
            assertEquals("Fahmi Sulaiman", page.items.single().displayName)
            assertFalse(page.toString().contains("private-secret"))
            val query = fixture.activityQueries.single()
            assertEquals("5", query.parameters["per_page"])
            assertEquals("2", query.parameters["page"])
            assertEquals("15000", query.parameters["radius_m"])
            assertEquals("-6.0", query.parameters["lat"])
            fixture.repository.questActivities(null, "id", 2)
            assertNull(fixture.activityQueries.last().parameters["lat"])
            assertNull(fixture.activityQueries.last().parameters["radius_m"])
        } finally {
            fixture.client.close()
        }
    }

    @Test
    fun malformedActivitiesAndInvalidCriteriaNeverBecomeAnEmptySuccess() = runTest {
        val fixture = PublicFixture()
        try {
            fixture.login()
            for (invalid in
                listOf(
                    fixture.activityEnvelope.replace("2026-10-21T10:00:00Z", "invalid"),
                    fixture.activityEnvelope.replace("\"page\":2", "\"page\":1"),
                    fixture.activityEnvelope.replace("\"total\":6", "\"total\":-1"),
                )) {
                fixture.activityOverride = invalid
                assertEquals(
                    CatalogResult.InvalidResponse,
                    fixture.repository.questActivities(null, "en", 2),
                )
            }
            val before = fixture.activityQueries.size
            assertEquals(
                CatalogResult.HttpError(422),
                fixture.repository.questActivities(GeoPoint(Double.NaN, 1.0), "en", 2),
            )
            assertEquals(
                CatalogResult.HttpError(422),
                fixture.repository.questActivities(null, "fr", 2),
            )
            assertEquals(
                CatalogResult.HttpError(422),
                fixture.repository.questActivities(null, "en", 0),
            )
            assertEquals(
                CatalogResult.HttpError(422),
                fixture.repository.questActivities(null, "en", 1, 101),
            )
            assertEquals(before, fixture.activityQueries.size)
        } finally {
            fixture.client.close()
        }
    }

    @Test
    fun publicThresholdsComeFromTheTargetAndRemainCompatibleWithOlderResponses() = runTest {
        val f = PublicFixture()
        try {
            f.login()
            f.response =
                """{"xp_total":3500,"level":3,"level_name":"Voyager","xp_floor":3000,"xp_to_next_level":4000}"""
            assertEquals(
                PublicProfileProgress(3, "Voyager", 3500, 3000, 4000),
                assertIs<CatalogResult.Ready<PublicProfileProgress>>(
                        f.repository.progress("author")
                    )
                    .value,
            )
            f.response =
                """{"xp_total":120000,"level":10,"level_name":"Legendary Explorer","xp_floor":120000,"xp_to_next_level":null}"""
            assertNull(
                assertIs<CatalogResult.Ready<PublicProfileProgress>>(
                        f.repository.progress("author")
                    )
                    .value
                    .xpToNextLevel
            )
            f.response = """{"xp_total":3500,"level":3,"level_name":"Voyager"}"""
            assertNull(
                assertIs<CatalogResult.Ready<PublicProfileProgress>>(
                        f.repository.progress("author")
                    )
                    .value
                    .xpFloor
            )
            assertTrue(f.session.view.value.signedIn)
        } finally {
            f.client.close()
        }
    }

    @Test
    fun invalidPublicThresholdsAndReadFailuresDoNotProduceInventedProgress() = runTest {
        val f = PublicFixture()
        try {
            f.login()
            for (fields in
                listOf(
                    """"xp_floor":3501,"xp_to_next_level":4000""",
                    """"xp_floor":-1,"xp_to_next_level":4000""",
                    """"xp_floor":"3000","xp_to_next_level":4000""",
                    """"xp_floor":3000,"xp_to_next_level":-1""",
                    """"xp_floor":3000,"xp_to_next_level":"4000""",
                    """"xp_floor":null,"xp_to_next_level":4000""",
                    """"xp_floor":null,"xp_to_next_level":null""",
                    """"xp_floor":3000""",
                    """"xp_to_next_level":null""",
                    """"xp_floor":3000,"xp_to_next_level":9223372036854775807""",
                )) {
                f.response = """{"xp_total":3500,"level":3,"level_name":"Voyager",$fields}"""
                assertEquals(CatalogResult.InvalidResponse, f.repository.progress("author"), fields)
            }
            f.status = HttpStatusCode.NotFound
            assertEquals(CatalogResult.HttpError(404), f.repository.progress("author"))
            f.status = HttpStatusCode.ServiceUnavailable
            assertEquals(CatalogResult.Maintenance(), f.repository.progress("author"))
            assertTrue(f.session.view.value.signedIn)
        } finally {
            f.client.close()
        }
    }

    @Test
    fun publicProfileAndSnapshotNeverProjectPrivateFieldsOrMutableWorkingData() = runTest {
        val f = PublicFixture()
        try {
            f.login()
            val profile =
                assertIs<CatalogResult.Ready<TravelerPublicProfile>>(f.repository.profile("author"))
                    .value
            assertEquals("<b>Public bio</b>", profile.bio)
            assertNull(profile.savedSpots)
            assertFalse(profile.toString().contains("private-secret"))
            assertEquals(2, profile.itineraries)
            assertEquals(
                1,
                assertIs<CatalogResult.Ready<List<PublicItinerarySummary>>>(
                        f.repository.itineraries(1)
                    )
                    .value
                    .single()
                    .version,
            )
            assertEquals(
                "culture-day",
                assertIs<CatalogResult.Ready<List<PublicItinerarySummary>>>(
                        f.repository.profileItineraries("author")
                    )
                    .value
                    .single()
                    .slug,
            )
            val snapshot =
                assertIs<CatalogResult.Ready<PublicItinerarySnapshot>>(
                        f.repository.itinerary("culture-day")
                    )
                    .value
            assertEquals("publication", snapshot.publicationId)
            assertEquals(coverageLocations, snapshot.locations)
            assertEquals("Museum", snapshot.stops.single().spot.title)
            assertEquals(GeoPoint(-6.9, 107.6), snapshot.stops.single().spot.geo)
            assertEquals("Public note", snapshot.stops.single().note)
            assertFalse(snapshot.toString().contains("private-secret"))
            assertEquals(CatalogResult.HttpError(422), f.repository.itinerary("../private"))
            assertEquals(CatalogResult.HttpError(422), f.repository.itineraries(0))
        } finally {
            f.client.close()
        }
    }

    @Test
    fun inaccessibleHiddenOrMalformedPublicProjectionsAreNotDisplayedAsAnEmptyProfile() = runTest {
        val f = PublicFixture()
        try {
            f.login()
            f.status = HttpStatusCode.NotFound
            assertEquals(CatalogResult.HttpError(404), f.repository.profile("author"))
            f.status = HttpStatusCode.ServiceUnavailable
            assertEquals(CatalogResult.Maintenance(), f.repository.itinerary("culture-day"))
            assertTrue(f.session.view.value.signedIn)
            f.status = HttpStatusCode.OK
            f.response = f.snapshot.replace("\"active\"", "\"moderation_hidden\"")
            assertEquals(CatalogResult.InvalidResponse, f.repository.itinerary("culture-day"))
            f.response = f.snapshot.replace("\"allow_clone\":true", "\"allow_clone\":\"true\"")
            assertEquals(CatalogResult.InvalidResponse, f.repository.itinerary("culture-day"))
            f.response = f.snapshot.replace("\"spot_id\":\"museum\"", "\"spot_id\":\"other\"")
            assertEquals(CatalogResult.InvalidResponse, f.repository.itinerary("culture-day"))
            f.response = """{"username":"author","counts":{"saved_spots":-1}}"""
            assertEquals(CatalogResult.InvalidResponse, f.repository.profile("author"))
            f.response = "{}"
            assertEquals(CatalogResult.InvalidResponse, f.repository.itineraries(1))
            f.networkFailure = true
            assertEquals(CatalogResult.NetworkError, f.repository.profileItineraries("author"))
        } finally {
            f.client.close()
        }
    }
}

private class PublicFixture {
    val paths = mutableListOf<String>()
    val publicationQueries = mutableListOf<Url>()
    var allowGuestActivity = false
    val activityQueries = mutableListOf<Url>()
    var activityOverride: String? = null
    val activityEnvelope =
        """{"meta":{"code":"QUEST_ACTIVITIES","pagination":{"page":2,"total":6,"total_pages":2}},"error":null,"data":[{"username":"fahmi","display_name":"Fahmi Sulaiman","quest_id":"quest","quest_slug":"city-walk","quest_title":"City walk","locations":[{"id":"11111111-1111-4111-8111-111111111111","name":"Bali","slug":"bali","level":"region"},{"id":"22222222-2222-4222-8222-222222222222","name":"Bandung","slug":"bandung","level":"city"}],"started_at":"2026-10-21T10:00:00Z","private_note":"private-secret"}]}"""

    fun activityLocations(locations: JsonElement?): String {
        val envelope = Json.parseToJsonElement(activityEnvelope).jsonObject
        val activity = envelope.getValue("data").jsonArray.single().jsonObject
        val fields = activity - "locations"
        val updated = if (locations == null) JsonObject(fields) else JsonObject(fields + ("locations" to locations))
        return JsonObject(envelope + ("data" to JsonArray(listOf(updated)))).toString()
    }

    var status = HttpStatusCode.OK
    var response: String? = null
    var networkFailure = false
    private val summary =
        """{"id":"plan","slug":"culture-day","locations":$coverage,"title":"Culture day","description":"Public description","visibility":"public","allow_clone":true,"publication_id":"publication","publication_version":1,"stop_count":1,"owner_username":"author","owner_display_name":"Public Author","day_count":1,"published_at":"2026-11-01T10:00:00Z"}"""
    val snapshot =
        """{"id":"plan","slug":"culture-day","locations":$coverage,"title":"Culture day","description":"Public description","visibility":"public","distribution_status":"active","allow_clone":true,"publication_id":"publication","publication_version":1,"owner_public_profile":{"username":"author","display_name":"Author"},"stops":[{"id":"stop","spot_id":"museum","spot":{"id":"museum","slug":"museum","name":"Museum","geo":{"lat":-6.9,"lng":107.6}},"day":1,"position":0,"public_note":"Public note","override_note":"private-secret"}]}"""
    val client =
        HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                paths += path
                if (path.endsWith("/public-itineraries")) publicationQueries += request.url
                val data =
                    when {
                        path.endsWith("/login") ->
                            """{"tokens":{"token_type":"Bearer","access_token":"fixture","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"protected","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
                        path.endsWith("/quest-activities") -> {
                            if (allowGuestActivity) assertNull(request.headers[HttpHeaders.Authorization])
                            else assertEquals("Bearer fixture", request.headers[HttpHeaders.Authorization])
                            activityQueries += request.url
                            return@MockEngine respond(
                                activityOverride ?: activityEnvelope,
                                status,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        path.endsWith("/author/progress") -> {
                            assertEquals(
                                "Bearer fixture",
                                request.headers[HttpHeaders.Authorization],
                            )
                            return@MockEngine respond(
                                """{"meta":{"code":"OK"},"error":null,"data":${response ?: "{}"}}""",
                                status,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        path.endsWith("/progress") ->
                            """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
                        path.contains("/comment-totals/targets/") -> {
                            assertNull(request.headers[HttpHeaders.Authorization])
                            """{"items":[]}"""
                        }
                        path == "/api/apps/v1/spots/museum" -> {
                            assertNull(request.headers[HttpHeaders.Authorization])
                            """{"id":"museum","slug":"museum","name":"Museum","geo":{"lat":-6.9,"lng":107.6},"comment_count":0}"""
                        }
                        else -> {
                            assertEquals(HttpMethod.Get, request.method)
                            assertEquals(
                                "Bearer fixture",
                                request.headers[HttpHeaders.Authorization],
                            )
                            assertNull(request.headers[HttpHeaders.Cookie])
                            if (networkFailure) error("Fixture network failure")
                            val data =
                                response
                                    ?: when {
                                        path.endsWith("/author") ->
                                            """{"username":"author","first_name":"Author","bio":"<b>Public bio</b>","email":"private-secret","joined_year":2026,"counts":{"itineraries":2,"regions":3,"published_stories":4,"saved_spots":null}}"""
                                        path.endsWith("/culture-day") -> snapshot
                                        else -> "[$summary]"
                                    }
                            return@MockEngine respond(
                                """{"meta":{"code":"OK"},"error":null,"data":$data}""",
                                status,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }
                respond(
                    """{"meta":{"code":"OK"},"error":null,"data":$data}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        )
    val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
    val session =
        TravelerSession(
            api,
            object : RefreshCredentialStore {
                override fun read(): String? = null

                override fun write(credential: String) = Unit

                override fun clear() = Unit
            },
            "en",
        )
    val repository = NativePublicTravelerApi(api.catalogTransport(), session)

    suspend fun login() {
        session.login("fixture@example.invalid", "fixture-password")
    }
}
