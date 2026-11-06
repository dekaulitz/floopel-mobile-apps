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

class NativeAccountApiTest {
    @Test fun ownerEditorsUseExistingContractsAndPreservePartialUpdates() = runTest {
        val fixture = AccountFixture()
        try {
            assertEquals(CatalogResult.HttpError(401), fixture.repository.profile())
            fixture.login()
            val profile = PersonalProfile("Traveler", "Owner", "traveler", "ID")
            fixture.data = """{"profile":${fixture.profileJson}}"""
            assertEquals(CatalogResult.Ready(profile), fixture.repository.profile())
            fixture.data = fixture.profileJson
            assertEquals(CatalogResult.Ready(profile), fixture.repository.updateProfile(profile.copy(username = "before"), profile))
            assertEquals("/api/apps/v1/me/profile", fixture.lastPath)
            assertEquals("""{"username":"traveler"}""", fixture.lastBody)
            fixture.repository.updateProfile(profile.copy(firstName = "Before", lastName = "Before", nationalityCode = "GB"), profile)
            val changed = Json.parseToJsonElement(fixture.lastBody!!).jsonObject
            assertEquals(setOf("first_name", "last_name", "nationality_country_code"), changed.keys)
            assertEquals(CatalogResult.HttpError(422), fixture.repository.updateProfile(profile, profile.copy(firstName = "")))
            assertEquals(CatalogResult.HttpError(422), fixture.repository.updateProfile(profile, profile.copy(nationalityCode = "XXZ")))
            fixture.data = """[{"code":"ID","name":"Indonesia","labels":{"id":"Indonesia","en":"Indonesia"}}]"""
            assertEquals(CatalogResult.Ready(listOf(NationalityOption("ID", "Indonesia"))), fixture.repository.nationalities("id"))
            assertEquals(CatalogResult.HttpError(422), fixture.repository.nationalities("xx"))
            fixture.data = "null"
            assertEquals(CatalogResult.Ready(null), fixture.repository.emergencyContact())
            val contact = EmergencyContact("Contact", "sibling", "ID", "8123456789")
            fixture.data = """{"name":"Contact","relationship":"sibling","phone":{"region":"ID","national_number":"8123456789"}}"""
            assertEquals(CatalogResult.Ready(contact), fixture.repository.saveEmergencyContact(contact))
            assertEquals(CatalogResult.Ready(contact), fixture.repository.emergencyContact())
            assertEquals(CatalogResult.HttpError(422), fixture.repository.saveEmergencyContact(contact.copy(name = "")))
            assertFalse(contact.toString().contains("8123456789"))
            assertFalse(profile.toString().contains("Owner"))
        } finally { fixture.client.close() }
    }

    @Test fun interestsAreServerDrivenAndFailuresDoNotRetryWritesOrLoseIdentity() = runTest {
        val fixture = AccountFixture()
        try {
            fixture.login()
            fixture.vocabulary = """[{"id":"culture","label":"Culture"}]"""
            fixture.data = """{"interest_ids":["culture","food","nature"]}"""
            val selected = setOf("culture", "food", "nature")
            assertEquals(CatalogResult.Ready(TravelerInterests(listOf(InterestOption("culture", "Culture")), selected)), fixture.repository.interests("en"))
            assertEquals(CatalogResult.Ready(selected), fixture.repository.saveInterests(selected))
            assertEquals(CatalogResult.HttpError(422), fixture.repository.saveInterests(setOf("culture")))
            assertEquals(CatalogResult.HttpError(422), fixture.repository.interests("xx"))
            val before = fixture.writes
            fixture.status = HttpStatusCode.ServiceUnavailable
            assertEquals(CatalogResult.Maintenance(), fixture.repository.saveInterests(selected))
            assertEquals(before + 1, fixture.writes)
            assertTrue(fixture.session.view.value.signedIn)
            fixture.status = HttpStatusCode.UnprocessableEntity
            assertEquals(CatalogResult.HttpError(422), fixture.repository.interests("id"))
            fixture.status = HttpStatusCode.OK
            fixture.data = "[]"
            assertEquals(CatalogResult.InvalidResponse, fixture.repository.interests("id"))
            fixture.data = """{"has_password":"false","google_connected":true}"""
            assertEquals(CatalogResult.InvalidResponse, fixture.repository.security())
            fixture.data = """{"has_password":false,"google_connected":true}"""
            assertEquals(CatalogResult.Ready(CredentialCapabilities(false, true)), fixture.repository.security())
            fixture.data = """{"retry_in_seconds":30}"""
            assertEquals(CatalogResult.Ready(30), fixture.repository.requestPasswordSetup())
            fixture.data = """{"retry_in_seconds":-1}"""
            assertEquals(CatalogResult.InvalidResponse, fixture.repository.requestPasswordSetup())
            fixture.networkFailure = true
            assertEquals(CatalogResult.NetworkError, fixture.repository.profile())
            assertTrue(fixture.session.view.value.signedIn)
        } finally { fixture.client.close() }
    }

    @Test fun credentialChangeEnforcesPolicyAndDiscardsTheSharedSessionAfter204() = runTest {
        val fixture = AccountFixture()
        try {
            fixture.login()
            assertFalse(validTravelerPassword("short1"))
            assertFalse(validTravelerPassword("abcdefghijkl"))
            assertFalse(validTravelerPassword("123456789012"))
            assertFalse(validTravelerPassword("a1" + "é".repeat(36)))
            assertTrue(validTravelerPassword("ExamplePassword123"))
            assertEquals(CatalogResult.HttpError(422), fixture.repository.changePassword("", "ExamplePassword123"))
            assertEquals(CatalogResult.HttpError(422), fixture.repository.changePassword("ExamplePassword123", "ExamplePassword123"))
            fixture.status = HttpStatusCode.TooManyRequests
            assertEquals(CatalogResult.HttpError(429), fixture.repository.changePassword("old-password", "ExamplePassword123"))
            assertTrue(fixture.session.view.value.signedIn)
            fixture.status = HttpStatusCode.NoContent
            assertEquals(CatalogResult.Ready(Unit), fixture.repository.changePassword("old-password", "ExamplePassword123"))
            assertFalse(fixture.session.view.value.signedIn)
            assertNull(fixture.credential)
        } finally { fixture.client.close() }
    }
}

private class AccountFixture {
    var data = "{}"
    var vocabulary = "[]"
    var status = HttpStatusCode.OK
    var networkFailure = false
    var writes = 0
    var lastPath = ""
    var lastBody: String? = null
    var credential: String? = null
    val profileJson = """{"first_name":"Traveler","last_name":"Owner","username":"traveler","nationality_country_code":"ID"}"""
    val client = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        val response = when {
            path.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"fixture","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"protected","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
            path.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
            path.endsWith("/logout") -> "null"
            else -> {
                assertEquals("Bearer fixture", request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                if (networkFailure) error("Fixture network failure")
                lastPath = path
                lastBody = (request.body as? TextContent)?.text
                if (request.method != HttpMethod.Get) writes++
                return@MockEngine respond("""{"meta":{"code":"OK"},"error":null,"data":${if (path == "/api/apps/v1/interests") vocabulary else data}}""", status,
                    headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        respond("""{"meta":{"code":"OK"},"error":null,"data":$response}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })
    private val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
    val session = TravelerSession(api, object : RefreshCredentialStore {
        override fun read() = credential
        override fun write(credential: String) { this@AccountFixture.credential = credential }
        override fun clear() { credential = null }
    }, "en")
    val repository = NativeAccountApi(api.catalogTransport(), session)
    suspend fun login() { session.login("fixture@example.invalid", "fixture-password") }
}
