package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.remote.canonicalAppsFailureCode
import com.floopel.feature.traveler.data.remote.canonicalAppsFailureIssues
import com.floopel.feature.traveler.data.dto.ApplyOpenTripDto
import com.floopel.feature.traveler.feature.auth.*

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.TravelerSession
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Clock

/** Every mutation receives a stable caller-owned key and executes only once. */
internal class NativeTripApi(private val rest: FloopelRestClient, private val session: TravelerSession,
    private val recovery: TripRecoveryStore = MemoryTripRecoveryStore(),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val mediaUploads: NativeMediaUploads? = null,
    private val creationRoutes: TripRecoveryStore = MemoryTripRecoveryStore(),
) : TravelerTripRepository {
    private val http = NativeAuthorizedRequests(rest, session)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutation = Mutex()
    private var reviewed: Pair<Long, String?>? = null

    override suspend fun pendingDraft(sourceId: String, creating: Boolean): CatalogResult<TripDraft?> {
        val epoch = session.identityGeneration
        val actor = actor(epoch)
        if (actor !is CatalogResult.Ready) return actor.tripFailure()
        return try {
            val saved = recovery.read()?.let { json.parseToJsonElement(it).jsonObject }
            val target = if (creating) "/itineraries/${sourceId.encodeURLPathPart()}/trips" else path(sourceId)
            if (saved == null || saved.text("profile_id") != actor.value || saved.text("path") != target) CatalogResult.Ready(null)
            else {
                val draft = json.parseToJsonElement(saved.text("body")).jsonObject
                val opens = draft.optional("registration_opens_at").ifBlank { null }
                val closes = draft.optional("registration_closes_at").ifBlank { null }
                val visibility = draft.optional("visibility").takeIf(String::isNotBlank)?.let { value ->
                    TripVisibility.entries.firstOrNull { it.wire == value } ?: error("Invalid Trip visibility")
                } ?: when {
                    creating -> TripVisibility.PRIVATE
                    opens != null && closes != null -> TripVisibility.PUBLIC
                    else -> TripVisibility.UNKNOWN // Legacy PATCH omitted type and registration; it does not prove private visibility.
                }
                CatalogResult.Ready(TripDraft(draft.text("name"), draft.text("starts_on"), draft.text("ends_on"), draft.text("timezone"),
                    visibility, opens, closes, if ("approved_description" in draft) draft.tripNullableText("approved_description") else null))
            }
        } catch (_: Exception) { CatalogResult.InvalidResponse }
    }

    /** Looks up an interrupted create by its durable key without sending another POST or clearing uncertainty. */
    override suspend fun pendingCreatedTrip(itineraryId: String): CatalogResult<TravelerTrip?> {
        if (!mutation.tryLock()) return CatalogResult.HttpError(409)
        try {
            val epoch = session.identityGeneration
            val actor = actor(epoch)
            if (actor !is CatalogResult.Ready) return actor.tripFailure()
            val raw = try { recovery.read() } catch (_: Exception) { return CatalogResult.InvalidResponse }
            val saved = try { raw?.let { json.parseToJsonElement(it).jsonObject } } catch (_: Exception) { return CatalogResult.InvalidResponse }
            if (saved == null || saved.text("profile_id") != actor.value || saved.text("path") != "/itineraries/${itineraryId.encodeURLPathPart()}/trips") return CatalogResult.Ready(null)
            val id = saved.optional("confirmed_trip_id").takeIf { it.isNotBlank() }
            val target = id?.let(::path) ?: "/me/trip-creation-operations/${saved.text("key").encodeURLPathPart()}"
            val result = read(RestMethod.GET, target, expectedGeneration = epoch, expectedCode = if (id == null) "TRIP" else null, decode = ::trip)
            if (id == null && result is CatalogResult.HttpError && result.status == 404) return CatalogResult.Ready(null)
            if (id == null && result is CatalogResult.Ready && session.identityGeneration == epoch) {
                try {
                    if (recovery.read() == raw) recovery.write(JsonObject(saved + ("confirmed_trip_id" to JsonPrimitive(result.value.id))).toString())
                } catch (_: Exception) { /* The authorized server result stays confirmed if local bookkeeping fails. */ }
            }
            return result
        } finally { mutation.unlock() }
    }
    override suspend fun completeTripCreation(id: String): CatalogResult<Unit> {
        if (!mutation.tryLock()) return CatalogResult.HttpError(409)
        return try {
            val epoch = session.identityGeneration
            val actor = actor(epoch)
            if (actor !is CatalogResult.Ready) return actor.tripFailure()
            val saved = recovery.read()?.let { json.parseToJsonElement(it).jsonObject }
            if (saved != null && saved.text("profile_id") == actor.value && saved.optional("confirmed_trip_id") == id) recovery.clear()
            CatalogResult.Ready(Unit)
        } catch (_: Exception) { CatalogResult.InvalidResponse } finally { mutation.unlock() }
    }
    override suspend fun createdTripForOperation(key: String): CatalogResult<TravelerTrip?> {
        if (key.isBlank() || key.length > 128) return CatalogResult.HttpError(422)
        val epoch = session.identityGeneration
        val actor = actor(epoch)
        if (actor !is CatalogResult.Ready) return actor.tripFailure()
        val canonical = try { creationAliases(actor.value).optional(key).ifBlank { key } }
            catch (_: Exception) { return CatalogResult.InvalidResponse }
        val result = read(RestMethod.GET, "/me/trip-creation-operations/${canonical.encodeURLPathPart()}", expectedGeneration = epoch,
            expectedCode = "TRIP", decode = ::trip)
        // A known alias already identifies a committed create. Lost access cannot turn it into a fresh action.
        return if (canonical == key && result is CatalogResult.HttpError && result.status == 404) CatalogResult.Ready(null) else result
    }
    override suspend fun rememberTripCreationRoute(routeKey: String, tripId: String): CatalogResult<Unit> {
        if (routeKey.isBlank() || routeKey.length > 128 || tripId.isBlank()) return CatalogResult.HttpError(422)
        if (!mutation.tryLock()) return CatalogResult.HttpError(409)
        return try {
            val epoch = session.identityGeneration
            val actor = actor(epoch)
            if (actor !is CatalogResult.Ready) return actor.tripFailure()
            val saved = recovery.read()?.let { json.parseToJsonElement(it).jsonObject }
            if (saved != null && saved.text("profile_id") == actor.value && saved.optional("confirmed_trip_id") == tripId) {
                val canonical = saved.text("key")
                if (canonical != routeKey) {
                    val aliases = creationAliases(actor.value)
                    val previous = aliases.optional(routeKey)
                    if (previous.isNotBlank() && previous != canonical) return CatalogResult.HttpError(409, code = "CREATION_ROUTE_CONFLICT")
                    // Never silently evict an alias still referenced by an OS-saved route.
                    if (aliases.size >= 256 && routeKey !in aliases) return CatalogResult.HttpError(409, code = "CREATION_RECOVERY_LIMIT")
                    creationRoutes.write(buildJsonObject {
                        put("profile_id", actor.value)
                        put("aliases", JsonObject(aliases + (routeKey to JsonPrimitive(canonical))))
                    }.toString())
                }
            }
            CatalogResult.Ready(Unit)
        } catch (_: Exception) { CatalogResult.InvalidResponse } finally { mutation.unlock() }
    }
    private fun creationAliases(profileId: String): JsonObject {
        val saved = creationRoutes.read()?.let { json.parseToJsonElement(it).jsonObject } ?: return buildJsonObject {}
        return if (saved.text("profile_id") == profileId) saved.getValue("aliases").jsonObject else buildJsonObject {}
    }
    override suspend fun reviewPendingMutation(): CatalogResult<List<TravelerTrip>> {
        if (!mutation.tryLock()) return CatalogResult.HttpError(409)
        try {
            reviewed = null
            val epoch = session.identityGeneration
            val actor = actor(epoch)
            if (actor !is CatalogResult.Ready) return actor.tripFailure()
            val current = read(RestMethod.GET, "/me/trips", expectedGeneration = epoch) { it.jsonArray.map(::trip) }
            if (current is CatalogResult.Ready) {
                val record = try {
                    val saved = recovery.read()
                    if (saved != null && json.parseToJsonElement(saved).jsonObject.text("profile_id") != actor.value) return CatalogResult.HttpError(401)
                    saved
                } catch (_: Exception) { return CatalogResult.InvalidResponse }
                reviewed = epoch to record
            }
            return current
        } finally { mutation.unlock() }
    }

    override suspend fun startNewAction(): CatalogResult<Unit> {
        if (!mutation.tryLock()) return CatalogResult.HttpError(409)
        return try {
            val snapshot = reviewed ?: return CatalogResult.HttpError(409)
            if (snapshot.first != session.identityGeneration || recovery.read() != snapshot.second) return CatalogResult.HttpError(409)
            recovery.clear()
            reviewed = null
            CatalogResult.Ready(Unit)
        } catch (_: Exception) { CatalogResult.InvalidResponse } finally { mutation.unlock() }
    }

    private suspend fun actor(epoch: Long) = read(RestMethod.GET, "/me", expectedGeneration = epoch) {
        it.jsonObject.getValue("profile").jsonObject.text("id")
    }
    override suspend fun mine() = read(RestMethod.GET, "/me/trips") { it.jsonArray.map(::trip) }
    /** Uses server-side filtering before pagination and preserves only public projection fields. */
    override suspend fun openTrips(query: OpenTripQuery): CatalogResult<OpenTripPage> {
        val suffix = runCatching { query.toWireQuery() }.getOrElse { return CatalogResult.HttpError(422) }
        val generation = session.identityGeneration
        val result = read(RestMethod.GET, "/open-trips$suffix", expectedCode = "OPEN_TRIPS") {
            val dto = decodeOpenTripPage(it.jsonObject)
            require(dto.items.size <= query.limit)
            require(dto.nextCursor == null || dto.items.isNotEmpty() && dto.nextCursor.isNotBlank() && dto.nextCursor.length <= 256 && dto.nextCursor != query.cursor)
            val items = dto.items.map { row -> row.toDomain(rest) }
            require(!query.canApplyOnly || items.all { it.canApply })
            OpenTripPage(items, dto.nextCursor)
        }
        if (result !is CatalogResult.Ready) return result
        val enriched = enrichOpenTripOrganizers(result.value.items, generation)
        return enriched?.let { CatalogResult.Ready(result.value.copy(items = it)) } ?: CatalogResult.HttpError(401)
    }
    /** Public and member detail use separate endpoints and separate Domain models. */
    override suspend fun openTrip(id: String): CatalogResult<OpenTrip> {
        if (!validNotificationId(id)) return CatalogResult.HttpError(404)
        val generation = session.identityGeneration
        val result = read(RestMethod.GET, "/open-trips/${id.encodeURLPathPart()}", expectedCode = "OPEN_TRIP") {
            decodeOpenTrip(it.jsonObject).toDomain(rest).also { trip -> require(trip.id == id) }
        }
        if (result !is CatalogResult.Ready) return result
        return enrichOpenTripOrganizers(listOf(result.value), generation)?.singleOrNull()
            ?.let { CatalogResult.Ready(it) } ?: CatalogResult.HttpError(401)
    }

    /** One optional batch enriches a page; logout during that read discards the entire old projection. */
    private suspend fun enrichOpenTripOrganizers(items: List<OpenTrip>, generation: Long): List<OpenTrip>? {
        val identities = TravelerAvatarIdentities({ http.execute(RestMethod.GET, it) }, rest::resolveMediaUrl)
            .read(items.map { it.organizer.username })
        if (session.identityGeneration != generation) return null
        return items.map { trip -> trip.copy(organizer = trip.organizer.copy(
            appearance = identities[trip.organizer.username.lowercase()])) }
    }
    /** Public route reads do not hydrate member plans or request per-card Spot details. */
    override suspend fun openTripStops(id: String, cursor: String?): CatalogResult<OpenTripStopPage> {
        if (!validNotificationId(id) || cursor != null && cursor.length > 128) return CatalogResult.HttpError(422)
        val query = OpenTripQuery(cursor = cursor).toWireQuery()
        return read(RestMethod.GET, "/open-trips/${id.encodeURLPathPart()}/spots$query", expectedCode = "OPEN_TRIP_SPOTS") {
            val dto = decodeOpenTripStopPage(it.jsonObject)
            require(dto.items.size <= 20)
            require(dto.nextCursor == null || dto.items.isNotEmpty() && dto.nextCursor.isNotBlank() && dto.nextCursor.length <= 128 && dto.nextCursor != cursor)
            OpenTripStopPage(dto.items.map { row -> row.toDomain(rest) }, dto.nextCursor)
        }
    }
    /** Keeps approval history distinct from the server's current membership flag. */
    override suspend fun tripApplication(id: String): CatalogResult<TripApplication> {
        if (!validNotificationId(id)) return CatalogResult.HttpError(404)
        val epoch = session.identityGeneration
        val actor = actor(epoch)
        if (actor !is CatalogResult.Ready) return actor.tripFailure()
        return read(RestMethod.GET, "/trip-applications/${id.encodeURLPathPart()}", expectedGeneration = epoch, expectedCode = "TRIP_APPLICATION") {
            decodeTripApplication(it.jsonObject).toDomain().also { application -> require(application.id == id) }.let { application ->
                // The protected reader authorizes only the applicant or the current organizer.
                application.copy(viewer = if (application.profileId == actor.value) TripApplicationViewer.APPLICANT else TripApplicationViewer.ORGANIZER)
            }
        }
    }
    /** Owner filters and cursor stay on the owner endpoint. */
    override suspend fun myTripApplications(tripId: String?, cursor: String?): CatalogResult<TripApplicationPage> {
        val query = runCatching { applicationWireQuery(tripId, cursor, 20) }.getOrElse { return CatalogResult.HttpError(422) }
        return readApplications("/me/trip-applications$query", tripId, cursor, TripApplicationViewer.APPLICANT)
    }
    /** Organizer capability is checked on the server for every page. */
    override suspend fun tripApplications(tripId: String, cursor: String?): CatalogResult<TripApplicationPage> {
        if (!validNotificationId(tripId)) return CatalogResult.HttpError(404)
        val query = runCatching { applicationWireQuery(null, cursor, 20) }.getOrElse { return CatalogResult.HttpError(422) }
        return readApplications("/trips/${tripId.encodeURLPathPart()}/applications$query", tripId, cursor, TripApplicationViewer.ORGANIZER)
    }
    /** Reads the encrypted actor-bound intent so process recreation cannot silently replace its message/key. */
    override suspend fun pendingOpenTripApplication(tripId: String): CatalogResult<String?> {
        if (!validNotificationId(tripId)) return CatalogResult.HttpError(404)
        val generation = session.identityGeneration
        val profile = actor(generation)
        if (profile !is CatalogResult.Ready) return profile.tripFailure()
        return try {
            val saved = recovery.read()?.let { json.parseToJsonElement(it).jsonObject }
            val target = "/open-trips/${tripId.encodeURLPathPart()}/applications"
            if (generation != session.identityGeneration) CatalogResult.HttpError(401)
            else if (saved == null || saved.text("profile_id") != profile.value || saved.text("path") != target || saved.text("method") != "POST")
                CatalogResult.Ready(null)
            else CatalogResult.Ready(json.parseToJsonElement(saved.text("body")).jsonObject.optional("message"))
        } catch (_: Exception) { CatalogResult.InvalidResponse }
    }

    /** Normalizes only the optional message and keeps the frozen body/key for explicit retries. */
    override suspend fun applyToOpenTrip(tripId: String, message: String, key: String): CatalogResult<TripApplication> {
        if (!validNotificationId(tripId) || visibleCharacterCount(message) > 500) return CatalogResult.HttpError(422)
        val body = json.encodeToString(ApplyOpenTripDto(message.trim().takeIf { it.isNotEmpty() }))
        return read(RestMethod.POST, "/open-trips/${tripId.encodeURLPathPart()}/applications", body, key, expectedCode = "TRIP_APPLICATION_CREATED") {
            decodeTripApplication(it.jsonObject).toDomain().also { application -> require(application.tripId == tripId) }
        }
    }
    /** Terminal replay is authorized again and cannot create a replacement membership episode. */
    override suspend fun decideTripApplication(id: String, decision: TripApplicationDecision, key: String): CatalogResult<TripApplication> {
        if (!validNotificationId(id)) return CatalogResult.HttpError(404)
        return read(RestMethod.POST, "/trip-applications/${id.encodeURLPathPart()}/${decision.wire}", key = key, releaseRejectedDecision = true, expectedCode = "TRIP_APPLICATION_UPDATED") {
            decodeTripApplication(it.jsonObject).toDomain().also { application ->
                require(application.id == id)
                val wanted = when (decision) {
                    TripApplicationDecision.APPROVE -> TripApplicationStatus.APPROVED
                    TripApplicationDecision.REJECT -> TripApplicationStatus.REJECTED
                    TripApplicationDecision.WITHDRAW -> TripApplicationStatus.WITHDRAWN
                }
                require(application.status == wanted)
            }
                .copy(viewer = if (decision == TripApplicationDecision.WITHDRAW) TripApplicationViewer.APPLICANT else TripApplicationViewer.ORGANIZER)
        }
    }
    /** Converts a page only after the authorized list request completes in this session generation. */
    private suspend fun readApplications(path: String, tripId: String?, cursor: String?, viewer: TripApplicationViewer): CatalogResult<TripApplicationPage> =
        read(RestMethod.GET, path, expectedCode = "TRIP_APPLICATIONS") {
            val dto = decodeTripApplicationPage(it.jsonObject)
            require(dto.items.size <= 20 && (tripId == null || dto.items.all { item -> item.tripId == tripId }))
            require(dto.nextCursor == null || dto.items.isNotEmpty() && validNotificationId(dto.nextCursor) && dto.nextCursor != cursor)
            TripApplicationPage(dto.items.map { row -> row.toDomain().copy(viewer = viewer) }, dto.nextCursor)
        }

    /** Returns only this actor's matching application command; other targets cannot adopt its intent. */
    override suspend fun pendingTripApplicationDecision(id: String): CatalogResult<TripApplicationDecision?> {
        if (!validNotificationId(id)) return CatalogResult.HttpError(404)
        val epoch = session.identityGeneration
        val actor = actor(epoch)
        if (actor !is CatalogResult.Ready) return actor.tripFailure()
        return try {
            val saved = recovery.read()?.let { json.parseToJsonElement(it).jsonObject }
            if (session.identityGeneration != epoch) return CatalogResult.HttpError(401)
            if (saved == null || saved.text("profile_id") != actor.value || saved.text("method") != RestMethod.POST.name) CatalogResult.Ready(null)
            else CatalogResult.Ready(TripApplicationDecision.entries.firstOrNull { decision ->
                saved.text("path") == "/trip-applications/${id.encodeURLPathPart()}/${decision.wire}"
            })
        } catch (_: Exception) { CatalogResult.InvalidResponse }
    }
    override suspend fun detail(id: String) = read(RestMethod.GET, path(id), decode = ::trip)
    /** Reuses the native session's generation-checked read without retaining private snapshots. */
    override suspend fun ownedTripNotice(id: String): CatalogResult<TripOutcomeNotice> {
        if (!validNotificationId(id)) return CatalogResult.HttpError(404)
        return read(RestMethod.GET, "/me/trip-notices/${id.encodeURLPathPart()}", expectedCode = "TRIP_NOTICE") {
            decodeTripOutcomeNotice(it.jsonObject).toDomain().also { notice -> require(notice.id == id) }
        }
    }
    override suspend fun bannerStatus(id: String) = read(RestMethod.GET, path(id) + "/banner", decode = ::banner)
    override suspend fun uploadBanner(id: String, bytes: ByteArray, mimeType: String, key: String): CatalogResult<TripBannerStatus> {
        if (bytes.isEmpty() || bytes.size > 5 * 1024 * 1024 || mimeType !in setOf("image/jpeg", "image/png", "image/webp") || key.isBlank()) return CatalogResult.HttpError(422)
        if (mediaUploads != null) return mediaUploads.upload(path(id) + "/banner", bytes, mimeType, key) { decodeOutcome(it, ::banner) }
        return decodeOutcome(http.upload("/api/apps/v1" + path(id) + "/banner", bytes, mimeType, key), ::banner)
    }
    private fun banner(element: JsonElement) = element.jsonObject.let {
        TripBannerStatus(it.text("media_id"), CoverProcessing.entries.single { status -> status.wire == it.text("processing_status") },
            it.getValue("retryable").jsonPrimitive.also { flag -> require(!flag.isString) }.boolean)
    }
    /** Member levels are optional and bound to the same identity generation as the private read. */
    override suspend fun members(id: String): CatalogResult<List<TripMember>> {
        val generation = session.identityGeneration
        val result = read(RestMethod.GET, path(id) + "/members") { element ->
            element.jsonArray.map { entry ->
                val value = entry.jsonObject
                TripMember(value.text("profile_id"), value.text("username"), value.text("display_name"),
                    role(value.text("role")), value.text("status") == "active")
            }
        }
        if (result !is CatalogResult.Ready) return result
        val identities = TravelerAvatarIdentities({ http.execute(RestMethod.GET, it) }, rest::resolveMediaUrl).read(result.value.map { it.username })
        if (generation != session.identityGeneration) return CatalogResult.HttpError(401)
        return CatalogResult.Ready(result.value.map { it.copy(level = identities[it.username]?.level, avatarUrl = identities[it.username]?.avatarUrl) })
    }
    override suspend fun workingPlan(id: String) = read(RestMethod.GET, path(id) + "/itinerary") { element ->
        val value = element.jsonObject
        TripWorkingPlan(value.text("title"), value["stops"]!!.jsonArray.map { entry -> entry.jsonObject.let { stop ->
            OwnedStop(stop.text("id"), stop.text("spot_id"), stop.text("spot_name"), stop.integer("day"), stop.integer("position"),
                stop.optional("override_note"), (stop["override_duration_min"] as? JsonPrimitive)?.intOrNull,
                stop.optional("override_tips"), stop.optional("arrive_time"), stop.optional("travel_note"))
        } }.sortedWith(compareBy(OwnedStop::day, OwnedStop::position)),
            catalogMediaPhoto(value["cover_image"], rest, value.optional("cover_image_alt").orEmpty()), value.readLocations())
    }
    override suspend fun create(itineraryId: String, draft: TripDraft, key: String): CatalogResult<TravelerTrip> {
        if (!validDraft(draft) || !validCreation(draft)) return CatalogResult.HttpError(422)
        return read(RestMethod.POST, "/itineraries/${itineraryId.encodeURLPathPart()}/trips", body(draft, true), key, keepConfirmedTrip = true, decode = ::trip)
    }
    override suspend fun createWithBanner(itineraryId: String, draft: TripDraft, key: String): CatalogResult<TravelerTrip> {
        return create(itineraryId, draft, key)
    }
    override suspend fun update(id: String, draft: TripDraft, key: String): CatalogResult<TravelerTrip> {
        if (!validDraft(draft) || !validUpdateRegistration(draft)) return CatalogResult.HttpError(422)
        return read(RestMethod.PATCH, path(id), body(draft, false), key, decode = ::trip)
    }
    override suspend fun lifecycle(id: String, action: TripLifecycleAction, key: String) = ack(RestMethod.POST, path(id) + "/${action.wire}", key)
    override suspend fun removeMember(id: String, profileId: String, key: String) = ack(RestMethod.DELETE, path(id) + "/members/${profileId.encodeURLPathPart()}", key)
    override suspend fun transferOrganizer(id: String, profileId: String, key: String) = ack(RestMethod.POST, path(id) + "/organizer-transfer", key,
        buildJsonObject { put("new_organizer_profile_id", profileId) }.toString())
    override suspend fun invitations(id: String) = read(RestMethod.GET, path(id) + "/invitations") { it.jsonArray.map(::invitation) }
    /** Inviter identity metadata is batched once and discarded when the recipient account changes. */
    override suspend fun myInvitations(): CatalogResult<List<TripInvitation>> {
        val generation = session.identityGeneration
        val result = read(RestMethod.GET, "/me/trip-invitations") { it.jsonArray.map(::invitation) }
        if (result !is CatalogResult.Ready) return result
        val identities = TravelerAvatarIdentities({ http.execute(RestMethod.GET, it) }, rest::resolveMediaUrl).read(result.value.map { it.inviterUsername })
        if (generation != session.identityGeneration) return CatalogResult.HttpError(401)
        return CatalogResult.Ready(result.value.map { it.copy(inviterLevel = identities[it.inviterUsername]?.level,
            inviterAvatarUrl = identities[it.inviterUsername]?.avatarUrl) })
    }
    override suspend fun invite(id: String, recipient: String, key: String): CatalogResult<Unit> {
        val value = recipient.trim()
        if (value.isBlank()) return CatalogResult.HttpError(422)
        val body = buildJsonObject {
            if (value.startsWith("@") || !value.contains("@")) { put("target_type", "profile"); put("username", value.removePrefix("@")) }
            else { put("target_type", "email"); put("email", value) }
        }
        return ack(RestMethod.POST, path(id) + "/invitations", key, body.toString())
    }
    override suspend fun resend(id: String, invitationId: String, key: String) = ack(RestMethod.POST, path(id) + "/invitations/${invitationId.encodeURLPathPart()}/resend", key)
    override suspend fun revoke(id: String, invitationId: String, key: String) = ack(RestMethod.DELETE, path(id) + "/invitations/${invitationId.encodeURLPathPart()}", key)
    override suspend fun decide(invitationId: String, decision: InvitationDecision, key: String) = ack(RestMethod.POST, "/trip-invitations/${invitationId.encodeURLPathPart()}/${decision.wire}", key)
    override suspend fun block(invitationId: String, key: String) = ack(RestMethod.POST, "/trip-invitations/${invitationId.encodeURLPathPart()}/block-inviter", key)
    override suspend fun report(invitationId: String, reason: InvitationReportReason, details: String, key: String): CatalogResult<Unit> {
        if (details.length > 1000) return CatalogResult.HttpError(422)
        return ack(RestMethod.POST, "/trip-invitations/${invitationId.encodeURLPathPart()}/reports", key,
            buildJsonObject { put("reason_code", reason.wire); if (details.isNotBlank()) put("details", details) }.toString())
    }
    private suspend fun ack(method: RestMethod, path: String, key: String, body: String? = null): CatalogResult<Unit> = read<Unit>(method, path, body, key) {}
    /** A single mutation owner freezes intent before I/O; reads never acquire or clear that intent. */
    private suspend fun <T> read(method: RestMethod, path: String, body: String? = null, key: String? = null, expectedGeneration: Long? = null, keepConfirmedTrip: Boolean = false, releaseRejectedDecision: Boolean = false, expectedCode: String? = null, decode: (JsonElement) -> T): CatalogResult<T> {
        if (key != null && key.isBlank()) return CatalogResult.HttpError(422)
        if (method == RestMethod.GET) return request(method, path, body, key, expectedGeneration, expectedCode, decode)
        if (!mutation.tryLock()) return CatalogResult.HttpError(409)
        try {
            reviewed = null
            val epoch = session.identityGeneration
            val actor = actor(epoch)
            if (actor !is CatalogResult.Ready) return actor.tripFailure()
            val durableKey = mutationKey(actor.value, method, path, body, key)
            if (durableKey !is CatalogResult.Ready) return durableKey.tripFailure()
            val result = request(method, path, body, durableKey.value, epoch, expectedCode, decode)
            return settleMutation(result, epoch, keepConfirmedTrip, releaseRejectedDecision)
        } finally { mutation.unlock() }
    }

    /** Reuses a matching operation; only legacy Redis-backed actions have the 24h replay limit. */
    private fun mutationKey(actor: String, method: RestMethod, path: String, body: String?, key: String?): CatalogResult<String> {
        return try {
            val saved = recovery.read()?.let { json.parseToJsonElement(it).jsonObject }
            if (saved != null && saved.text("profile_id") == actor) {
                if (saved.text("method") != method.name || saved.text("path") != path || saved.optional("body") != body.orEmpty()) return CatalogResult.HttpError(409)
                val age = nowMillis() - saved.getValue("created_at").jsonPrimitive.long
                if (!hasDurableTripReplay(method, path) && age !in 0 until 86_400_000L) return CatalogResult.HttpError(409)
                CatalogResult.Ready(saved.text("key"))
            } else {
                require(!key.isNullOrBlank())
                recovery.write(buildJsonObject {
                    put("profile_id", actor); put("method", method.name); put("path", path)
                    put("body", body.orEmpty()); put("key", key); put("created_at", nowMillis())
                }.toString())
                CatalogResult.Ready(key)
            }
        } catch (_: Exception) { CatalogResult.InvalidResponse }
    }

    /** A confirmed server outcome survives local cleanup failure; uncertain results keep their intent. */
    private fun <T> settleMutation(result: CatalogResult<T>, epoch: Long, keepConfirmedTrip: Boolean, releaseRejectedDecision: Boolean): CatalogResult<T> {
        if (session.identityGeneration != epoch) return CatalogResult.HttpError(401)
        // Application decisions use atomic terminal-state replay, without a cached/in-progress 409 response.
        val rejectedDecision = releaseRejectedDecision && result is CatalogResult.HttpError && result.status in setOf(400, 401, 403, 404, 409, 422, 429)
        if (result is CatalogResult.Ready || result is CatalogResult.HttpError && result.status == 422 ||
            rejectedDecision || tripCommandRejection(result) != null) {
            try {
                if (keepConfirmedTrip && result is CatalogResult.Ready && result.value is TravelerTrip) {
                    val saved = json.parseToJsonElement(recovery.read()!!).jsonObject
                    recovery.write(JsonObject(saved + ("confirmed_trip_id" to JsonPrimitive(result.value.id))).toString())
                } else recovery.clear()
            } catch (_: Exception) {
                if (result !is CatalogResult.Ready) return CatalogResult.InvalidResponse
            }
        }
        return result
    }

    /** Generation checks surround both transport and mapping, including reads resumed after decoding. */
    private suspend fun <T> request(method: RestMethod, path: String, body: String?, key: String?, epoch: Long?, expectedCode: String?, decode: (JsonElement) -> T): CatalogResult<T> {
        val generation = epoch ?: session.identityGeneration
        val signedIn = session.view.value.signedIn
        val cleanPath = path.substringBefore('?')
        val publicOpenTrip = method == RestMethod.GET && (cleanPath == "/open-trips" ||
            Regex("^/open-trips/[0-9a-fA-F-]{36}(?:/spots)?$").matches(cleanPath))
        val outcome = if (!signedIn && publicOpenTrip) rest.execute(method, "/api/apps/v1$path")
            else http.execute(method, "/api/apps/v1$path", body, key?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap(), generation)
        val result = decodeOutcome(outcome, decode, expectedCode)
        return if (session.identityGeneration == generation && session.view.value.signedIn == signedIn) result else CatalogResult.HttpError(401)
    }
    /** Preserves canonical failure codes while decoding only confirmed operation responses. */
    private fun <T> decodeOutcome(result: ApiOutcome, decode: (JsonElement) -> T, expectedCode: String? = null): CatalogResult<T> {
        return when (result) {
            is ApiOutcome.Success -> runCatching {
                if (result.status == 204) CatalogResult.Ready(decode(JsonNull))
                else {
                    val envelope = json.parseToJsonElement(result.responseBody).jsonObject
                    val code = envelope["meta"]!!.jsonObject.text("code")
                    require(envelope["error"] is JsonNull && code.isNotBlank() && (expectedCode == null || code == expectedCode))
                    CatalogResult.Ready(decode(envelope["data"] ?: error("Missing data")))
                }
            }.getOrElse { CatalogResult.InvalidResponse }
            is ApiOutcome.HttpFailure -> CatalogResult.HttpError(result.status, result.retryAfterSeconds,
                result.code ?: canonicalAppsFailureCode(result.jsonErrorBody), canonicalAppsFailureIssues(result.jsonErrorBody))
            is ApiOutcome.Unavailable -> CatalogResult.Maintenance(result.retryAfterSeconds)
            ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
        }
    }
    private fun path(id: String) = "/trips/" + id.encodeURLPathPart()
    private fun role(value: String) = TripRole.entries.single { it.wire == value }
    /** Keeps live working coverage from the response independently of Trip lifecycle status. */
    private fun trip(element: JsonElement): TravelerTrip = element.jsonObject.let {
        val caller = TripCallerRole.entries.single { role -> role.wire == it.text("caller_role") }
        val invitationId = it["invitation_id"]?.takeUnless { value -> value is JsonNull }?.let { _ ->
            it.text("invitation_id").also { id -> require(id.matches(Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))) }
        }
        require(invitationId == null || caller == TripCallerRole.INVITEE)
        val status = TripStatus.entries.single { status -> status.wire == it.text("status") }
        val count = it.integer("participant_count").also { count -> require(count >= 0) }
        TravelerTrip(it.text("id"), it.text("working_itinerary_id"), it.text("name"), it.text("starts_on"), it.text("ends_on"), it.text("timezone"),
            status, caller, count,
            bannerPhoto = catalogMediaPhoto(it["banner_image"], rest, it.text("name")), invitationId = invitationId,
            locations = it.readLocations(), capabilities = decodeTripMemberCapabilities(it).toDomain(count, caller, status))
    }
    private fun invitation(element: JsonElement): TripInvitation = element.jsonObject.let {
        val status = it.text("status").also { value -> require(value in setOf("pending", "accepted", "declined", "revoked", "expired")) }
        TripInvitation(it.text("id"), it.text("trip_id"), it.text("target_label"), status, it.text("expires_at"), it.optional("trip_name"),
            it.optional("inviter_username"), it.optional("trip_starts_on"), it.optional("trip_ends_on"),
            it["itinerary_summary"]?.takeUnless { value -> value is JsonNull }?.jsonObject?.let { summary ->
                InvitationItinerarySummary(summary.text("id"), summary.text("title"), summary.integer("day_count").also { count -> require(count >= 0) }, summary.integer("stop_count").also { count -> require(count >= 0) })
            })
    }
    private fun body(draft: TripDraft, create: Boolean) = buildJsonObject {
        put("name", draft.name); put("starts_on", draft.startsOn); put("ends_on", draft.endsOn); put("timezone", draft.timezone)
        if (create) {
            put("readiness_policy_version", "trip-readiness-v1")
            // Omitted visibility is canonically private and preserves pre-public durable request bodies.
            if (draft.visibility == TripVisibility.PUBLIC) put("visibility", draft.visibility.wire)
            draft.approvedDescription?.let { put("approved_description", it) }
        }
        if (draft.visibility == TripVisibility.PUBLIC) {
            draft.registrationOpensAt?.let { put("registration_opens_at", it) }
            draft.registrationClosesAt?.let { put("registration_closes_at", it) }
        }
    }.toString()
    private fun validDraft(draft: TripDraft) = draft.name.isNotBlank() && visibleCharacterCount(draft.name) <= 120 && draft.timezone.isNotBlank() && draft.timezone.length <= 64 &&
        validTripDate(draft.startsOn) && validTripDate(draft.endsOn) && draft.endsOn >= draft.startsOn

    /** Public timestamps remain server-validated instants; private intents never carry registration. */
    private fun validCreation(draft: TripDraft): Boolean = when (draft.visibility) {
        TripVisibility.PRIVATE -> draft.registrationOpensAt == null && draft.registrationClosesAt == null && draft.approvedDescription == null
        TripVisibility.PUBLIC -> runCatching {
            val opens = kotlin.time.Instant.parse(requireNotNull(draft.registrationOpensAt))
            val closes = kotlin.time.Instant.parse(requireNotNull(draft.registrationClosesAt))
            opens < closes
        }.getOrDefault(false)
        TripVisibility.UNKNOWN -> false
    }

    /** PATCH never changes type; omitted registration preserves the current server range for legacy intents. */
    private fun validUpdateRegistration(draft: TripDraft) =
        if (draft.registrationOpensAt == null && draft.registrationClosesAt == null) true
        else draft.visibility == TripVisibility.PUBLIC && validCreation(draft)
}
private class MemoryTripRecoveryStore : TripRecoveryStore {
    private var record: String? = null
    override fun read() = record
    override fun write(record: String) { this.record = record }
    override fun clear() { record = null }
}
private fun <T> CatalogResult<*>.tripFailure(): CatalogResult<T> = when (this) {
    is CatalogResult.HttpError -> this
    is CatalogResult.Maintenance -> this
    CatalogResult.NetworkError -> CatalogResult.NetworkError
    else -> CatalogResult.InvalidResponse
}
private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.also { require(it.isString) }.content
private fun JsonObject.optional(key: String) = get(key)?.takeUnless { it is JsonNull }?.let { text(key) } ?: ""
private fun JsonObject.integer(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }.int

/** Validates Gregorian calendar dates without applying a device timezone to Trip dates. */
internal fun validTripDate(value: String): Boolean {
    if (!Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(value)) return false
    val year = value.take(4).toInt(); val month = value.substring(5, 7).toInt(); val day = value.takeLast(2).toInt()
    if (year < 1 || month !in 1..12) return false
    val maximum = when (month) { 2 -> if (year % 400 == 0 || year % 4 == 0 && year % 100 != 0) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
    return day in 1..maximum
}
