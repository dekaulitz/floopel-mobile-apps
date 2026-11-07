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
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class NativeSettingsApiTest {
    @Test fun settingsPreserveUnknownConsentUseNativeIdentityAndDoNotRetryMutations() = runTest {
        var settingsBody = """{"notifications":{"trip_updates":true,"marketing":false,"product":true},"privacy":{"profile_discoverable":false}}"""
        var status = HttpStatusCode.OK
        var networkFailure = false
        var failureBody: String? = null
        var writes = 0
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            val data = when {
                path.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"settings-access","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"protected","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
                path.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
                else -> {
                    assertEquals("/api/apps/v1/me/settings", path)
                    assertEquals("Bearer settings-access", request.headers[HttpHeaders.Authorization])
                    assertNull(request.headers[HttpHeaders.Cookie])
                    if (request.method == HttpMethod.Patch) {
                        writes++
                        val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                        assertEquals(false, payload["notifications"]!!.jsonObject["marketing"]!!.jsonPrimitive.boolean)
                        assertEquals(false, payload["privacy"]!!.jsonObject["profile_discoverable"]!!.jsonPrimitive.boolean)
                    }
                    if (networkFailure) error("fixture network failure")
                    return@MockEngine respond(failureBody ?: """{"meta":{"code":"OK"},"error":null,"data":$settingsBody}""", status,
                        headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
            respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
        val session = TravelerSession(api, object : RefreshCredentialStore {
            var credential: String? = null
            override fun read() = credential
            override fun write(credential: String) { this.credential = credential }
            override fun clear() { credential = null }
        }, "en")
        val settings = NativeSettingsApi(api.catalogTransport(), session)
        assertEquals(CatalogResult.HttpError(401), settings.load())
        session.login("fixture@example.invalid", "fixture-password")
        val initial = TravelerSettings(true, false, true, false)
        assertEquals(CatalogResult.Ready(initial), settings.load())
        assertEquals(CatalogResult.Ready(initial), settings.save(initial))
        assertEquals(1, writes)
        status = HttpStatusCode.ServiceUnavailable
        assertEquals(CatalogResult.Maintenance(), settings.save(initial))
        assertEquals(2, writes)
        assertTrue(session.view.value.signedIn)
        status = HttpStatusCode.UnprocessableEntity
        assertEquals(CatalogResult.HttpError(422), settings.save(initial))
        assertEquals(3, writes)
        status = HttpStatusCode.Conflict
        failureBody = """{"meta":{"request_id":"11111111-1111-4111-8111-111111111111","timestamp":1793000000,"code":"CONFLICT"},"error":{"message":"Not a UI branch token","details":[{"field":"profile_discoverable","code":"active_public_trip","message":"Do not display raw messages"}]},"data":null}"""
        assertEquals(CatalogResult.HttpError(409, code = "CONFLICT", fieldIssues = listOf(CatalogFieldIssue("profile_discoverable", "active_public_trip"))), settings.save(initial))
        assertEquals(4, writes)
        failureBody = failureBody!!.replace("\"field\":\"profile_discoverable\"", "\"field\":false")
        assertEquals(CatalogResult.HttpError(409, code = "CONFLICT"), settings.save(initial))
        assertEquals(5, writes)
        failureBody = "<html>profile_discoverable active_public_trip</html>"
        assertEquals(CatalogResult.HttpError(409), settings.save(initial))
        assertEquals(6, writes)
        assertTrue(session.view.value.signedIn)
        failureBody = null
        status = HttpStatusCode.OK
        settingsBody = "{}"
        assertEquals(CatalogResult.Ready(TravelerSettings(null, null, null, null)), settings.load())
        settingsBody = """{"notifications":{"marketing":"false"}}"""
        assertEquals(CatalogResult.InvalidResponse, settings.load())
        settingsBody = """{"notifications":false}"""
        assertEquals(CatalogResult.InvalidResponse, settings.load())
        settingsBody = """{"privacy":null}"""
        assertEquals(CatalogResult.InvalidResponse, settings.load())
        settingsBody = "[]"
        assertEquals(CatalogResult.InvalidResponse, settings.load())
        networkFailure = true
        assertEquals(CatalogResult.NetworkError, settings.load())
        assertTrue(session.view.value.signedIn)
        client.close()
    }
}
