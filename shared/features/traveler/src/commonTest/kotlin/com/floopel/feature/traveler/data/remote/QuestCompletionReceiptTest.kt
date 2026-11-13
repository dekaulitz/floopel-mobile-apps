package com.floopel.feature.traveler.data.remote

import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.core.network.AppPlatform
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Question and GPS mutations expose the committed receipt without borrowing reward previews. */
class QuestCompletionReceiptTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val participation = """{"id":"$id","status":"completed","quest_title":"Jelajah Jakarta","quest_revision_id":"revision","quest_execution_status":"available","completed_activity_count":1,"total_activity_count":1}"""
    private val receipt = """{"completion_id":"completion","completed_at":"2026-10-25T00:00:00Z","reward_grant":{"id":"grant","completion_id":"completion","status":"granted","xp_amount":500,"badge_id":"badge","badge_effect":"granted"},"badge":{"id":"badge","code":"jakarta","name":"Penjelajah Jakarta","image":{"url":"https://fixture.invalid/media/badge.webp"}}}"""
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun legacyResultsRemainReadableWithoutInventingAReceipt() {
        assertNull(json.decodeFromString<QuestAnswerResult>("""{"outcome":"correct","explanation":null,"participation":$participation}""").completion)
    }

    @Test fun questionAndGpsResultsKeepActualXpBadgeAndResolvedArtwork() = runTest {
        var gps = false
        val client = HttpClient(MockEngine {
            val data = if (gps) """{"proof_attempt":{"id":"proof","status":"accepted","proof_session_id":"$id","activity_id":"$id"},"participation":$participation,"completion":$receipt}"""
                else """{"outcome":"correct","explanation":null,"participation":$participation,"completion":$receipt}"""
            respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
        try {
            val execution = ExecutionCredential("fixture", "Bearer", 600)
            val answer = api.answer(id, id, "text", "Jakarta", execution, id, "id")
            assertEquals(500L, answer.completion?.grant?.xp)
            assertEquals("Penjelajah Jakarta", answer.completion?.badge?.name)
            assertEquals("https://fixture.invalid/media/badge.webp", answer.completion?.badge?.image?.url)
            gps = true
            assertEquals(answer.completion, api.submitProof(id, execution, id).completion)
        } finally { api.close() }
    }

    @Test fun aMismatchedCompletionOrNegativeXpCannotBecomeAnAward() {
        assertFails { json.decodeFromString<QuestCompletionReceipt>(receipt.replace("\"completion_id\":\"completion\",\"status\"", "\"completion_id\":\"other\",\"status\"")) }
        assertFails { json.decodeFromString<QuestCompletionReceipt>(receipt.replace("\"xp_amount\":500", "\"xp_amount\":-1")) }
    }
}
