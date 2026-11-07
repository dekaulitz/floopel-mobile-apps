package com.floopel.feature.traveler.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlin.test.*

class TravelerReadCacheTest {
    @Test fun noStoreAndRevalidationResponsesCannotHidePublicationRevocation() = runBlocking {
        for (control in listOf("private, no-store", "No-Cache", "max-age=0")) {
            var requests = 0
            val client = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine {
                requests++
                if (requests == 1) respond(body(1), headers = headersOf(HttpHeaders.CacheControl, control))
                else respond("{}", HttpStatusCode.NotFound)
            }), readCache = TravelerReadCache())
            try {
                val path = "/api/apps/v1/public-itineraries/fixture"
                assertIs<ApiOutcome.Success>(client.execute(RestMethod.GET, path))
                assertEquals(404, assertIs<ApiOutcome.HttpFailure>(client.execute(RestMethod.GET, path)).status)
                assertEquals(2, requests)
            } finally { client.close() }
        }
    }

    @Test fun explicitNoCacheRequestReadsAndReplacesAnOlderPageSnapshot() = runBlocking {
        var requests = 0
        val client = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine { respond(body(++requests)) }), readCache = TravelerReadCache())
        try {
            client.execute(RestMethod.GET, path)
            assertEquals(body(2), assertIs<ApiOutcome.Success>(client.execute(RestMethod.GET, path,
                headers = mapOf(HttpHeaders.CacheControl to "no-cache"))).responseBody)
            assertEquals(body(2), assertIs<ApiOutcome.Success>(client.execute(RestMethod.GET, path)).responseBody)
            assertEquals(2, requests)
        } finally { client.close() }
    }

    @Test fun freshNoStoreAndRejectedReadsEvictOlderSnapshots() = runBlocking {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.NotFound)) {
            var requests = 0
            val cache = TravelerReadCache()
            val client = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine {
                val index = ++requests
                if (index == 2) respond(body(index), status, headersOf(HttpHeaders.CacheControl, "no-store"))
                else respond(body(index))
            }), readCache = cache)
            try {
                client.execute(RestMethod.GET, path)
                cache.withFreshReads { client.execute(RestMethod.GET, path) }
                assertEquals(body(3), assertIs<ApiOutcome.Success>(client.execute(RestMethod.GET, path)).responseBody)
            } finally { client.close() }
        }
    }

    @Test fun delayedReadCannotRepopulateAnEntryAfterANoStoreResponse() = runBlocking {
        var requests = 0
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val client = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine {
            val index = ++requests
            if (index == 1) { started.complete(Unit); release.await() }
            respond(body(index), headers = if (index == 2) headersOf(HttpHeaders.CacheControl, "no-store") else headersOf())
        }), readCache = TravelerReadCache())
        try {
            val delayed = async { client.execute(RestMethod.GET, path) }
            started.await()
            client.execute(RestMethod.GET, path)
            release.complete(Unit); delayed.await()
            assertEquals(body(3), assertIs<ApiOutcome.Success>(client.execute(RestMethod.GET, path)).responseBody)
            assertEquals(3, requests)
        } finally { release.complete(Unit); client.close() }
    }

    @Test fun nearbyRadiusRefreshBypassesSnapshotWhileGlobalSpotFeedStillCaches() = runBlocking {
        var requests = 0
        val client = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine { respond(body(++requests)) }), readCache = TravelerReadCache())
        try {
            repeat(2) { client.execute(RestMethod.GET, "/api/apps/v1/spots?lat=-6&lng=106&radius_m=15000&page=1") }
            assertEquals(2, requests)
            repeat(2) { client.execute(RestMethod.GET, "/api/apps/v1/spots?locale=id&page=1") }
            assertEquals(3, requests)
        } finally { client.close() }
    }
    @Test fun nearestLocationAlwaysReadsCurrentCatalogRatherThanPageSnapshot() = runBlocking {
        var requests = 0
        val client = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine { respond(body(++requests)) }), readCache = TravelerReadCache())
        try {
            repeat(2) { client.execute(RestMethod.GET, "/api/apps/v1/locations/nearest?lat=-6&lng=106") }
            assertEquals(2, requests)
        } finally { client.close() }
    }
    @Test fun revokedMembershipAndOrganizerRightsAreCheckedOnEveryTripRead() = runBlocking {
        for (route in listOf("/trips/fixture", "/trips/fixture/itinerary", "/trips/fixture/members", "/trips/fixture/invitations", "/me/trips", "/me/trip-invitations")) {
            var authorized = true
            var requests = 0
            val client = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine {
                requests++
                if (authorized) respond(body(1)) else respond("{}", HttpStatusCode.NotFound)
            }), readCache = TravelerReadCache())
            try {
                val headers = mapOf("Authorization" to "Bearer synthetic-fixture")
                assertIs<ApiOutcome.Success>(client.execute(RestMethod.GET, "/api/apps/v1$route", headers = headers))
                authorized = false
                assertIs<ApiOutcome.HttpFailure>(client.execute(RestMethod.GET, "/api/apps/v1$route", headers = headers))
                assertEquals(2, requests)
            } finally { client.close() }
        }
    }
    private fun body(value: Int) = """{"meta":{"code":"OK"},"error":null,"data":{"value":$value}}"""
    private val path = "/api/apps/v1/spots?locale=id"

    @Test fun profileAvailabilityIsReadAgainAfterPrivacyChanges() = runBlocking {
        var requests = 0
        val client = FloopelRestClient("https://local.example.test", HttpClient(MockEngine {
            requests++
            if (requests == 1) respond(body(1)) else respond("{}", HttpStatusCode.NotFound)
        }), readCache = TravelerReadCache())
        try {
            assertIs<ApiOutcome.Success>(client.execute(RestMethod.GET, "/api/apps/v1/profiles/traveler"))
            assertFalse(client.execute(RestMethod.GET, "/api/apps/v1/profiles/traveler") is ApiOutcome.Success)
            assertEquals(2, requests)
        } finally { client.close() }
    }

    @Test fun revisitingUsesOneMinuteSnapshotThenExpiresWithoutExtendingLifetime() = runBlocking {
        var time = 0L; var requests = 0
        val cache = TravelerReadCache(nowMillis = { time })
        val client = FloopelRestClient("https://local.example.test", HttpClient(MockEngine { respond(body(++requests)) }), readCache = cache)
        try {
            client.execute(RestMethod.GET, path); time = 59_999
            assertEquals(body(1), assertIs<ApiOutcome.Success>(client.execute(RestMethod.GET, path)).responseBody)
            assertEquals(1, requests)
            time = 60_000; client.execute(RestMethod.GET, path); assertEquals(2, requests)
        } finally { client.close() }
    }

    @Test fun ownerLocaleQueryAndPageAreIsolatedAndLogoutInvalidatesSnapshot() = runBlocking {
        var requests = 0
        val cache = TravelerReadCache()
        val client = FloopelRestClient("https://local.example.test", HttpClient(MockEngine { respond(body(++requests)) }), readCache = cache)
        try {
            for (owner in listOf("fixture-owner-a", "fixture-owner-b")) {
                for (query in listOf("locale=id", "locale=en", "locale=id&page=2")) {
                    repeat(2) { client.execute(RestMethod.GET, "/api/apps/v1/me/itineraries?$query", headers = mapOf("Authorization" to owner)) }
                }
            }
            assertEquals(6, requests)
            cache.clear()
            client.execute(RestMethod.GET, "/api/apps/v1/me/itineraries?locale=id", headers = mapOf("Authorization" to "fixture-owner-a"))
            assertEquals(7, requests)
        } finally { client.close() }
    }

    @Test fun refreshBypassesSnapshotAndUpdatesItWithoutDiscardingOtherPages() = runBlocking {
        var requests = 0
        val cache = TravelerReadCache()
        val client = FloopelRestClient("https://local.example.test", HttpClient(MockEngine { respond(body(++requests)) }), readCache = cache)
        try {
            client.execute(RestMethod.GET, path)
            client.execute(RestMethod.GET, "/api/apps/v1/locations")
            cache.withFreshReads { client.execute(RestMethod.GET, path) }
            assertEquals(body(3), assertIs<ApiOutcome.Success>(client.execute(RestMethod.GET, path)).responseBody)
            client.execute(RestMethod.GET, "/api/apps/v1/locations")
            assertEquals(3, requests)
            assertFailsWith<CancellationException> { cache.withFreshReads { throw CancellationException() } }
            client.execute(RestMethod.GET, path); assertEquals(3, requests)
        } finally { client.close() }
    }

    @Test fun failuresAndInvalidBodiesNeverBecomeCachedData() = runBlocking {
        for (status in listOf(200, 401, 429, 503)) {
            var requests = 0
            val client = FloopelRestClient("https://local.example.test", HttpClient(MockEngine {
                requests++; respond("not-json", HttpStatusCode.fromValue(status), headersOf(HttpHeaders.RetryAfter, "60"))
            }), readCache = TravelerReadCache())
            try { repeat(2) { client.execute(RestMethod.GET, path) }; assertEquals(2, requests, "status=$status") }
            finally { client.close() }
        }
    }

    @Test fun authConsentSecurityDeletionAndGameplayAlwaysReadServer() = runBlocking {
        var requests = 0
        val client = FloopelRestClient("https://local.example.test", HttpClient(MockEngine { respond(body(++requests)) }), readCache = TravelerReadCache())
        val routes = listOf("/api/apps/v1/me", "/api/apps/v1/legal/terms", "/api/apps/v1/me/security",
            "/api/apps/v1/me/account-deletion", "/api/apps/v1/gamification/me/progress", "/api/mobile/v1/auth/me")
        try { routes.forEach { route -> repeat(2) { client.execute(RestMethod.GET, route) } }; assertEquals(12, requests) }
        finally { client.close() }
    }

    @Test fun mutationInvalidatesReadsAndLatePreMutationReadCannotRestoreOldData() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var requests = 0
        val client = FloopelRestClient("https://local.example.test", HttpClient(MockEngine { request ->
            val index = ++requests
            if (request.method == HttpMethod.Get && index == 1) { started.complete(Unit); release.await() }
            respond(body(index))
        }), readCache = TravelerReadCache())
        try {
            val old = async { client.execute(RestMethod.GET, path) }
            started.await()
            client.execute(RestMethod.POST, "/api/apps/v1/me/saved-spots", "{}")
            release.complete(Unit); old.await()
            assertEquals(body(3), assertIs<ApiOutcome.Success>(client.execute(RestMethod.GET, path)).responseBody)
            client.execute(RestMethod.GET, path); assertEquals(3, requests)
        } finally { client.close() }
    }

    @Test fun cacheIsBoundedAndLargeResponsesAreNotRetained() = runBlocking {
        val cache = TravelerReadCache(maximumEntries = 2)
        val epoch = cache.generation()
        val keys = (1..3).map { TravelerReadCache.Key("/api/apps/v1/spots/$it", null) }
        keys.forEachIndexed { i, key -> cache.put(key, ApiOutcome.Success(200, body(i)), epoch) }
        assertNull(cache.get(keys[0])); assertNotNull(cache.get(keys[1]))
        cache.put(keys[0], ApiOutcome.Success(200, " ".repeat(262_145)), epoch)
        assertNull(cache.get(keys[0]))
        cache.clear(); cache.put(keys[0], ApiOutcome.Success(200, body(1)), epoch)
        assertNull(cache.get(keys[0]))
    }
}
