package com.floopel.feature.traveler.feature.auth

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.data.remote.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.floopel.feature.traveler.data.repository.NativeTravelerIdentity
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QuestGpsTest {
    private val quest = "00000000-0000-4000-8000-000000000001"
    private val participation = "00000000-0000-4000-8000-000000000002"
    private val activity = "00000000-0000-4000-8000-000000000003"
    private val proof = "00000000-0000-4000-8000-000000000004"
    private fun participation(done: Boolean) = """{"id":"$participation","status":"${if(done) "completed" else "active"}","quest_title":"GPS","quest_revision_id":"revision","quest_execution_status":"available","completed_activity_count":${if(done) 1 else 0},"total_activity_count":1,"activities":[{"activity_id":"$activity","status":"${if(done) "completed" else "available"}"}]}"""
    private inner class Fixture(scope: TestScope, val failSubmit: Boolean = false, val alreadyTaken: Boolean = true) {
        var locationAvailable = true
        var samples = 0
        var starts = 0
        var submits = 0
        var questStarts = 0
        var participationReads = 0
        var key: String? = null
        val api = NativeApi(HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(scope.testScheduler)
            addHandler { request ->
            fun ok(value: String) = respond("""{"error":null,"data":$value}""")
            fun proofData() = """{"id":"$proof","participation_id":"$participation","activity_id":"$activity","status":"active","started_at":"1970-01-01T00:00:00Z","expires_at":"1970-01-01T00:05:00Z","observation_count":$samples,"execution_epoch":1}"""
            when {
                request.url.encodedPath.endsWith("/login") -> ok("""{"tokens":{"token_type":"Bearer","access_token":"identity","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}""")
                request.url.encodedPath.endsWith("/progress") -> ok("""{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}""")
                request.url.encodedPath.endsWith("/gps/me") -> ok("""{"quest_id":"$quest","locations":[],"quest_slug":"gps","status":"${if (alreadyTaken) "active" else "not_completed"}","active_participation":${if (alreadyTaken) participation(false) else "null"}}""")
                request.url.encodedPath.endsWith("/gps") -> ok("""{"id":"$quest","slug":"gps","locations":[],"title":"GPS","description":"Story","availability":"active","execution_status":"available","proof_summary":"GPS","reward_preview":{"xp":100,"badge":null},"quota":null,"activities":[]}""")
                request.url.encodedPath.endsWith("/participations/$participation") -> { participationReads++; ok(participation(false)) }
                request.url.encodedPath.endsWith("/start") -> { questStarts++; ok(participation(false)) }
                request.url.encodedPath.endsWith("/activities/$activity") -> ok("""{"activity":{"id":"$activity","title":"Visit","type":"gps","ordinal":1},"instructions":"Stay here","options":[],"answer_type":null,"explanation":null}""")
                request.url.encodedPath.endsWith("/challenges") -> ok("""{"challenge_id":"challenge","platform":"android","challenge":"challenge","expires_at":"2099-01-01T00:00:00Z"}""")
                request.url.encodedPath.endsWith("/credentials") -> ok("""{"access_token":"execution","token_type":"Bearer","expires_in":600}""")
                request.url.encodedPath.endsWith("/proof-sessions") -> { starts++; ok(proofData()) }
                request.url.encodedPath.endsWith("/observations") -> { samples++; ok(proofData()) }
                request.url.encodedPath.endsWith("/submit") -> {
                    submits++
                    if (key == null) key = request.headers["Idempotency-Key"] else assertEquals(key, request.headers["Idempotency-Key"])
                    if (failSubmit && submits == 1) respond("maintenance", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "60"))
                    else ok("""{"proof_attempt":{"id":"attempt","status":"verified","proof_session_id":"$proof","activity_id":"$activity"},"participation":${participation(true)},"completion":null}""")
                }
                else -> error("Unexpected request ${request.url.encodedPath}")
            }
        } })), "https://fixture.invalid", AppPlatform.ANDROID)
        val session = TravelerSession(api, object : RefreshCredentialStore {
            override fun read(): String? = null
            override fun write(credential: String) {}
            override fun clear() {}
        }, "en", { scope.testScheduler.currentTime }, object : QuestAttestation {
            override val available = true
            override suspend fun androidIntegrityToken(challenge: String) = "proof"
        }, object : QuestLocationSource {
            override suspend fun current(): QuestLocation {
                if (!locationAvailable) throw QuestLocationUnavailable()
                return QuestLocation(0.0, 0.0, 5.0, "1970-01-01T00:00:00Z")
            }
        })
        suspend fun prepare() {
            session.login("fixture@example.invalid", "password")
            session.openQuest("gps")
            if (alreadyTaken) session.continueQuest() else session.takeQuest()
            session.openActivity(activity)
        }
    }

    @Test fun gpsProjectionAndPermissionRetryKeepTheCorrectActivityMode() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.prepare()
            val identity = NativeTravelerIdentity(fixture.session, fixture.api)
            assertEquals(QuestActivityKind.GPS, identity.view.first().question?.kind)
            fixture.locationAvailable = false
            fixture.session.collectGps()
            assertTrue(identity.view.first().locationUnavailable)
            fixture.locationAvailable = true
            val retry = launch { fixture.session.collectGps() }
            runCurrent()
            assertFalse(identity.view.first().locationUnavailable)
            assertTrue(identity.view.first().gpsCollecting)
            fixture.session.onBackground(); retry.join()
        } finally { fixture.api.close() }
    }

    @Test fun submitStopsCollectionAndUsesServerProgress() = runTest {
        val fixture = Fixture(this)
        fixture.prepare()
        val collecting = launch { fixture.session.collectGps() }
        runCurrent()
        assertTrue(fixture.session.view.value.gpsCollecting)
        assertEquals(0, fixture.session.view.value.participation?.completedCount)
        fixture.session.submitGps()
        collecting.join()
        assertEquals("verified", fixture.session.view.value.gpsResult?.attempt?.status)
        assertEquals("completed", fixture.session.view.value.participation?.status)
        assertFalse(fixture.session.view.value.gpsCollecting)
        advanceTimeBy(30000)
        assertEquals(1, fixture.samples)
    }

    @Test fun backgroundStopsSamplesAndResumeReusesUnexpiredSession() = runTest {
        val fixture = Fixture(this)
        fixture.prepare()
        val collecting = launch { fixture.session.collectGps() }
        runCurrent()
        fixture.session.onBackground()
        collecting.join()
        advanceTimeBy(30000)
        assertEquals(1, fixture.samples)
        assertFalse(fixture.session.view.value.gpsCollecting)
        val resumed = launch { fixture.session.collectGps() }
        runCurrent()
        assertEquals(1, fixture.starts)
        assertEquals(2, fixture.samples)
        fixture.session.onBackground()
        resumed.join()
    }

    @Test fun failedSubmitRequiresExplicitRetryWithSameKey() = runTest {
        val fixture = Fixture(this, failSubmit = true, alreadyTaken = false)
        fixture.prepare()
        assertEquals(1, fixture.questStarts)
        assertTrue(fixture.session.view.value.progress!!.activeParticipations.isEmpty())
        val collecting = launch { fixture.session.collectGps() }
        runCurrent()
        fixture.session.submitGps()
        collecting.join()
        assertTrue(fixture.session.view.value.gpsSubmissionPending)
        assertEquals(1, fixture.submits)
        advanceTimeBy(60001)
        val frozenProof = fixture.session.view.value.gpsProof
        val frozenKey = fixture.key
        val reads = fixture.participationReads
        fixture.session.continuePassportQuest(participation)
        assertEquals(reads + 1, fixture.participationReads)
        assertNull(fixture.session.view.value.failure)
        assertEquals(1, fixture.questStarts)
        fixture.session.openActivity(activity)
        assertEquals(frozenProof, fixture.session.view.value.gpsProof)
        assertTrue(fixture.session.view.value.gpsSubmissionPending)
        assertEquals(frozenKey, fixture.key)
        assertEquals(1, fixture.submits) // Reconcile the reader, without replaying GPS submit.
        fixture.session.collectGps()
        assertEquals(1, fixture.starts)
        fixture.session.submitGps()
        assertEquals(2, fixture.submits)
        assertFalse(fixture.session.view.value.gpsSubmissionPending)
    }

    @Test fun expiryStopsCollectionWithoutAwardingProgress() = runTest {
        val fixture = Fixture(this)
        fixture.prepare()
        val collecting = launch { fixture.session.collectGps() }
        runCurrent()
        advanceTimeBy(300001)
        collecting.join()
        val samples = fixture.samples
        advanceTimeBy(60000)
        assertEquals(samples, fixture.samples)
        assertFalse(fixture.session.view.value.gpsCollecting)
        assertNull(fixture.session.view.value.gpsResult)
        assertEquals(0, fixture.session.view.value.participation?.completedCount)
    }

    @Test fun logoutCancelsCollectionAndClearsPrivateProof() = runTest {
        val fixture = Fixture(this)
        fixture.prepare()
        val collecting = launch { fixture.session.collectGps() }
        runCurrent()
        fixture.session.logout()
        collecting.join()
        advanceTimeBy(30000)
        assertEquals(1, fixture.samples)
        assertFalse(fixture.session.view.value.signedIn)
        assertNull(fixture.session.view.value.gpsProof)
    }
}
