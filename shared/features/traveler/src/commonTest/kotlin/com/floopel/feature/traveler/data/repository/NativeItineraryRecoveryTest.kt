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
import kotlin.test.*

class NativeItineraryRecoveryTest {
    @Test fun workingItineraryCoverageRetainsServerOrderAndMissingCoverageIsAnError() = runTest {
        val fixture = ItineraryFixture()
        try {
            fixture.login()
            val canonical = fixture.response.replace("\"locations\":[]", "\"locations\":$coverage")
            fixture.response = canonical
            assertEquals(coverageLocations, assertIs<CatalogResult.Ready<OwnedItineraryDetail>>(
                fixture.repository.detail("plan")).value.itinerary.locations)
            fixture.response = canonical.replace("\"locations\":$coverage,", "")
            assertEquals(CatalogResult.InvalidResponse, fixture.repository.detail("plan"))
            fixture.response = canonical.replace("\"locations\":$coverage", "\"locations\":null")
            assertEquals(CatalogResult.InvalidResponse, fixture.repository.detail("plan"))
            fixture.response = canonical.replace("\"locations\":$coverage", "\"locations\":[]")
            assertTrue(assertIs<CatalogResult.Ready<OwnedItineraryDetail>>(
                fixture.repository.detail("plan")).value.itinerary.locations.isEmpty())
        } finally { fixture.client.close() }
    }
    @Test fun curatedDetailMapsAuthorizedCoverWithoutChangingClonePolicy() = runTest {
        val fixture=ItineraryFixture()
        try {
            fixture.login()
            fixture.response=fixture.response.replace("\"is_owner\":true", "\"is_owner\":false,\"source\":\"curated\",\"cover_image\":{\"variants\":{\"medium\":{\"url\":\"http://untrusted.test/image.webp\"},\"small\":{\"url\":\"https://cdn.example.test/cover.webp\"}}}")
            val detail=assertIs<CatalogResult.Ready<OwnedItineraryDetail>>(fixture.repository.detail("plan")).value
            assertTrue(detail.itinerary.isCurated); assertTrue(detail.itinerary.canClone); assertFalse(detail.itinerary.isOwner)
            assertEquals("https://cdn.example.test/cover.webp",detail.itinerary.coverUrl)
            assertEquals("Note",detail.stops.single().note)
        } finally { fixture.client.close() }
    }
    @Test fun uncertainCreateSurvivesAdapterRecreationAndReusesExactlyTheSameKeyAndInput() = runTest {
        val fixture = ItineraryFixture()
        try {
            fixture.login()
            fixture.networkFailure = true
            val draft = ItineraryDraft("My plan", "Description")
            assertIs<ItineraryWriteResult.Failed>(fixture.repository.create(draft))
            assertNotNull(fixture.record)
            assertEquals(CatalogResult.Ready(draft), fixture.repository.pendingDraft())
            assertEquals(ItineraryWriteResult.PendingDifferentAction, fixture.repository.create(draft.copy(title = "Other plan")))
            assertEquals(1, fixture.writes)
            fixture.networkFailure = false
            val recreated = fixture.newRepository()
            assertIs<ItineraryWriteResult.Completed<OwnedItineraryDetail>>(recreated.create(draft))
            assertEquals<List<String?>>(listOf(fixture.key, fixture.key), fixture.keys)
            assertEquals(fixture.bodies[0], fixture.bodies[1])
            assertNull(fixture.record)
            assertEquals(1, fixture.generatedKeys)
        } finally { fixture.client.close() }
    }

    @Test fun summaryMutationResponsesCompleteWithoutPretendingThatStopsWereLoaded() = runTest {
        val fixture = ItineraryFixture()
        try {
            fixture.login()
            fixture.response = """{"id":"plan","locations":[],"title":"My plan","description":"Description","source":"user_created","visibility":"private","is_owner":true,"cover_media_id":"cover"}"""
            val created = assertIs<ItineraryWriteResult.Completed<OwnedItineraryDetail>>(fixture.repository.create(ItineraryDraft("My plan", "Description"))).value
            assertFalse(created.stopsLoaded)
            assertEquals("cover", created.itinerary.coverMediaId)
            assertNull(fixture.record)
            val updated = assertIs<CatalogResult.Ready<OwnedItineraryDetail>>(fixture.repository.update("plan", ItineraryDraft("New title", "Description", "cover"))).value
            assertFalse(updated.stopsLoaded)
            assertTrue(fixture.bodies.last().contains("\"cover_media_id\":\"cover\""))
            assertFalse(assertIs<ItineraryWriteResult.Completed<OwnedItineraryDetail>>(fixture.repository.clone("plan")).value.stopsLoaded)
            assertEquals(CatalogResult.InvalidResponse, fixture.repository.detail("plan")) // A detail read still requires its Stop projection.
        } finally { fixture.client.close() }
    }
    @Test fun consentWaitCannotMoveAnOldItineraryIntentIntoTheSuccessorIdentity() = runTest {
        val fixture = ItineraryFixture()
        try {
            fixture.login()
            val initial = fixture.session.identityGeneration
            val gate = object : TravelerConsentGate {
                override suspend fun ensure(): CatalogResult<Unit> { fixture.session.login("successor@example.invalid", "fixture-password"); return CatalogResult.Ready(Unit) }
            }
            val repository = fixture.newRepository(gate)
            val result = assertIs<ItineraryWriteResult.Failed>(repository.create(ItineraryDraft("Old actor plan", "Description")))
            assertEquals(CatalogResult.HttpError(401), result.cause)
            assertEquals(0, fixture.writes)
            assertNull(fixture.record)
            assertTrue(fixture.session.identityGeneration > initial)
            assertTrue(fixture.session.view.value.signedIn)
        } finally { fixture.client.close() }
    }

    @Test fun pendingStopRetainsItsOriginalTargetAndDayAcrossAdapterRecreation() = runTest {
        val fixture = ItineraryFixture()
        try {
            fixture.login()
            assertEquals(CatalogResult.Ready(null), fixture.repository.pendingStop())
            fixture.networkFailure = true
            assertIs<ItineraryWriteResult.Failed>(fixture.repository.addStop("plan", "spot", 2))
            assertEquals(CatalogResult.Ready(PendingItineraryStop("plan", "spot", 2)), fixture.newRepository().pendingStop())
            fixture.networkFailure = false
            assertIs<ItineraryWriteResult.Completed<*>>(fixture.newRepository().addStop("plan", "spot", 2))
            assertEquals(1, fixture.generatedKeys)
            assertEquals(CatalogResult.Ready(null), fixture.repository.pendingStop())
        } finally { fixture.client.close() }
    }

    @Test fun boundedRecoveryNeverReplacesExpiredOrCorruptIntentsAutomatically() = runTest {
        val fixture = ItineraryFixture()
        try {
            fixture.login()
            fixture.networkFailure = true
            val draft = ItineraryDraft("My plan", "Description")
            fixture.repository.create(draft)
            fixture.now += 86_400_001L
            assertEquals(ItineraryWriteResult.Expired, fixture.repository.create(draft))
            assertEquals(1, fixture.writes)
            assertEquals(CatalogResult.Ready(Unit), fixture.repository.startNewAction())
            fixture.record = "corrupt"
            assertEquals(ItineraryWriteResult.StorageFailure, fixture.repository.create(draft))
            assertEquals(CatalogResult.InvalidResponse, fixture.repository.pendingDraft())
            fixture.storageFailure = true
            assertEquals(ItineraryWriteResult.StorageFailure, fixture.repository.create(draft))
            assertEquals(CatalogResult.InvalidResponse, fixture.repository.startNewAction())
            fixture.storageFailure = false
            fixture.record = null
            assertEquals(CatalogResult.Ready(null), fixture.repository.pendingDraft())
            fixture.record = """{"profile_id":"another-owner","path":"/itineraries","body":"{}","key":"old"}"""
            assertEquals(CatalogResult.Ready(null), fixture.repository.pendingDraft())
            fixture.now = 1_800_000_000_000L
            fixture.networkFailure = false
            assertIs<ItineraryWriteResult.Completed<OwnedItineraryDetail>>(fixture.repository.create(draft))
            fixture.response = "[]"
            assertEquals(CatalogResult.InvalidResponse, fixture.repository.detail("plan"))
            assertEquals(CatalogResult.HttpError(422), fixture.repository.detail(""))
        } finally { fixture.client.close() }
    }

    @Test fun ownerEditsDayOperationsAndStopFieldsUseTheContractAndDoNotRetry() = runTest {
        val fixture = ItineraryFixture()
        try {
            fixture.login()
            val stop = OwnedStop("stop", "spot", "Spot", 1, 1, "Note", 45, "Tips", "09:30", "Walk")
            assertIs<CatalogResult.Ready<*>>(fixture.repository.update("plan", ItineraryDraft("My plan", "Description")))
            assertEquals(HttpMethod.Put, fixture.method)
            assertIs<CatalogResult.Ready<*>>(fixture.repository.updateStop("plan", stop))
            assertTrue(fixture.bodies.last().contains("\"arrive_time\":\"09:30\""))
            assertIs<CatalogResult.Ready<*>>(fixture.repository.updateStop("plan", stop.copy(note = "", tips = "", durationMinutes = null, arriveTime = "", travelNote = "")))
            assertTrue(fixture.bodies.last().contains("\"override_note\":null"))
            assertIs<CatalogResult.Ready<*>>(fixture.repository.reorder("plan", listOf(stop)))
            assertIs<CatalogResult.Ready<*>>(fixture.repository.titleDay("plan", 1, "City day"))
            assertIs<CatalogResult.Ready<*>>(fixture.repository.duplicateDay("plan", 1))
            assertIs<ItineraryWriteResult.Completed<*>>(fixture.repository.addStop("plan", "spot", 1))
            assertIs<ItineraryWriteResult.Completed<*>>(fixture.repository.clone("plan"))
            fixture.status = HttpStatusCode.NoContent
            assertEquals(CatalogResult.Ready(Unit), fixture.repository.removeStop("plan", "stop"))
            assertEquals(CatalogResult.Ready(Unit), fixture.repository.delete("plan"))
            fixture.status = HttpStatusCode.ServiceUnavailable
            val count = fixture.writes
            assertEquals(CatalogResult.Maintenance(), fixture.repository.update("plan", ItineraryDraft("My plan", "Description")))
            assertEquals(count + 1, fixture.writes)
            assertTrue(fixture.session.view.value.signedIn)
            fixture.status = HttpStatusCode.Conflict
            assertEquals(CatalogResult.HttpError(409), fixture.repository.removeStop("plan", "stop"))
            assertEquals(CatalogResult.HttpError(422), fixture.repository.updateStop("plan", stop.copy(day = 0)))
            assertEquals(CatalogResult.HttpError(422), fixture.repository.titleDay("plan", 0, ""))
            assertEquals(CatalogResult.HttpError(422), fixture.repository.duplicateDay("plan", 0))
            assertIs<ItineraryWriteResult.Failed>(fixture.repository.create(ItineraryDraft("", "")))
            assertIs<ItineraryWriteResult.Failed>(fixture.repository.addStop("plan", "", 0))
            assertIs<ItineraryWriteResult.Failed>(fixture.repository.clone(""))
        } finally { fixture.client.close() }
    }
}

private class ItineraryFixture {
    var record: String? = null
    var storageFailure = false
    var networkFailure = false
    var status = HttpStatusCode.OK
    var now = 1_800_000_000_000L
    val key = "01a3185c-5000-7000-8000-000000000001"
    var generatedKeys = 0
    var writes = 0
    val keys = mutableListOf<String?>()
    val bodies = mutableListOf<String>()
    var method = HttpMethod.Get
    var response = """{"id":"plan","locations":[],"title":"My plan","description":"Description","is_owner":true,"day_count":1,"stop_count":1,"stops":[{"id":"stop","spot_id":"spot","day":1,"position":1,"override_note":"Note","override_duration_min":45,"override_tips":"Tips","arrive_time":"09:30","travel_note":"Walk","spot":{"name":"Spot"}}],"days":[{"day":1,"title":"City day"}]}"""
    val client = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        val data = when {
            path.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"fixture","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"protected","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
            path.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
            path.endsWith("/me") -> """{"profile":{"id":"owner"}}"""
            else -> {
                assertEquals("Bearer fixture", request.headers[HttpHeaders.Authorization])
                method = request.method
                if (request.method != HttpMethod.Get) {
                    writes++; keys.add(request.headers["Idempotency-Key"])
                    bodies.add((request.body as? TextContent)?.text ?: "")
                }
                if (networkFailure) error("Fixture response lost")
                return@MockEngine respond("""{"meta":{"code":"OK"},"error":null,"data":$response}""", status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })
    private val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
    val session = TravelerSession(api, object : RefreshCredentialStore {
        var credential: String? = null
        override fun read() = credential
        override fun write(credential: String) { this.credential = credential }
        override fun clear() { credential = null }
    }, "en")
    private val storage = object : ItineraryRecoveryStore {
        override fun read(): String? { check(!storageFailure); return record }
        override fun write(record: String) { check(!storageFailure); this@ItineraryFixture.record = record }
        override fun clear() { check(!storageFailure); record = null }
    }
    fun newRepository(consent: TravelerConsentGate? = null) = NativeItineraryApi(api.catalogTransport(), session, storage, { generatedKeys++; key }, { now }, consent)
    val repository = newRepository()
    suspend fun login() { session.login("fixture@example.invalid", "fixture-password") }
}
