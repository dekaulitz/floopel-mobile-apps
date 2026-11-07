package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.FloopelRestClient
import com.floopel.feature.traveler.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class AppsTravelerStoryAvatarsTest {
    @Test fun actualAuthorPhotoIsNotReplacedByAnUnrelatedTravelersMatchingHandle() = runTest {
        var photo: String? = null
        val client = HttpClient(MockEngine { request ->
            val data = if (request.url.encodedPath.endsWith("/profile-identities"))
                """{"traveler":{"level":3,"avatar_url":"https://fixture.invalid/media/unrelated-traveler.webp"}}"""
            else """[{"id":"story","slug":"story","locations":[],"title":"A journey","type":"article","author":{"profile_id":"admin-profile","username":"traveler","avatar_url":${photo?.let { JsonPrimitive(it) } ?: JsonNull}}}]"""
            respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val rest = FloopelRestClient("https://fixture.invalid", client)
        try {
            val catalog = AppsTravelerCatalog(rest)
            val actual = listOf("https://fixture.invalid/media/actual-author.webp", null).map { source ->
                photo = source
                assertIs<CatalogResult.Ready<List<CatalogEntry>>>(catalog.stories("id", StoryType.ARTICLE, 10)).value.single().story?.authorAvatarUrl
            }
            assertEquals(listOf("https://fixture.invalid/media/actual-author.webp", null), actual)
        } finally { rest.close() }
    }
}
