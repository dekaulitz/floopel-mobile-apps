package com.floopel.feature.traveler.core.network

import com.floopel.feature.traveler.data.remote.NativeApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class SystemMaintenanceTest {
    @Test fun genericServiceErrorsDoNotActivateSystemMaintenance() {
        val availability = SystemAvailability { 0 }
        availability.observe(503, null, 60)
        availability.observe(429, "system", 30)
        availability.observe(200, "system", 60)
        assertNull(availability.maintenance.value)
    }

    @Test fun explicitModeBlocksBothTransportsUntilAnEligibleSuccessfulRecoveryRead() = runTest {
        var now = 0L
        var requests = 0
        val availability = SystemAvailability { now }
        val client = HttpClient(MockEngine { request ->
            requests++
            if (requests == 1) respond("maintenance", HttpStatusCode.ServiceUnavailable,
                headersOf("X-Floopel-Maintenance" to listOf("system"), "Retry-After" to listOf("2")))
            else {
                assertEquals("/api/apps/v1/locations", request.url.encodedPath)
                respond("""{"meta":{"code":"OK"},"error":null,"data":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID, availability)
        try {
            assertIs<ApiOutcome.Unavailable>(api.catalogTransport().execute(RestMethod.GET, "/api/apps/v1/spots"))
            assertNotNull(availability.maintenance.value)
            assertEquals(2L, availability.retrySecondsRemaining())
            assertIs<ApiOutcome.Unavailable>(api.catalogTransport().execute(RestMethod.POST, "/api/apps/v1/me/itineraries", "{}"))
            assertFailsWith<ApiFailure> { api.login("fixture@example.invalid", "password") }
            assertFalse(api.recheckSystemMaintenance())
            assertEquals(1, requests)
            now = 2001
            assertTrue(api.recheckSystemMaintenance())
            assertNull(availability.maintenance.value)
            assertEquals(2, requests)
        } finally { api.close() }
    }

    @Test fun nativeAuthObservesTheSameMaintenanceSignal() = runTest {
        val client = HttpClient(MockEngine {
            respond("maintenance", HttpStatusCode.ServiceUnavailable,
                headersOf("X-Floopel-Maintenance" to listOf("system"), "Retry-After" to listOf("2")))
        })
        val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
        try {
            assertEquals(503, assertFailsWith<ApiFailure> { api.login("fixture@example.invalid", "password") }.status)
            assertNotNull(api.systemAvailability.maintenance.value)
        } finally { api.close() }
    }

    @Test fun oldSuccessfulReadCannotCloseANewerMaintenanceNotice() = runTest {
        var now = 0L
        val availability = SystemAvailability { now }
        availability.observe(503, "system", 2)
        val previous = availability.maintenance.value!!.revision
        now = 2001
        availability.observe(503, "system", 2)
        availability.recover(previous)
        assertNotNull(availability.maintenance.value)
        assertEquals(2L, availability.retrySecondsRemaining())
        availability.observe(503, null, 5)
        assertEquals(5L, availability.retrySecondsRemaining())
    }
}
