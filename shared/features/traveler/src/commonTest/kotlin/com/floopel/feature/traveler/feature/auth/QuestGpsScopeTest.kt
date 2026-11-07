package com.floopel.feature.traveler.feature.auth

import com.floopel.feature.traveler.core.network.AppPlatform
import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.data.repository.NativeTravelerIdentity
import com.floopel.feature.traveler.domain.model.QuestLocation
import com.floopel.feature.traveler.domain.repository.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Production Session/mapper; transport, attestation and coordinates are test-only doubles. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class QuestGpsScopeTest {
    private val quest = "00000000-0000-4000-8000-000000000101"
    private val participation = "00000000-0000-4000-8000-000000000102"
    private val otherParticipation = "00000000-0000-4000-8000-000000000103"
    private val activityA = "00000000-0000-4000-8000-000000000104"
    private val activityB = "00000000-0000-4000-8000-000000000105"
    private val proofA = "00000000-0000-4000-8000-000000000106"
    private val proofB = "00000000-0000-4000-8000-000000000107"
    private val otherProof = "00000000-0000-4000-8000-000000000108"

    private inner class Fixture(scope: TestScope, val outcome: String, val failFirstSubmit: Boolean = false) {
        val started = mutableListOf<Pair<String, String>>()
        val submitKeys = mutableListOf<String?>()
        val submittedProofs = mutableListOf<String>()
        val verified = mutableSetOf<Pair<String, String>>()
        var activityReads = 0
        private val targets = mutableMapOf<String, Pair<String, String>>()
        private fun participationData(id: String): String {
            val done = verified.count { it.first == id }
            fun status(activity: String) = if (id to activity in verified) "completed" else "available"
            return """{"id":"$id","status":"active","quest_title":"GPS fixture","quest_revision_id":"revision","quest_execution_status":"available","completed_activity_count":$done,"total_activity_count":2,"activities":[{"activity_id":"$activityA","status":"${status(activityA)}"},{"activity_id":"$activityB","status":"${status(activityB)}"}]}"""
        }
        private fun proofData(id: String): String {
            val target = requireNotNull(targets[id])
            return """{"id":"$id","participation_id":"${target.first}","activity_id":"${target.second}","status":"active","started_at":"1970-01-01T00:00:00Z","expires_at":"1970-01-01T00:05:00Z","observation_count":1,"execution_epoch":1}"""
        }
        val api = NativeApi(HttpClient(MockEngine(MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(scope.testScheduler)
            addHandler { request ->
                val path = request.url.encodedPath
                fun ok(data: String) = respond("""{"error":null,"data":$data}""")
                val p = if (path.contains(otherParticipation) || path.contains("gps-two")) otherParticipation else participation
                val slug = if (p == otherParticipation) "gps-two" else "gps"
                when {
                    path.endsWith("/login") -> ok("""{"tokens":{"token_type":"Bearer","access_token":"fixture-identity","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"fixture-refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}}""")
                    path.endsWith("/progress") -> ok("""{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}""")
                    path.endsWith("/me") -> ok("""{"quest_id":"$quest","locations":[],"quest_slug":"$slug","status":"active","active_participation":${participationData(p)}}""")
                    path.endsWith("/gps") || path.endsWith("/gps-two") -> ok("""{"id":"$quest","slug":"$slug","locations":[],"title":"GPS fixture","description":"Fixture","availability":"active","execution_status":"available","proof_summary":"GPS","reward_preview":{"xp":100,"badge":null},"quota":null,"activities":[]}""")
                    path.endsWith("/participations/$p") -> ok(participationData(p))
                    path.endsWith("/activities/$activityA") || path.endsWith("/activities/$activityB") -> {
                        activityReads++
                        val a = path.substringAfterLast('/')
                        ok("""{"activity":{"id":"$a","title":"GPS fixture","type":"gps","ordinal":1},"instructions":"Fixture only","options":[],"answer_type":null,"explanation":null}""")
                    }
                    path.endsWith("/challenges") -> ok("""{"challenge_id":"challenge","platform":"android","challenge":"challenge","expires_at":"2099-01-01T00:00:00Z"}""")
                    path.endsWith("/credentials") -> ok("""{"access_token":"fixture-execution","token_type":"Bearer","expires_in":600}""")
                    path.endsWith("/proof-sessions") -> {
                        val a = Json.parseToJsonElement((request.body as TextContent).text).jsonObject.getValue("activity_id").jsonPrimitive.content
                        val id = if (p == otherParticipation) otherProof else if (a == activityA) proofA else proofB
                        started += p to a; targets[id] = p to a; ok(proofData(id))
                    }
                    path.endsWith("/observations") -> ok(proofData(path.substringBeforeLast('/').substringAfterLast('/')))
                    path.endsWith("/submit") -> {
                        submitKeys += request.headers["Idempotency-Key"]
                        val id = path.substringBeforeLast('/').substringAfterLast('/')
                        submittedProofs += id
                        val target = requireNotNull(targets[id])
                        if (failFirstSubmit && submitKeys.size == 1) respond("maintenance", HttpStatusCode.ServiceUnavailable, headersOf("Retry-After", "1"))
                        else {
                            if (outcome == "verified") verified += target
                            ok("""{"proof_attempt":{"id":"attempt","status":"$outcome","proof_session_id":"$id","activity_id":"${target.second}"},"participation":${participationData(target.first)},"completion":null}""")
                        }
                    }
                    else -> error("Unexpected fixture request $path")
                }
            }
        })), "https://fixture.invalid", AppPlatform.ANDROID)
        val session = TravelerSession(api, object : RefreshCredentialStore {
            override fun read(): String? = null
            override fun write(credential: String) {}
            override fun clear() {}
        }, "en", { scope.testScheduler.currentTime }, object : QuestAttestation {
            override val available = true
            override suspend fun androidIntegrityToken(challenge: String) = "fixture-proof"
        }, object : QuestLocationSource {
            override suspend fun current() = QuestLocation(0.0, 0.0, 5.0, "1970-01-01T00:00:00Z")
        })
        val identity = NativeTravelerIdentity(session, api)
        suspend fun prepare() {
            session.login("fixture@example.invalid", "fixture-password")
            session.openQuest("gps"); session.continueQuest(); session.openActivity(activityA)
        }
    }

    @Test fun verifiedResultIsBoundToItsActivity() = checkActivityScope("verified")
    @Test fun rejectedResultIsBoundToItsActivity() = checkActivityScope("rejected")
    @Test fun expiredResultIsBoundToItsActivity() = checkActivityScope("expired")

    private fun checkActivityScope(outcome: String) = runTest {
        val f = Fixture(this, outcome)
        try {
            f.prepare()
            val collecting = launch { f.session.collectGps() }; runCurrent()
            f.session.submitGps(); collecting.join()
            assertEquals(outcome, f.identity.view.first().gpsOutcome)
            f.session.continuePassportQuest(participation)
            assertNull(f.identity.view.first().gpsOutcome) // No current activity on the progress reader.
            f.session.openActivity(activityB)
            assertEquals(activityB, f.identity.view.first().question?.id); assertNull(f.identity.view.first().gpsOutcome)
            f.session.openActivity(activityA)
            assertEquals(outcome, f.identity.view.first().gpsOutcome) // Revisiting the same target retains its result.
            f.session.openActivity(activityB)
            val collectingB = launch { f.session.collectGps() }; runCurrent()
            assertEquals(listOf(participation to activityA, participation to activityB), f.started)
            assertNull(f.identity.view.first().gpsOutcome)
            f.session.onBackground(); collectingB.join()
            assertEquals(1, f.submitKeys.size) // Navigation did not replay a mutation.
        } finally { f.api.close() }
    }

    @Test fun reusedActivityIdInAnotherParticipationDoesNotInheritTheResult() = runTest {
        val f = Fixture(this, "verified")
        try {
            f.prepare(); val collecting = launch { f.session.collectGps() }; runCurrent()
            f.session.submitGps(); collecting.join()
            f.session.openQuest("gps-two"); f.session.continueQuest(); f.session.openActivity(activityA)
            assertEquals(otherParticipation, f.identity.view.first().running?.id)
            assertEquals(activityA, f.identity.view.first().question?.id); assertNull(f.identity.view.first().gpsOutcome)
            val collectingOther = launch { f.session.collectGps() }; runCurrent()
            assertEquals(otherParticipation to activityA, f.started.last())
            f.session.onBackground(); collectingOther.join()
        } finally { f.api.close() }
    }

    @Test fun unknownSubmitSurvivesReaderNavigationAndOnlyExplicitRetryReusesItsKey() = runTest {
        val f = Fixture(this, "verified", failFirstSubmit = true)
        try {
            f.prepare(); val collecting = launch { f.session.collectGps() }; runCurrent()
            f.session.submitGps(); collecting.join()
            val proof = f.session.view.value.gpsProof
            assertTrue(f.identity.view.first().gpsSubmissionPending)
            advanceTimeBy(1_001)
            f.session.continuePassportQuest(participation); f.session.openActivity(activityB)
            val b = f.identity.view.first()
            assertNull(b.gpsOutcome); assertFalse(b.gpsReady); assertFalse(b.gpsSubmissionPending)
            assertTrue(b.gpsCollectionBlocked)
            f.session.submitGps(); f.session.collectGps()
            assertEquals(1, f.submitKeys.size); assertEquals(1, f.started.size)
            f.session.openActivity(activityA)
            assertEquals(proof, f.session.view.value.gpsProof); assertTrue(f.identity.view.first().gpsSubmissionPending)
            assertFalse(f.identity.view.first().gpsCollectionBlocked)
            assertEquals(1, f.submitKeys.size); assertEquals(1, f.started.size)
            f.session.submitGps()
            assertEquals(2, f.submitKeys.size); assertNotNull(f.submitKeys[0]); assertEquals(f.submitKeys[0], f.submitKeys[1])
            assertFalse(f.identity.view.first().gpsSubmissionPending); assertEquals("verified", f.identity.view.first().gpsOutcome)
        } finally { f.api.close() }
    }

    @Test fun backgroundedActiveProofCannotOfferSubmitForAnotherTarget() = checkActiveProofScope(false)
    @Test fun backgroundedActiveProofCannotOfferSubmitForAnotherParticipation() = checkActiveProofScope(true)

    private fun checkActiveProofScope(anotherParticipation: Boolean) = runTest {
        val f = Fixture(this, "verified")
        try {
            f.prepare(); val collecting = launch { f.session.collectGps() }; runCurrent()
            f.session.onBackground(); collecting.join()
            assertTrue(f.identity.view.first().gpsReady)
            if (anotherParticipation) { f.session.openQuest("gps-two"); f.session.continueQuest() }
            else f.session.continuePassportQuest(participation)
            val targetParticipation = if (anotherParticipation) otherParticipation else participation
            val targetActivity = if (anotherParticipation) activityA else activityB
            f.session.openActivity(targetActivity)
            val b = f.identity.view.first()
            assertEquals(targetParticipation, b.running?.id); assertEquals(targetActivity, b.question?.id)
            assertFalse(b.gpsReady); assertFalse(b.gpsSubmissionPending); assertFalse(b.gpsCollecting)
            assertFalse(b.gpsCollectionBlocked); assertFalse(b.locationUnavailable)
            f.session.submitGps(); assertTrue(f.submittedProofs.isEmpty())
            val collectingB = launch { f.session.collectGps() }; runCurrent()
            assertEquals(targetParticipation to targetActivity, f.started.last())
            f.session.submitGps(); collectingB.join()
            assertEquals(listOf(if (anotherParticipation) otherProof else proofB), f.submittedProofs)
            assertEquals("verified", f.identity.view.first().gpsOutcome)
        } finally { f.api.close() }
    }
}
