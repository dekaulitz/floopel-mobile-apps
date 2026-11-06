package com.floopel.feature.traveler.data.remote

import com.floopel.feature.traveler.core.network.*

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class QuestBadgeMediaTest {
    @Test fun badgeMediaUsesExistingOriginPolicyAndNeverDowngradesRemoteHttp() = runTest {
        for ((url, expected) in listOf(
            "http://localhost:8084/media/badge.webp" to "http://10.0.2.2:8084/media/badge.webp",
            "https://cdn.example.test/badge.webp" to "https://cdn.example.test/badge.webp",
            "http://localhost:8080/media/badge.webp" to null,
            "http://remote.example.test/media/badge.webp" to null,
        )) {
            val payload = """{"meta":{"code":"OK"},"error":null,"data":{"id":"quest","slug":"test","locations":[],"title":"Test","description":"Test","availability":"active","execution_status":"available","proof_summary":"Visit","reward_preview":{"xp":100,"badge":{"id":"badge","code":"test","name":"Test","image":{"url":"$url","width":160,"height":160}}},"quota":null,"activities":[]}}"""
            val api = NativeApi(HttpClient(MockEngine { respond(payload, headers = headersOf("Content-Type", "application/json")) }), "http://10.0.2.2:8084", AppPlatform.ANDROID)
            try { assertEquals(expected, api.quest("test", "en").reward.badge?.image?.url) }
            finally { api.close() }
        }
    }
}
