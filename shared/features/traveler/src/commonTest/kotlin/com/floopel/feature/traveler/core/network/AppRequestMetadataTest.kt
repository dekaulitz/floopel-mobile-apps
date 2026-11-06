package com.floopel.feature.traveler.core.network

import com.floopel.feature.traveler.data.remote.NativeApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class AppRequestMetadataTest {
    @Test
    fun nativeMetadataFollowsEveryApiTransportWithoutOverridingCredentials() = runTest {
        for (platform in AppPlatform.entries) {
            val paths = mutableListOf<String>()
            val api = NativeApi(HttpClient(MockEngine { request ->
                paths += request.url.encodedPath
                assertEquals(listOf(platform.name), request.headers.getAll("X-App-Platform"))
                assertEquals(listOf("2.4.1-test"), request.headers.getAll("X-App-Version"))
                if (request.url.encodedPath.endsWith("/me"))
                    assertEquals("Bearer owner", request.headers[HttpHeaders.Authorization])
                respond("""{"meta":{"code":"OK","pagination":{"page":1,"total_pages":1}},"error":null,"data":[]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }), "https://fixture.invalid", platform, appVersion = "2.4.1-test")
            try {
                api.quests(1, "en")
                api.registrationRequest("/api/mobile/v1/auth/logout", HttpMethod.Post)
                val rest = api.catalogTransport()
                rest.execute(RestMethod.GET, "/api/apps/v1/me", headers = mapOf("Authorization" to "Bearer owner"))
                rest.execute(RestMethod.POST, "/api/apps/v1/me/interests", "{}",
                    mapOf("x-app-platform" to "wrong", "x-app-version" to "wrong"))
                rest.upload("/api/apps/v1/me/itineraries/plan/cover", byteArrayOf(1), "image/png",
                    mapOf("Idempotency-Key" to "fixture-upload"))
                rest.maintenanceProbe()
                assertEquals(6, paths.size)
            } finally { api.close() }
        }
    }

    @Test
    fun absentLegacyVersionIsNotInventedAndInvalidHeaderValuesFailBeforeSending() {
        val headers = HeadersBuilder()
        AppRequestMetadata(AppPlatform.IOS, null).applyTo(headers)
        assertEquals("IOS", headers["X-App-Platform"])
        assertNull(headers["X-App-Version"])
        for (version in listOf("", " ", "1.0\r\nother: value"))
            assertFailsWith<IllegalArgumentException> { AppRequestMetadata(AppPlatform.ANDROID, version) }
    }
}
