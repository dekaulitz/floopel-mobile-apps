package com.floopel.feature.traveler

import com.floopel.feature.traveler.core.network.androidNativeApi
import com.floopel.feature.traveler.data.repository.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.TravelerSession
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlin.test.*

/** Opt-in real native API integration. The ignored fixture manifest is never packaged in the app. */
class LivePrivateTravelerTest {
    companion object {
        private val fixture by lazy { loadPrivateFixture() }
        private val actors = mutableMapOf<String, PrivateActor>()
        @JvmStatic @org.junit.BeforeClass fun loginFixtures() = runBlocking {
            fixture.accounts.forEach { credentials ->
                PrivateActor(credentials).also { actors[credentials.role] = it; it.login() }
            }
        }
        @JvmStatic @org.junit.AfterClass fun logoutFixtures() = runBlocking {
            actors.values.forEach { actor ->
                try { actor.session.logout(); assertFalse(actor.session.view.value.signedIn) }
                finally { actor.close() }
            }
            actors.clear()
        }
    }

    @Test fun verifiedFixtureAccountsUseNativeLoginAndActualLegalConsent() = runBlocking {
        assertEquals(8, fixture.accounts.size)
        fixture.accounts.forEach { credentials ->
            val actor = actors.getValue(credentials.role)
            val profile = actor.account.profile().ready("profile")
            assertEquals(credentials.profileId, profile.profileId)
            assertEquals(credentials.username, profile.username)
            assertTrue(actor.legal.pending("id").ready("consent readback").isEmpty())
        }
    }

    @Test fun ownedItineraryAndStopUseActualPersistenceAndRejectAnOutsider() = runBlocking {
        val owner = actor("organizer"); val outsider = actor("outsider")
        run {
            val spot = owner.catalog.discoverySpots("id").ready("discovery").first()
            val created = owner.itineraries.create(ItineraryDraft("Android QA ${fixture.runId} plan", "Private API fixture")).completed()
            val id = created.itinerary.id
            assertTrue(created.stops.isEmpty())
            val added = owner.itineraries.addStop(id, spot.id, 3).completed()
            val stop = added.stops.single { it.spotId == spot.id && it.day == 3 }
            val edited = owner.itineraries.updateStop(id, stop.copy(arriveTime = "08:00", durationMinutes = 0, note = "Fixture note"))
                .ready("edit Stop")
            assertEquals(0, edited.stops.single { it.id == stop.id }.durationMinutes)
            assertEquals("08:00", owner.itineraries.detail(id).ready("readback").stops.single { it.id == stop.id }.arriveTime)
            val denied = assertIs<CatalogResult.HttpError>(outsider.itineraries.detail(id))
            assertTrue(denied.status in setOf(403, 404))
            owner.itineraries.removeStop(id, stop.id).ready("remove Stop")
            assertTrue(owner.itineraries.detail(id).ready("removed readback").stops.isEmpty())
            owner.itineraries.delete(id).ready("fixture itinerary cleanup")
        }
    }

    @Test fun openTripAdmissionUsesActualFiveParticipantLimitAndCurrentMembership() = runBlocking {
        val owner = actors.getValue("organizer")
        run {
            val spot = owner.catalog.discoverySpots("id").ready("discovery").first()
            val timezone = requireNotNull(owner.catalog.detail(spot, "id").ready("Spot timezone").timezone)
            val plan = owner.itineraries.create(ItineraryDraft("Android QA ${fixture.runId} admission", "Synthetic private note")).completed().itinerary
            owner.itineraries.addStop(plan.id, spot.id, 1).completed()
            val start = LocalDate.now(ZoneOffset.UTC).plusDays(30)
            val draft = TripDraft("Android QA ${fixture.runId} Open Trip", start.toString(), start.plusDays(2).toString(), timezone,
                TripVisibility.PUBLIC, Instant.now().minusSeconds(60).toString(), Instant.now().plusSeconds(604_800).toString())
            val key = privateOperationKey()
            val trip = owner.trips.create(plan.id, draft, key).ready("create Open Trip")
            assertEquals(plan.id, trip.workingItineraryId)
            assertEquals(trip.id, owner.trips.pendingCreatedTrip(plan.id).ready("reconcile create")?.id)
            val reenteredRouteKey = privateOperationKey()
            owner.trips.rememberTripCreationRoute(reenteredRouteKey, trip.id).ready("bind reentered creation route")
            owner.trips.completeTripCreation(trip.id).ready("complete create")
            assertEquals(trip.id, owner.trips.createdTripForOperation(key).ready("reconcile acknowledged creation route")?.id)
            assertEquals(trip.id, owner.recreateTrips().createdTripForOperation(reenteredRouteKey).ready("reconcile aliased route after recreation")?.id)
            assertEquals(trip.id, owner.trips.create(plan.id, draft, key).ready("same creation key replay").id)
            owner.trips.completeTripCreation(trip.id).ready("complete replay")
            assertEquals(1, owner.trips.mine().ready("owned Trips").count { it.id == trip.id })
            assertEquals(spot.id, owner.trips.workingPlan(trip.id).ready("actual working plan").stops.single().spotId)

            val candidates = listOf("applicant", "member2", "member3", "member4", "member5", "outsider", "overflow")
            val applications = candidates.associateWith { role ->
                actors.getValue(role).trips.applyToOpenTrip(trip.id, "Synthetic fixture application", privateOperationKey()).ready("apply $role")
            }
            assertTrue(applications.values.all { it.status == TripApplicationStatus.PENDING && !it.currentMember })
            assertEquals(0, owner.trips.detail(trip.id).ready("pending count").participantCount)
            assertDenied(actors.getValue("outsider").trips.workingPlan(trip.id))
            assertDenied(actors.getValue("member2").trips.tripApplication(applications.getValue("applicant").id))
            val withdrawn = actors.getValue("overflow").trips.decideTripApplication(applications.getValue("overflow").id,
                TripApplicationDecision.WITHDRAW, privateOperationKey()).ready("withdraw own application")
            assertEquals(TripApplicationStatus.WITHDRAWN, withdrawn.status)
            val approvalKeys = candidates.take(5).associateWith { privateOperationKey() }
            candidates.take(5).forEach { role ->
                val approved = owner.trips.decideTripApplication(applications.getValue(role).id, TripApplicationDecision.APPROVE,
                    approvalKeys.getValue(role)).ready("approve $role")
                assertTrue(approved.currentMember)
                assertEquals(TripApplicationStatus.APPROVED, approved.status)
            }
            val roster = owner.trips.members(trip.id).ready("roster at capacity").filter { it.active }
            assertEquals(5, roster.count { it.role == TripRole.PARTICIPANT })
            assertEquals(1, roster.count { it.role == TripRole.ORGANIZER })
            val full = actors.getValue("outsider").trips.openTrip(trip.id).ready("public capacity")
            assertEquals(5, full.participantLimit)
            assertEquals(0, full.remainingParticipants)
            val denied = assertIs<CatalogResult.HttpError>(owner.trips.decideTripApplication(applications.getValue("outsider").id,
                TripApplicationDecision.APPROVE, privateOperationKey()))
            assertTrue(denied.status in setOf(409, 422), "Sixth participant must be rejected")
            assertEquals(5, owner.trips.detail(trip.id).ready("count after denial").participantCount)
            assertEquals(TripApplicationStatus.REJECTED, owner.trips.decideTripApplication(applications.getValue("outsider").id,
                TripApplicationDecision.REJECT, privateOperationKey()).ready("reject pending candidate").status)

            val applicant = actors.getValue("applicant")
            owner.trips.removeMember(trip.id, applications.getValue("applicant").profileId, privateOperationKey()).ready("remove approved member")
            assertDenied(applicant.trips.workingPlan(trip.id))
            val replay = owner.trips.decideTripApplication(applications.getValue("applicant").id, TripApplicationDecision.APPROVE,
                approvalKeys.getValue("applicant")).ready("approval replay after removal")
            assertEquals(TripApplicationStatus.APPROVED, replay.status)
            assertFalse(replay.currentMember)
            assertEquals(4, owner.trips.detail(trip.id).ready("replay cannot rejoin").participantCount)
            owner.trips.lifecycle(trip.id, TripLifecycleAction.CANCEL, privateOperationKey()).ready("cancel fixture Trip")
        }
    }

    private fun actor(role: String) = actors.getValue(role)
}

/** Explicit real upload + scoped processing + publication. Separate from the admission smoke. */
class LivePrivateMediaPublicationTest {
    @Test fun realCoverProcessingPublishesAnImmutableSnapshotAndUnpublishesIt() = runBlocking {
        val fixture = loadPrivateFixture()
        val owner = PrivateActor(fixture.accounts.single { it.role == "organizer" })
        val guest = PrivateActor(fixture.accounts.single { it.role == "outsider" })
        try {
            owner.login()
            val spot = owner.catalog.discoverySpots("id").ready("discovery").first()
            val detail = owner.itineraries.create(ItineraryDraft("Android QA ${fixture.runId} publication", "Synthetic publication fixture")).completed()
            val id = detail.itinerary.id
            val added = owner.itineraries.addStop(id, spot.id, 1).completed()
            val stop = added.stops.single()
            owner.itineraries.updateStop(id, stop.copy(note = "Initial reviewed fixture note", durationMinutes = 0)).ready("initial Stop note")
            val before = owner.distribution.read(id).ready("owner publication controls")
            val revision = requireNotNull(before.updatedAt) { "Owner controls must include updated_at" }
            val notReady = owner.distribution.publish(id, ItineraryPublicationReview(revision, true, true, true), privateOperationKey())
            assertEquals(422, assertIs<CatalogResult.HttpError>(notReady).status)
            val cover = owner.distribution.uploadCover(id, publicationTestImage(), "image/jpeg", privateOperationKey()).ready("real cover upload")
            assertTrue(cover.processing in setOf(CoverProcessing.UPLOADED, CoverProcessing.PROCESSING))
            processFixtureCover(cover.mediaId)
            assertEquals(CoverProcessing.READY, owner.distribution.cover(id).ready("processed cover readback").processing)
            val fresh = owner.distribution.read(id).ready("fresh publication controls")
            val published = owner.distribution.publish(id, ItineraryPublicationReview(requireNotNull(fresh.updatedAt), true, true, true), privateOperationKey())
                .ready("publish actual snapshot")
            val slug = requireNotNull(published.publicSlug)
            assertEquals("public", published.visibility)
            val snapshot = guest.publicContent.itinerary(slug).ready("guest publication read")
            assertPublishedFixtureImage(requireNotNull(snapshot.coverUrl))
            assertEquals(spot.id, snapshot.stops.single().spot.id)
            assertEquals(0, snapshot.stops.single().durationMinutes)
            assertNull(snapshot.stops.single().note) // A private Stop note is not made public by the review acknowledgement.
            owner.itineraries.updateStop(id, stop.copy(note = "Private working change after publication", durationMinutes = 15)).ready("change working draft")
            owner.itineraries.update(id, ItineraryDraft("Private working title after publication", "New private working description", cover.mediaId)).ready("change working metadata")
            val unchanged = guest.publicContent.itinerary(slug).ready("immutable public snapshot")
            assertEquals(snapshot.title, unchanged.title)
            assertEquals(snapshot.description, unchanged.description)
            assertEquals(0, unchanged.stops.single().durationMinutes)
            assertNull(unchanged.stops.single().note)
            assertTrue(owner.distribution.read(id).ready("unpublished changes").unpublishedChanges)
            owner.distribution.unpublish(id, privateOperationKey()).ready("unpublish fixture")
            assertDenied(guest.publicContent.itinerary(slug))
            owner.itineraries.delete(id).ready("delete own publication fixture")
        } finally {
            owner.session.logout(); owner.close(); guest.close()
        }
    }
}

private val fixtureJson = Json { ignoreUnknownKeys = true }
private fun loadPrivateFixture(): PrivateFixture = fixtureJson.decodeFromString(
    File(requireNotNull(System.getProperty("floopel.test.fixture"))).readText())

private fun publicationTestImage(): ByteArray {
    val image = BufferedImage(1600, 1000, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
        graphics.color = Color(55, 48, 163); graphics.fillRect(0, 0, 1600, 1000)
        graphics.color = Color(240, 170, 60); graphics.fillRect(100, 100, 800, 600)
    } finally { graphics.dispose() }
    return ByteArrayOutputStream().use { bytes ->
        check(ImageIO.write(image, "jpeg", bytes)); image.flush(); bytes.toByteArray()
    }
}

private fun processFixtureCover(mediaId: String) {
    val process = ProcessBuilder(requireNotNull(System.getProperty("floopel.test.mediaWorker")),
        "--fixture", requireNotNull(System.getProperty("floopel.test.fixture")), "--media", mediaId,
        "--service-root", requireNotNull(System.getProperty("floopel.test.backendService")),
        "--processor-image", requireNotNull(System.getProperty("floopel.test.processorImage")))
        .redirectErrorStream(true).start()
    try {
        assertTrue(process.waitFor(115, TimeUnit.SECONDS), "Fixture media worker exceeded its budget")
        assertEquals(0, process.exitValue(), "Scoped fixture processing failed")
        val status = fixtureJson.parseToJsonElement(process.inputStream.bufferedReader().readText()).toString()
        assertTrue(status.contains("\"status\":\"ready\""), "Four real variants must be ready")
    } finally { if (process.isAlive) process.destroyForcibly() }
}

private fun assertPublishedFixtureImage(url: String) {
    assertTrue(url.startsWith(requireNotNull(System.getProperty("floopel.test.origin")) + "/"), "Local fixture delivery must use the API origin")
    val connection = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
    connection.connectTimeout = 10_000; connection.readTimeout = 10_000; connection.instanceFollowRedirects = false
    try {
        assertEquals(200, connection.responseCode, "Published variant must be readable")
        assertTrue(connection.contentType.startsWith("image/webp"))
        val prefix = connection.inputStream.use { it.readNBytes(12) }
        assertEquals("RIFF", prefix.take(4).toByteArray().decodeToString())
        assertEquals("WEBP", prefix.drop(8).take(4).toByteArray().decodeToString())
    } finally { connection.disconnect() }
}

@Serializable
private data class PrivateFixture(@SerialName("run_id") val runId: String, val accounts: List<PrivateCredentials>) {
    override fun toString() = "PrivateFixture([redacted])"
}
@Serializable
private data class PrivateCredentials(val role: String, @SerialName("profile_id") val profileId: String,
    val email: String, val password: String, val username: String) {
    override fun toString() = "PrivateCredentials([redacted])"
}

private class PrivateActor(private val credentials: PrivateCredentials) {
    val api = androidNativeApi(requireNotNull(System.getProperty("floopel.test.origin")), "0.1.0")
    private val refresh = object : RefreshCredentialStore {
        private var value: String? = null
        override fun read() = value
        override fun write(credential: String) { value = credential }
        override fun clear() { value = null }
    }
    val session = TravelerSession(api, refresh, "id")
    private val rest = api.catalogTransport()
    val account = NativeAccountApi(rest, session)
    val legal = NativeLegalApi(rest, session)
    val catalog = AppsTravelerCatalog(rest)
    private val media = NativeMediaUploads(rest, session, PrivateJournal())
    val itineraries = NativeItineraryApi(rest, session, PrivateJournal(), ::privateOperationKey)
    private val tripJournal = PrivateJournal()
    private val creationRoutes = PrivateJournal()
    fun recreateTrips() = NativeTripApi(rest, session, tripJournal, creationRoutes = creationRoutes, mediaUploads = media)
    val trips = recreateTrips()
    val distribution = NativeItineraryDistributionApi(rest, session, PrivateJournal(), mediaUploads = media)
    val publicContent = NativePublicTravelerApi(rest, session)
    val notifications = NativeNotificationApi(rest, session)
    suspend fun login() {
        session.login(credentials.email, credentials.password)
        assertTrue(session.view.value.signedIn, "Native login failed: ${session.view.value.failure?.code}")
        legal.pending("id").ready("pending consent").forEach { document ->
            legal.accept(document, privateOperationKey(), session.identityGeneration).ready("accept consent")
        }
    }
    fun close() = api.close()
}

private class PrivateJournal : TripRecoveryStore, ItineraryRecoveryStore {
    private var value: String? = null
    override fun read() = value
    override fun write(record: String) { value = record }
    override fun clear() { value = null }
}
private fun privateOperationKey(): String {
    val now = System.currentTimeMillis()
    val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
    for (i in 0..5) bytes[i] = (now ushr ((5 - i) * 8)).toByte()
    bytes[6] = ((bytes[6].toInt() and 15) or 112).toByte()
    bytes[8] = ((bytes[8].toInt() and 63) or 128).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    return "${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
private fun <T> CatalogResult<T>.ready(operation: String): T = assertIs<CatalogResult.Ready<T>>(this,
    "$operation: ${if (this is CatalogResult.HttpError) "HTTP $status / $code" else this::class.simpleName}").value
private fun <T> ItineraryWriteResult<T>.completed(): T = assertIs<ItineraryWriteResult.Completed<T>>(this).value
private fun assertDenied(result: CatalogResult<*>) {
    assertTrue(assertIs<CatalogResult.HttpError>(result).status in setOf(403, 404))
}
