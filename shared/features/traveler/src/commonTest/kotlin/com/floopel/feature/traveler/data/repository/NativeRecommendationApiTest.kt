package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
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

class NativeRecommendationApiTest {
    @Test fun recommendationsDistinguishMissingInterestsNoMatchAndAuthorizedProjection() = runTest {
        val f = RecommendationFixture()
        try {
            f.login()
            assertEquals(CatalogResult.Ready(TravelerRecommendation.NeedsInterests), f.repository.load("en"))
            assertEquals(0, f.recommendationCalls)
            f.interests = "[\"culture\"]"; f.status = HttpStatusCode.NoContent
            assertEquals(CatalogResult.Ready(TravelerRecommendation.NoMatch), f.repository.load("en"))
            f.status = HttpStatusCode.OK
            val matched = assertIs<TravelerRecommendation.Matched>(assertIs<CatalogResult.Ready<TravelerRecommendation>>(f.repository.load("id")).value)
            assertEquals(listOf("culture"), matched.interests)
            assertTrue(matched.collection.detailLoaded)
            assertEquals("Museum", matched.collection.related.single().title)
            assertEquals("<p>Selected for you</p>", matched.collection.bodyHtml)
            f.response = f.collection.replace("\"members\":[{\"target_type\":\"spot\",\"target\":{\"id\":\"museum\",\"slug\":\"museum\",\"name\":\"Museum\"}}]", "\"members\":[]")
            assertEquals(CatalogResult.Ready(TravelerRecommendation.NoMatch), f.repository.load("en"))
        } finally { f.client.close() }
    }
    @Test fun personalizationErrorsDoNotMasqueradeAsEditorialFallbackOrLoseIdentity() = runTest {
        val f = RecommendationFixture()
        try {
            f.login(); f.interests = "[\"culture\"]"
            f.response = """{"id":"collection","locations":[],"title":"For you","members":[],"matched_interests":"culture"}"""
            assertEquals(CatalogResult.InvalidResponse, f.repository.load("en"))
            f.status = HttpStatusCode.ServiceUnavailable
            assertEquals(CatalogResult.Maintenance(), f.repository.load("en"))
            assertTrue(f.session.view.value.signedIn)
            f.status = HttpStatusCode.TooManyRequests
            assertEquals(CatalogResult.HttpError(429), f.repository.load("en"))
            f.networkFailure = true
            assertEquals(CatalogResult.NetworkError, f.repository.load("en"))
            assertTrue(f.session.view.value.signedIn)
            f.networkFailure = false; f.status = HttpStatusCode.OK; f.interests = "{}"
            assertEquals(CatalogResult.InvalidResponse, f.repository.load("en"))
        } finally { f.client.close() }
    }
}
private class RecommendationFixture {
    var interests = "[]"
    var status = HttpStatusCode.OK
    var recommendationCalls = 0
    var response: String? = null
    var networkFailure = false
    val collection = """{"id":"collection","slug":"for-you","locations":[],"title":"For you","intro":"<p>Selected for you</p>","members":[{"target_type":"spot","target":{"id":"museum","slug":"museum","name":"Museum"}}],"matched_interests":["culture"]}"""
    val client = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        val data = when {
            path.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"fixture","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"protected","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
            path.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
            else -> {
                assertEquals("Bearer fixture", request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                if (networkFailure) error("Fixture network failure")
                if (path.endsWith("/interests")) """{"interest_ids":$interests}"""
                else {
                    recommendationCalls++
                    assertEquals("20", request.url.parameters["per_page"])
                    return@MockEngine respond(if(status == HttpStatusCode.NoContent) "" else """{"meta":{"code":"OK"},"error":null,"data":${response ?: collection}}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })
    val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
    val session = TravelerSession(api, object : RefreshCredentialStore {
        override fun read(): String? = null
        override fun write(credential: String) = Unit
        override fun clear() = Unit
    }, "en")
    val repository = NativeRecommendationApi(api.catalogTransport(), session, AppsTravelerCatalog(api.catalogTransport()))
    suspend fun login() { session.login("fixture@example.invalid", "fixture-password") }
}
