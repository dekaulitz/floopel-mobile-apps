package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ExplorerMarkersTest {
    @Test fun viewportFeedIsLeanDeduplicatedAndNeverLoadsAllDetails() = runTest {
        var calls = 0
        val marker = """{"id":"spot","kind":"spot","name":"Museum","category_slug":"museum","geo":{"lat":-6.9,"lng":107.6}}"""
        var data = """{"markers":[$marker,$marker],"truncated":true}"""
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals("/api/apps/v1/map/markers", request.url.encodedPath)
            assertEquals("spot", request.url.parameters["kind"])
            assertEquals("107,-7,108,-6", request.url.parameters["bbox"])
            respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""")
        })
        try {
            val catalog = AppsTravelerCatalog(FloopelRestClient("https://fixture.invalid", client))
            val result = assertIs<CatalogResult.Ready<CatalogMapPage>>(catalog.mapMarkers("107,-7,108,-6")).value
            assertEquals(1,calls); assertEquals(1,result.markers.size); assertTrue(result.truncated)
            assertEquals(GeoPoint(-6.9,107.6), result.markers.single().geo)
            assertNull(result.markers.single().bodyHtml); assertNull(result.markers.single().coverUrl)
            for (bad in listOf("""{"markers":[],"truncated":"false"}""", """{"markers":[{}],"truncated":false}""", """{"markers":[]} """)) {
                data=bad
                assertEquals(CatalogResult.InvalidResponse,catalog.mapMarkers("107,-7,108,-6"))
            }
        } finally { client.close() }
    }
}
