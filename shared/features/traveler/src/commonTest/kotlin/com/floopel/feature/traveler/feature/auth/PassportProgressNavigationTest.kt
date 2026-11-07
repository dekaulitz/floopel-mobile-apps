package com.floopel.feature.traveler.feature.auth

import com.floopel.feature.traveler.core.network.AppPlatform
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.domain.repository.RefreshCredentialStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Instant

/** Initial Home balance reads must never supersede a requested private Quest destination. */
class PassportProgressNavigationTest {
    @Test
    fun passiveProgressCannotDiscardThePendingLinkedQuest() = runTest {
        Fixture().use { fixture ->
            fixture.session.login("fixture@example.invalid", "password")
            val quest = launch { fixture.session.openQuest("public") }
            fixture.ownerStarted.await()
            val progress = launch { fixture.session.refreshPassportProgress() }
            fixture.progressStarted.await()
            try {
                fixture.ownerResponse.complete(Unit)
                quest.join()
                fixture.assertQuest()
                assertFalse(fixture.session.view.value.busy)
            } finally {
                fixture.progressResponse.complete(Unit)
                quest.join()
                progress.join()
            }
            fixture.assertQuest()
            assertEquals(10L, fixture.session.view.value.progress?.xpTotal)
        }
    }

    @Test
    fun progressFinishingFirstCannotClearTheNavigationLoadingState() = runTest {
        Fixture().use { fixture ->
            fixture.session.login("fixture@example.invalid", "password")
            val quest = launch { fixture.session.openQuest("public") }
            fixture.ownerStarted.await()
            val progress = launch { fixture.session.refreshPassportProgress() }
            fixture.progressStarted.await()
            try {
                fixture.progressResponse.complete(Unit)
                progress.join()
                assertTrue(fixture.session.view.value.busy)
                assertEquals(TravelerScreen.QUEST_DETAIL, fixture.session.view.value.screen)
            } finally { fixture.ownerResponse.complete(Unit); quest.join(); progress.join() }
            fixture.assertQuest()
            assertEquals(10L, fixture.session.view.value.progress?.xpTotal)
        }
    }

    @Test
    fun disposingTheInitialHomeReadCannotCancelItsQuestDestination() = runTest {
        Fixture().use { fixture ->
            fixture.session.login("fixture@example.invalid", "password")
            val quest = launch { fixture.session.openQuest("public") }
            fixture.ownerStarted.await()
            val progress = launch { fixture.session.refreshPassportProgress() }
            fixture.progressStarted.await()
            try {
                progress.cancelAndJoin()
                assertTrue(fixture.session.view.value.busy)
            } finally { fixture.ownerResponse.complete(Unit); quest.join() }
            fixture.assertQuest()
        }
    }

    @Test
    fun aLateProjectionCannotReplaceTheResultOfNewerNavigation() = runTest {
        Fixture().use { fixture ->
            fixture.session.login("fixture@example.invalid", "password")
            val progress = launch { fixture.session.refreshPassportProgress() }
            fixture.progressStarted.await()
            try {
                fixture.ownerResponse.complete(Unit)
                fixture.session.openQuest("public")
            } finally { fixture.progressResponse.complete(Unit); progress.join() }
            fixture.assertQuest()
            assertEquals(0L, fixture.session.view.value.progress?.xpTotal)
        }
    }

    @Test
    fun theNewestProjectionWinsWithoutReplayingEitherRequest() = runTest {
        Fixture().use { fixture ->
            val newestStarted = CompletableDeferred<Unit>()
            val newestResponse = CompletableDeferred<Unit>()
            fixture.progressValue = { count ->
                when (count) {
                    2 -> { fixture.progressStarted.complete(Unit); fixture.progressResponse.await(); 10 }
                    3 -> { newestStarted.complete(Unit); newestResponse.await(); 20 }
                    else -> 0
                }
            }
            fixture.session.login("fixture@example.invalid", "password")
            val old = launch { fixture.session.refreshPassportProgress() }
            fixture.progressStarted.await()
            val newest = launch { fixture.session.refreshPassportProgress() }
            newestStarted.await()
            try {
                newestResponse.complete(Unit)
                newest.join()
                assertEquals(20L, fixture.session.view.value.progress?.xpTotal)
            } finally { fixture.progressResponse.complete(Unit); old.join(); newest.join() }
            assertEquals(20L, fixture.session.view.value.progress?.xpTotal)
            assertEquals(3, fixture.progressReads)
        }
    }

    @Test
    fun logoutAndAccountSwitchRejectThePreviousPrivateProjection() = runTest {
        for (signInAgain in listOf(false, true)) Fixture().use { fixture ->
            fixture.session.login("first@example.invalid", "password")
            val progress = launch { fixture.session.refreshPassportProgress() }
            fixture.progressStarted.await()
            try {
                fixture.session.logout()
                if (signInAgain) fixture.session.login("second@example.invalid", "password")
            } finally { fixture.progressResponse.complete(Unit); progress.join() }
            assertEquals(signInAgain, fixture.session.view.value.signedIn)
            assertEquals(if (signInAgain) 0L else null, fixture.session.view.value.progress?.xpTotal)
            assertNull(fixture.session.view.value.questOwner)
        }
    }

    @Test
    fun anUnrelatedProjectionFailureCannotClearPendingNavigation() = runTest {
        Fixture().use { fixture ->
            fixture.failHeldProgress = true
            fixture.session.login("fixture@example.invalid", "password")
            val quest = launch { fixture.session.openQuest("public") }
            fixture.ownerStarted.await()
            val progress = launch { fixture.session.refreshPassportProgress() }
            fixture.progressStarted.await()
            try {
                fixture.progressResponse.complete(Unit)
                progress.join()
                assertTrue(fixture.session.view.value.busy)
                assertNull(fixture.session.view.value.failure)
            } finally { fixture.ownerResponse.complete(Unit); quest.join(); progress.join() }
            fixture.assertQuest()
        }
    }

    @Test
    fun lateProjectionSuccessCannotClearTheDestinationFailure() = runTest {
        Fixture().use { fixture ->
            fixture.failOwner = true
            fixture.session.login("fixture@example.invalid", "password")
            val quest = launch { fixture.session.openQuest("public") }
            fixture.ownerStarted.await()
            val progress = launch { fixture.session.refreshPassportProgress() }
            fixture.progressStarted.await()
            try {
                fixture.ownerResponse.complete(Unit)
                quest.join()
                assertEquals(403, fixture.session.view.value.failure?.status)
            } finally { fixture.progressResponse.complete(Unit); quest.join(); progress.join() }
            assertEquals(403, fixture.session.view.value.failure?.status)
            assertEquals(10L, fixture.session.view.value.progress?.xpTotal)
        }
    }

    @Test
    fun lateProjectionFailureCannotReplaceTheDestinationFailure() = runTest {
        Fixture().use { fixture ->
            fixture.failOwner = true
            fixture.failHeldProgress = true
            fixture.session.login("fixture@example.invalid", "password")
            val quest = launch { fixture.session.openQuest("public") }
            fixture.ownerStarted.await()
            val progress = launch { fixture.session.refreshPassportProgress() }
            fixture.progressStarted.await()
            try {
                fixture.ownerResponse.complete(Unit)
                quest.join()
                assertEquals(403, fixture.session.view.value.failure?.status)
            } finally { fixture.progressResponse.complete(Unit); quest.join(); progress.join() }
            assertEquals(403, fixture.session.view.value.failure?.status)
            assertEquals(0L, fixture.session.view.value.progress?.xpTotal)
        }
    }

    @Test
    fun anUnauthorizedProjectionRotatesOnceWithoutDiscardingTheDestination() = runTest {
        Fixture().use { fixture ->
            fixture.rejectHeldProgress = true
            fixture.progressValue = { count ->
                when (count) {
                    2 -> { fixture.progressStarted.complete(Unit); fixture.progressResponse.await(); 10 }
                    3 -> 30
                    else -> 0
                }
            }
            fixture.session.login("fixture@example.invalid", "password")
            val quest = launch { fixture.session.openQuest("public") }
            fixture.ownerStarted.await()
            val progress = launch { fixture.session.refreshPassportProgress() }
            fixture.progressStarted.await()
            try {
                fixture.progressResponse.complete(Unit)
                progress.join()
                assertTrue(fixture.session.view.value.busy)
                assertEquals(1, fixture.refreshes)
                assertEquals(30L, fixture.session.view.value.progress?.xpTotal)
            } finally { fixture.ownerResponse.complete(Unit); quest.join(); progress.join() }
            fixture.assertQuest()
            assertEquals(3, fixture.progressReads)
        }
    }

    @Test
    fun anExpiredCredentialIsRenewedBeforeReadingTheProjection() = runTest {
        Fixture().use { fixture ->
            fixture.session.login("fixture@example.invalid", "password")
            fixture.clock = Instant.parse("2100-01-01T00:00:00Z").toEpochMilliseconds()
            fixture.progressResponse.complete(Unit)
            fixture.session.refreshPassportProgress()
            assertEquals(1, fixture.refreshes)
            assertEquals(10L, fixture.session.view.value.progress?.xpTotal)
            assertTrue(fixture.session.view.value.signedIn)
        }
    }

    @Test
    fun aSignedOutProjectionRefreshDoesNotIssueRequests() = runTest {
        Fixture().use { fixture ->
            fixture.session.refreshPassportProgress()
            assertEquals(0, fixture.progressReads)
            assertFalse(fixture.session.view.value.signedIn)
            assertNull(fixture.session.view.value.progress)
        }
    }

    private class Fixture : AutoCloseable {
        val ownerStarted = CompletableDeferred<Unit>()
        val ownerResponse = CompletableDeferred<Unit>()
        val progressStarted = CompletableDeferred<Unit>()
        val progressResponse = CompletableDeferred<Unit>()
        var progressReads = 0
            private set
        var failHeldProgress = false
        var failOwner = false
        var rejectHeldProgress = false
        var clock = 0L
        var refreshes = 0
            private set
        var progressValue: suspend (Int) -> Int = { count ->
            if (count == 2) { progressStarted.complete(Unit); progressResponse.await(); 10 } else 0
        }
        private val client = HttpClient(MockEngine { request ->
            fun ok(data: String) = respond("""{"meta":{"request_id":"$questId","timestamp":0,"code":"OK"},"error":null,"data":$data}""")
            when {
                request.url.encodedPath.endsWith("/login") -> ok("""{"tokens":{"token_type":"Bearer","access_token":"identity","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}""")
                request.url.encodedPath.endsWith("/refresh") -> {
                    refreshes++
                    ok("""{"token_type":"Bearer","access_token":"replacement","access_expires_at":"2200-01-01T00:00:00Z","refresh_token":"replacement-refresh","refresh_expires_at":"2200-02-01T00:00:00Z"}""")
                }
                request.url.encodedPath.endsWith("/logout") -> respond("", HttpStatusCode.NoContent)
                request.url.encodedPath.endsWith("/progress") -> {
                    val count = ++progressReads
                    val value = progressValue(count)
                    if (count == 2 && rejectHeldProgress) respond("{}", HttpStatusCode.Unauthorized)
                    else if (count == 2 && failHeldProgress) respond("{}", HttpStatusCode.InternalServerError)
                    else ok(passport(value))
                }
                request.url.encodedPath.endsWith("/public/me") -> {
                    ownerStarted.complete(Unit)
                    ownerResponse.await()
                    if (failOwner) respond("{}", HttpStatusCode.Forbidden)
                    else ok("""{"quest_id":"$questId","locations":[],"quest_slug":"public","status":"not_completed","is_hidden":false}""")
                }
                request.url.encodedPath.endsWith("/public") -> ok("""{"id":"$questId","slug":"public","locations":[],"title":"Public Quest","description":"Questions","availability":"active","execution_status":"available","proof_summary":"Questions","reward_preview":{"xp":100,"badge":null},"quota":null,"activities":[]}""")
                else -> error("Unexpected fixture path")
            }
        })
        val session = TravelerSession(NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID), object : RefreshCredentialStore {
            private var credential: String? = null
            override fun read() = credential
            override fun write(credential: String) { this.credential = credential }
            override fun clear() { credential = null }
        }, "en", { clock })

        fun assertQuest() {
            val state = session.view.value
            assertEquals(TravelerScreen.QUEST_DETAIL, state.screen)
            assertEquals(questId, state.questDetail?.id)
            assertEquals(questId, state.questOwner?.questId)
            assertEquals("not_completed", state.questOwner?.status)
            assertNull(state.failure)
        }

        override fun close() = client.close()
    }

    companion object {
        private const val questId = "00000000-0000-4000-8000-000000000001"
        private fun passport(xp: Int) = """{"xp_total":$xp,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
    }
}
