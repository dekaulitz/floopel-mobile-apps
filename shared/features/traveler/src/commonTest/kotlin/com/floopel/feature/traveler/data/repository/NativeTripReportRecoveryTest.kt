package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TripRecoveryStore
import com.floopel.feature.traveler.domain.repository.RefreshCredentialStore
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.feature.auth.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class NativeTripReportRecoveryTest {
    @Test fun lostResponseRestoresTheExactActorBodyAndKeyAndChecksBeforeExplicitRetry() = runTest {
        val f = ReportFixture()
        try {
            f.login()
            f.loseResponse = true
            assertEquals(CatalogResult.NetworkError, f.repository.submitTripReport(f.intent))
            assertNotNull(f.store.value)
            val recreated = NativeModerationApi(f.api.catalogTransport(), f.session, f.store)
            val pending = assertIs<CatalogResult.Ready<TripReportIntent?>>(recreated.pendingTripReport(f.tripId)).value!!
            assertEquals(f.intent.copy(details = "Synthetic detail 🌋"), pending)
            assertEquals(1, f.bodies.size)
            f.loseResponse = false; f.status = HttpStatusCode.NotFound
            f.response = f.failure("NOT_FOUND")
            assertEquals(CatalogResult.HttpError(404, code = "NOT_FOUND"), recreated.checkTripReport(pending))
            assertEquals(1, f.bodies.size)
            assertEquals("/api/apps/v1/me/report-creation-operations/report-key", f.operationReads.last())
            f.status = HttpStatusCode.Created; f.response = f.success("REPORT_CREATED")
            assertIs<CatalogResult.Ready<ReportReceipt>>(recreated.submitTripReport(pending))
            assertEquals(2, f.bodies.size)
            assertEquals(f.bodies.first(), f.bodies.last())
            assertEquals(listOf<String?>("report-key", "report-key"), f.keys)
            assertFalse(f.bodies.first().contains("Local title"))
            assertEquals(CatalogResult.Ready(Unit), recreated.completeTripReport(pending))
            assertNull(f.store.value)
        } finally { f.client.close() }
    }

    @Test fun onlyAnOwnedMatchingReceiptCanConfirmOrReleaseTheIntent() = runTest {
        val f = ReportFixture()
        try {
            f.login(); f.loseResponse = true
            f.repository.submitTripReport(f.intent)
            val pending = assertIs<CatalogResult.Ready<TripReportIntent?>>(f.repository.pendingTripReport(f.tripId)).value!!
            assertEquals(CatalogResult.InvalidResponse, f.repository.completeTripReport(pending))
            f.loseResponse = false; f.status = HttpStatusCode.OK
            for (body in listOf(f.success("OK"), f.success("REPORT").replace(f.tripId, f.otherId),
                f.success("REPORT").replace("\"reason_code\":\"unsafe_guidance\"", "\"reason_code\":\"privacy\""),
                f.success("REPORT").replace("\"status\":\"new\"", "\"status\":\"invented\""), "<html>REPORT</html>")) {
                f.response = body
                assertEquals(CatalogResult.InvalidResponse, f.repository.checkTripReport(pending))
                assertNotNull(f.store.value)
            }
            f.response = f.success("REPORT").replace("\"status\":\"new\"", "\"status\":\"resolved\"")
            assertEquals(CatalogResult.Ready(ReportReceipt(f.reportId, "resolved")), f.repository.checkTripReport(pending))
            assertEquals(1, f.bodies.size)
            assertEquals(CatalogResult.Ready(Unit), f.repository.completeTripReport(pending))
            assertNull(f.store.value)
        } finally { f.client.close() }
    }

    @Test fun foreignActorChangedInputAndStorageFailureCannotReplaceOrSubmitAnUnresolvedReport() = runTest {
        val f = ReportFixture()
        try {
            f.login(); f.loseResponse = true
            f.repository.submitTripReport(f.intent)
            val saved = f.store.value
            assertEquals(CatalogResult.InvalidResponse, f.repository.submitTripReport(f.intent.copy(details = "Different")))
            assertEquals(1, f.bodies.size)
            assertEquals(saved, f.store.value)
            val other = f.intent.copy(tripId = f.otherId, title = "Other Trip", details = "Other detail", key = "other-key")
            assertEquals(CatalogResult.NetworkError, f.repository.submitTripReport(other))
            assertEquals(other, assertIs<CatalogResult.Ready<TripReportIntent?>>(f.repository.pendingTripReport(f.otherId)).value)
            assertEquals(2, f.bodies.size)
            val bothSaved = f.store.value
            f.profile = f.otherId
            assertEquals(CatalogResult.InvalidResponse, f.repository.pendingTripReport(f.tripId))
            assertEquals(bothSaved, f.store.value)
            f.profile = f.profileId; f.store.value = null; f.store.fail = true
            assertEquals(CatalogResult.InvalidResponse, f.repository.submitTripReport(f.intent))
            assertEquals(2, f.bodies.size)
        } finally { f.client.close() }
    }

    @Test fun confirmingOneTripDoesNotRemoveAnotherUnresolvedTarget() = runTest {
        val f = ReportFixture()
        try {
            f.login(); f.loseResponse = true
            f.repository.submitTripReport(f.intent)
            val other = f.intent.copy(tripId = f.otherId, title = "Other Trip", details = "Other detail", key = "other-key")
            f.repository.submitTripReport(other)
            val pending = assertIs<CatalogResult.Ready<TripReportIntent?>>(f.repository.pendingTripReport(f.tripId)).value!!
            f.loseResponse = false; f.status = HttpStatusCode.OK; f.response = f.success("REPORT")
            assertIs<CatalogResult.Ready<ReportReceipt>>(f.repository.checkTripReport(pending))
            assertEquals(CatalogResult.Ready(Unit), f.repository.completeTripReport(pending))
            assertEquals(CatalogResult.Ready(null), f.repository.pendingTripReport(f.tripId))
            assertEquals(CatalogResult.Ready(other), f.repository.pendingTripReport(f.otherId))
        } finally { f.client.close() }
    }

    @Test fun malformedFailureAndReadValidationNeverReleaseAnUnknownIntentButConfirmedWriteValidationCan() = runTest {
        val f = ReportFixture()
        try {
            f.login(); f.loseResponse = true; f.repository.submitTripReport(f.intent); f.loseResponse = false
            val pending = assertIs<CatalogResult.Ready<TripReportIntent?>>(f.repository.pendingTripReport(f.tripId)).value!!
            f.status = HttpStatusCode.UnprocessableEntity; f.response = f.failure("VALIDATION")
            assertEquals(CatalogResult.HttpError(422, code = "VALIDATION"), f.repository.checkTripReport(pending))
            assertNotNull(f.store.value)
            f.response = "<html>VALIDATION</html>"
            assertEquals(CatalogResult.HttpError(422), f.repository.submitTripReport(pending))
            assertNotNull(f.store.value)
            f.response = f.failure("VALIDATION")
            assertEquals(CatalogResult.HttpError(422, code = "VALIDATION"), f.repository.submitTripReport(pending))
            assertNull(f.store.value)
        } finally { f.client.close() }
    }

    @Test fun scalarBoundaryRejectsOverflowBeforeAnyWriteAndConfirmedReceiptSurvivesCleanupStorageFailure() = runTest {
        val f = ReportFixture()
        try {
            f.login()
            assertEquals(CatalogResult.InvalidResponse, f.repository.submitTripReport(f.intent.copy(details = "🌋".repeat(1001))))
            assertEquals(CatalogResult.InvalidResponse, f.repository.submitTripReport(f.intent.copy(key = "🌋".repeat(33))))
            assertTrue(f.bodies.isEmpty())
            f.onPost = { f.store.fail = true }
            assertIs<CatalogResult.Ready<ReportReceipt>>(f.repository.submitTripReport(f.intent.copy(details = "🌋".repeat(1000))))
            assertNotNull(f.store.value)
        } finally { f.client.close() }
    }

    @Test fun logoutDuringThePostDiscardsTheLateReceiptAndDoesNotRecreateClearedRecovery() = runTest {
        val f = ReportFixture()
        try {
            f.login(); f.postGate = CompletableDeferred()
            val sending = async { f.repository.submitTripReport(f.intent) }
            f.postStarted.await()
            assertEquals(1, f.bodies.size)
            f.session.logout()
            assertNull(f.store.value)
            f.postGate!!.complete(Unit)
            assertEquals(CatalogResult.HttpError(401), sending.await())
            assertNull(f.store.value)
        } finally { f.client.close() }
    }
}

/** In-memory encrypted-store port substitute; HTTP uses synthetic canonical envelopes, never a real service. */
private class ReportFixture {
    val tripId = "11111111-1111-4111-8111-111111111111"
    val reportId = "22222222-2222-4222-8222-222222222222"
    val profileId = "33333333-3333-4333-8333-333333333333"
    val otherId = "44444444-4444-4444-8444-444444444444"
    var profile = profileId
    val intent = TripReportIntent(tripId, "Local title", ItineraryReportReason.UNSAFE, "  Synthetic detail 🌋  ", "report-key")
    val store = ReportStore()
    val bodies = mutableListOf<String>()
    val keys = mutableListOf<String?>()
    val operationReads = mutableListOf<String>()
    var status = HttpStatusCode.Created
    var response = success("REPORT_CREATED")
    var loseResponse = false
    var postGate: CompletableDeferred<Unit>? = null
    val postStarted = CompletableDeferred<Unit>()
    var onPost: () -> Unit = {}
    val client = HttpClient(MockEngine { request ->
        val path = request.url.encodedPath
        val data = when {
            path.endsWith("/logout") -> return@MockEngine respond("", HttpStatusCode.NoContent)
            path.endsWith("/login") -> """{"tokens":{"token_type":"Bearer","access_token":"synthetic-access","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"synthetic-refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
            path.endsWith("/progress") -> """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
            path.endsWith("/me") -> """{"profile":{"id":"$profile"}}"""
            else -> {
                assertEquals("Bearer synthetic-access", request.headers[HttpHeaders.Authorization])
                if (request.method == HttpMethod.Post) {
                    assertEquals("/api/apps/v1/reports", path)
                    assertNotNull(store.value)
                    bodies += (request.body as TextContent).text; keys += request.headers["Idempotency-Key"]
                    postStarted.complete(Unit)
                    postGate?.await()
                    onPost()
                    if (loseResponse) error("Synthetic lost response")
                } else { operationReads += path; assertNull(request.headers["Idempotency-Key"]) }
                return@MockEngine respond(response, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        respond(envelope("OK", data), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    })
    val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
    val session = TravelerSession(api, object : RefreshCredentialStore {
        override fun read(): String? = null
        override fun write(credential: String) = Unit
        override fun clear() = Unit
    }, "en", onLogout = { store.clear() })
    val repository = NativeModerationApi(api.catalogTransport(), session, store)
    suspend fun login() { session.login("fixture@example.invalid", "synthetic-password") }
    fun success(code: String) = envelope(code, """{"id":"$reportId","target_type":"trip","target_id":"$tripId","reason_code":"unsafe_guidance","status":"new","internal_note":"Must never leave Data"}""")
    fun failure(code: String) = """{"meta":{"request_id":"$reportId","timestamp":1793000000,"code":"$code"},"error":{"message":"Synthetic failure","details":null},"data":null}"""
    private fun envelope(code: String, data: String) = """{"meta":{"request_id":"$reportId","timestamp":1793000000,"code":"$code"},"error":null,"data":$data}"""
}

/** Storage failures are explicit so a failed preflight can be shown to issue zero HTTP writes. */
private class ReportStore : TripRecoveryStore {
    var value: String? = null
    var fail = false
    override fun read() = value
    override fun write(record: String) { check(!fail); value = record }
    override fun clear() { check(!fail); value = null }
}
