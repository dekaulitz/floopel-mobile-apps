package com.floopel.feature.traveler.data.remote

import com.floopel.feature.traveler.core.network.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

class QuestTravelerProjectionTest {
    private val base =
        """{"id":"quest","slug":"city","locations":[],"title":"City","description":"Sample","availability":"active","execution_status":"available","proof_summary":"Sample","reward_preview":{"xp":100,"badge":null},"quota":null,"activities":[]}"""

    @Test
    fun tenPreviewIdentitiesPreserveTheAuthoritativeTotalAndRejectInconsistentCounts() = runTest {
        val travelers = (1..10).joinToString(prefix = "[", postfix = "]") {
            """{"username":"sample$it","display_name":"Sample $it"}"""
        }
        var total = "100010"
        val api = NativeApi(HttpClient(MockEngine {
            respond("""{"meta":{"code":"OK"},"error":null,"data":${base.dropLast(1)},"travelers":$travelers,"traveler_count":$total}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }), "https://fixture.invalid", AppPlatform.ANDROID)
        try {
            val quest = api.quest("city", "en")
            assertEquals(10, quest.travelers.size)
            assertEquals(100010L, quest.travelerCount)
            for (invalid in listOf("9", "-1", "1.5", "\"100010\"")) {
                total = invalid
                assertEquals(502, assertFailsWith<ApiFailure> { api.quest("city", "en") }.status)
            }
        } finally { api.close() }
    }

    @Test
    fun oldDetailDefaultsToEmptyAndPublicPreviewPreservesCanonicalHandles() = runTest {
        var body = base
        val client =
            HttpClient(
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/profile-identities")) return@MockEngine respond(
                        """{"meta":{"code":"OK"},"error":null,"data":{"sample_nearby_legok_01":{"level":3,"avatar_url":"https://fixture.invalid/media/fixture-avatar.webp"}}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    assertEquals("10", request.url.parameters["traveler_limit"])
                    respond(
                        """{"meta":{"code":"OK"},"error":null,"data":$body}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            )
        try {
            val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
            assertTrue(api.quest("city", "en").travelers.isEmpty())
            assertNull(api.quest("city", "en").travelerCount)
            body =
                base.dropLast(1) +
                    """, "travelers":[{"username":"sample_nearby_legok_01","display_name":"Sample Legok 01"}]}"""
            assertEquals(
                "sample_nearby_legok_01",
                api.quest("city", "en").travelers.single().username,
            )
            assertEquals("https://fixture.invalid/media/fixture-avatar.webp", api.quest("city", "en").travelers.single().avatarUrl)
        } finally {
            client.close()
        }
    }

    @Test
    fun malformedDuplicateAndOversizedPreviewsFailInsteadOfDisplayingInventedIdentities() =
        runTest {
            var entries = "[]"
            val client =
                HttpClient(
                    MockEngine {
                        respond(
                            """{"meta":{"code":"OK"},"error":null,"data":${base.dropLast(1)},"travelers":$entries}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                )
            try {
                val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
                for (invalid in
                    listOf(
                        """[{"username":"","display_name":"Sample"}]""",
                        """[{"username":"sample","display_name":"Sample"},{"username":"sample","display_name":"Other"}]""",
                        (1..11).joinToString(prefix = "[", postfix = "]") {
                            """{"username":"sample$it","display_name":"Sample $it"}"""
                        },
                    )) {
                    entries = invalid
                    val failure = assertFailsWith<ApiFailure> { api.quest("city", "en") }
                    assertEquals(502, failure.status)
                }
            } finally {
                client.close()
            }
        }
}
