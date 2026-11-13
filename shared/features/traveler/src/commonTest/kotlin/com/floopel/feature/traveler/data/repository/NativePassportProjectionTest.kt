package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.AppPlatform
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.domain.repository.RefreshCredentialStore
import com.floopel.feature.traveler.feature.auth.TravelerSession
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NativePassportProjectionTest {
    @Test fun preservesStableBadgeIdsAndBoundedOwnerCompletionsWithoutInventingSharePermission() = runTest {
        val client = HttpClient(MockEngine { request ->
            val data = if (request.url.encodedPath.endsWith("/login"))
                """{"tokens":{"token_type":"Bearer","access_token":"fixture","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"fixture-refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
            else if (request.url.encodedPath.endsWith("/progress"))
                """{"xp_total":1250,"level":{"level":8,"code":"explorer","name":"Penjelajah","xp_floor":1000,"xp_to_next_level":750,"policy_revision":1},"badges":[{"id":"badge-1","code":"earned","name":"Badge simulasi","image":{"url":"https://fixture.invalid/badge.svg"}}],"recent_completions":[{"completion_id":"completion-1","quest_title":"Quest simulasi","quest_slug":"quest-simulasi","share_available":true,"reward_status":"awarded"},{"completion_id":"completion-2","quest_title":"Riwayat lama"}]}"""
            else error("Unexpected fixture request")
            respond("""{"meta":{"request_id":"fixture","timestamp":0,"code":"OK"},"error":null,"data":$data}""")
        })
        try {
            val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
            val session = TravelerSession(api, object : RefreshCredentialStore {
                private var value: String? = null
                override fun read() = value
                override fun write(credential: String) { value = credential }
                override fun clear() { value = null }
            }, "id")
            session.login("fixture@example.invalid", "fixture-password")
            val passport = assertNotNull(NativeTravelerIdentity(session, api).view.first().passport)
            assertEquals(1250L, passport.xp); assertEquals(8, passport.level)
            assertEquals("badge-1", passport.badges.single().id)
            assertEquals("https://fixture.invalid/badge.svg", passport.badges.single().imageUrl)
            val complete = passport.completions.first()
            assertEquals("completion-1", complete.id); assertEquals("quest-simulasi", complete.slug)
            assertEquals(true, complete.shareAvailable); assertEquals("awarded", complete.rewardStatus)
            val legacy = passport.completions.last()
            assertNull(legacy.slug); assertNull(legacy.shareAvailable); assertNull(legacy.rewardStatus)
        } finally { client.close() }
    }
}
