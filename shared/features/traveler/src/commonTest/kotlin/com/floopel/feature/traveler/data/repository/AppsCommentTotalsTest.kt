package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.FloopelRestClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AppsCommentTotalsTest {
    private fun envelope(items: String) = """{"meta":{"code":"OK"},"error":null,"data":{"items":$items}}"""

    @Test fun countersDeduplicateAndChunkTargetsWithoutRequestingCommentThreads() = runTest {
        val batches = mutableListOf<List<String>>()
        val transport = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine { request ->
            assertEquals("/api/apps/v1/comment-totals/targets/spot", request.url.encodedPath)
            assertEquals(HttpMethod.Get, request.method)
            val ids = request.url.parameters.getAll("target_ids").orEmpty()
            batches += ids
            respond(envelope(ids.joinToString(prefix = "[", postfix = "]") { """{"target_id":"$it","total_comments":7}""" }))
        }))
        try {
            val ids = (1..101).map { "spot-$it" }
            val result = AppsCommentTotals(transport).read("spot", ids + ids.first() + "")
            assertEquals(listOf(100, 1), batches.map(List<String>::size))
            assertEquals(ids.toSet(), result.keys)
            assertTrue(result.values.all { it == 7L })
        } finally { transport.close() }
    }

    @Test fun unavailableOrMalformedCountersStayUnknownInsteadOfBecomingZero() = runTest {
        for ((status, body) in listOf(
            HttpStatusCode.TooManyRequests to "Slow down",
            HttpStatusCode.ServiceUnavailable to "Maintenance",
            HttpStatusCode.OK to "invalid json",
            HttpStatusCode.OK to envelope("""[{"target_id":"spot","total_comments":"2"}]"""),
        )) {
            var calls = 0
            val transport = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine {
                calls++
                respond(body, status, headersOf(HttpHeaders.RetryAfter, "3"))
            }))
            try {
                assertEquals(emptyMap(), AppsCommentTotals(transport).read("spot", listOf("spot")))
                assertEquals(1, calls)
            } finally { transport.close() }
        }
    }

    @Test fun countersIgnoreUnrequestedAndNegativeValuesAndKeepTargetFamiliesSeparate() = runTest {
        val paths = mutableListOf<String>()
        val transport = FloopelRestClient("https://fixture.invalid", HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            val count = if (request.url.encodedPath.endsWith("/spot")) 2 else 8
            respond(envelope("""[{"target_id":"same-id","total_comments":$count},{"target_id":"unknown","total_comments":99},{"target_id":"negative","total_comments":-1}]"""))
        }))
        try {
            val reader = AppsCommentTotals(transport)
            assertEquals(mapOf("same-id" to 2L), reader.read("spot", listOf("same-id", "negative")))
            assertEquals(mapOf("same-id" to 8L), reader.read("itinerary", listOf("same-id")))
            assertEquals(2, paths.distinct().size)
            assertFailsWith<IllegalArgumentException> { reader.read("private", listOf("same-id")) }
            assertEquals(2, paths.size)
        } finally { transport.close() }
    }
}
