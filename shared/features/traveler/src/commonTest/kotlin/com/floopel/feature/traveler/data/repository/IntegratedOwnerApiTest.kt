package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.domain.model.SavedSpotPage
import com.floopel.feature.traveler.domain.model.CatalogResult
import com.floopel.feature.traveler.domain.model.OwnedItinerary
import com.floopel.feature.traveler.domain.repository.ItineraryRecoveryStore
import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.data.remote.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.*
import com.floopel.feature.traveler.feature.legal.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class IntegratedOwnerApiTest {
    @Test fun savedAndItineraryUseQuestIdentityAndReconsentBlocksWrites() = runTest {
        var pending = false
        var writes = 0
        var savedPageSeen: String? = null
        var savedPaginationValid = true
        var savedSpotOnSecondPage = false
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val data = when {
                path.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"same-identity","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"protected","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
                path.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
                else -> {
                    assertEquals("Bearer same-identity", request.headers[HttpHeaders.Authorization])
                    when {
                        path.endsWith("/me") -> """{"profile":{"username":"fixture","first_name":"Traveler"},"pending_reconsent":${if (pending) "[{}]" else "[]"}}"""
                        path.endsWith("/saved-spots") -> {
                            savedPageSeen = request.url.parameters["page"]
                            if (savedSpotOnSecondPage && savedPageSeen == "2")
                                """[{"spot":{"id":"spot","name":"Saved Spot","slug":"saved-spot"}}]""" else "[]"
                        }
                        path.endsWith("/itineraries") -> """[{"id":"itinerary","locations":[],"title":"My trip","day_count":1,"stop_count":0}]"""
                        else -> {
                            writes++
                            return@MockEngine respond("", HttpStatusCode.NoContent)
                        }
                    }
                }
            }
            val pagination = if (path.endsWith("/saved-spots") && savedPaginationValid) ",\"pagination\":{\"page\":${savedPageSeen ?: 1},\"total_pages\":2}" else ""
            respond("""{"meta":{"code":"OK"$pagination},"error":null,"data":$data}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
        val session = TravelerSession(api, object : RefreshCredentialStore {
            var saved: String? = null
            override fun read() = saved
            override fun write(credential: String) { saved = credential }
            override fun clear() { saved = null }
        }, "en")
        val rest = api.catalogTransport()
        val saved = NativeSavedSpotApi(rest, session, AppsTravelerCatalog(rest))
        val itineraries = NativeItineraryApi(rest, session, object : ItineraryRecoveryStore {
            override fun read(): String? = null
            override fun write(record: String) = Unit
            override fun clear() = Unit
        }, { error("No write expected") })
        assertEquals(CatalogResult.HttpError(401), saved.account())
        session.login("fixture@example.invalid", "fixture-password")
        assertIs<CatalogResult.Ready<*>>(saved.account())
        assertEquals(CatalogResult.Ready(SavedSpotPage(emptyList(), 1, 2)), saved.savedSpots())
        assertEquals("1", savedPageSeen)
        assertEquals(CatalogResult.Ready(SavedSpotPage(emptyList(), 2, 2)), saved.savedSpots(2))
        assertEquals("2", savedPageSeen)
        assertEquals(CatalogResult.Ready(false), saved.isSaved("spot"))
        assertEquals("2", savedPageSeen)
        savedSpotOnSecondPage = true
        assertEquals(CatalogResult.Ready(true), saved.isSaved("spot"))
        assertEquals("2", savedPageSeen)
        savedSpotOnSecondPage = false
        assertEquals(CatalogResult.HttpError(422), saved.savedSpots(0))
        assertEquals("2", savedPageSeen)
        savedPaginationValid = false
        assertEquals(CatalogResult.InvalidResponse, saved.savedSpots())
        assertEquals(CatalogResult.InvalidResponse, saved.isSaved("spot"))
        assertEquals("My trip", assertIs<CatalogResult.Ready<List<OwnedItinerary>>>(itineraries.mine()).value.single().title)
        pending = true
        assertEquals(CatalogResult.HttpError(409), saved.setSaved("spot", true))
        assertEquals(0, writes)
        pending = false
        assertEquals(CatalogResult.Ready(true), saved.setSaved("spot", true))
        assertEquals(1, writes)
        assertEquals(CatalogResult.Ready(false), saved.setSaved("spot", false))
        assertEquals(2, writes)
        client.close()
    }
}
