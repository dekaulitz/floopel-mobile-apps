package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class StoryDetailProjectionTest {
    @Test fun spotKeepsVisitContextAndCollectionNavigationWithoutAnotherRequest() = runTest {
        val client = HttpClient(MockEngine {
            respond("""{"meta":{"code":"OK"},"error":null,"data":{"id":"spot","slug":"museum","name":"Museum","category_label":"Heritage","typical_duration_min":90,"tags":["history"],"visit_notes":"Use the public entrance.","collections":[{"collection":{"id":"collection","slug":"city-heritage","locations":[],"title":"City heritage"},"editorial_note":null}],"nearby":[]}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val entry = assertIs<CatalogResult.Ready<CatalogEntry>>(AppsTravelerCatalog(FloopelRestClient("https://fixture.invalid", client)).detail(CatalogEntry("spot", "museum", "Museum", null, CatalogKind.SPOT, null), "en")).value
            assertEquals(90, entry.visitMinutes)
            assertEquals("Heritage", entry.category)
            assertEquals(listOf("history"), entry.tags)
            assertEquals("Use the public entrance.", entry.visitNotes)
            assertEquals("city-heritage", entry.collections.single().slug)
            assertEquals(CatalogKind.COLLECTION, entry.collections.single().kind)
        } finally { client.close() }
    }
    @Test fun storyKeepsAuthorMetadataFeaturedPlacesAndSafeMediaWithoutTreatingInlineGeoAsAnEntity() = runTest {
        val client = HttpClient(MockEngine {
            respond("""{"meta":{"code":"OK"},"error":null,"data":{"id":"story","slug":"culture-story","locations":[],"title":"Culture story","type":"article","author":{"username":"author","profile_id":"opaque"},"published_at":"2026-10-16T00:00:00Z","reading_time_minutes":5,"tags":[{"slug":"culture","label":"Culture"}],"body_html":"<h2>Heading</h2><p>Text</p><img src=\"http://localhost:8080/media/story.png\"><img src=\"http://unsafe.invalid/image\">","cover_hero_image":{"kind":"image","alt":"Culture","variants":{"medium":{"url":"http://localhost:8080/media/story.png"}}},"media":[{"kind":"image","alt":"Culture","variants":{"medium":{"url":"http://localhost:8080/media/story.png"}}},{"kind":"image","alt":"Museum","variants":{"medium":{"url":"https://cdn.invalid/museum.png"}}}],"attachments":[{"target":"spot","spot_id":"museum","slug":"museum","label":"Museum"},{"target":"location","location_id":"city","slug":"city","label":"City"},{"target":"geo_point","geo_lat":-6.9,"geo_lng":107.6,"label":"Inline point"}]}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val catalog = AppsTravelerCatalog(FloopelRestClient("http://10.0.2.2:8080", client))
            val detail = assertIs<CatalogResult.Ready<CatalogEntry>>(catalog.detail(CatalogEntry("story", "culture-story", "Culture story", null, CatalogKind.STORY, null), "en")).value
            assertEquals("author", detail.story?.authorUsername)
            assertEquals(5, detail.story?.readingMinutes)
            assertEquals(listOf("Culture"), detail.story?.tags)
            assertEquals(listOf(CatalogKind.SPOT, CatalogKind.LOCATION), detail.related.map { it.kind })
            assertEquals(2, detail.photos.size)
            assertEquals("http://10.0.2.2:8080/media/story.png", detail.coverUrl)
            assertTrue(detail.bodyHtml!!.contains("http://10.0.2.2:8080/media/story.png"))
            assertFalse(detail.bodyHtml.contains("unsafe.invalid"))
        } finally { client.close() }
    }
}
