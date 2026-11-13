package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.*

class MediaUrlTest {
    @Test fun emulatorMediaUsesOnlyTheAuthorizedLocalApiHostAndProductionNeverDowngrades() {
        val client = HttpClient(MockEngine { error("No request expected") })
        try {
            val local = FloopelRestClient("http://10.0.2.2:8080", client)
            assertEquals("http://10.0.2.2:8080/media/badge.png?signature=opaque", local.resolveMediaUrl("http://localhost:8080/media/badge.png?signature=opaque"))
            assertEquals("https://cdn.invalid/photo.webp", local.resolveMediaUrl("https://cdn.invalid/photo.webp"))
            listOf("http://localhost:9090/media/image", "http://localhost:8080/admin", "http://unsafe.invalid/media/image", "javascript:alert(1)", "file:///private", "https://user:password@cdn.invalid/image", "https://cdn.invalid/im age", "https://cdn.invalid/image\"attribute").forEach {
                assertNull(local.resolveMediaUrl(it), it)
            }
            val production = FloopelRestClient("https://api.invalid", client)
            assertNull(production.resolveMediaUrl("http://localhost:8080/media/image"))
            assertNull(production.resolveMediaUrl(null))
        } finally { client.close() }
    }
}
