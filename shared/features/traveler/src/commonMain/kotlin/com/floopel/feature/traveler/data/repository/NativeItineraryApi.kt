package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.feature.auth.*

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.TravelerSession
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import kotlin.time.Clock

/** Existing itinerary operations, with bounded durable POST recovery per ADR-0061/0062. */
internal class NativeItineraryApi(
    private val rest: FloopelRestClient,
    private val session: TravelerSession,
    private val recovery: ItineraryRecoveryStore,
    private val newKey: () -> String,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val consent: TravelerConsentGate? = null,
) : TravelerItineraryRepository {
    private val http = NativeAuthorizedRequests(rest, session)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutation = Mutex()

    override suspend fun mine(): CatalogResult<List<OwnedItinerary>> = read(RestMethod.GET, "/me/itineraries") {
        it.jsonArray.map(::readItinerary)
    }
    override suspend fun detail(id: String): CatalogResult<OwnedItineraryDetail> =
        if (id.isBlank()) CatalogResult.HttpError(422) else read(RestMethod.GET, path(id), decode = ::readDetail)

    override suspend fun create(draft: ItineraryDraft): ItineraryWriteResult<OwnedItineraryDetail> {
        if (draft.title.isBlank()) return ItineraryWriteResult.Failed(CatalogResult.HttpError(422))
        return recovered("/itineraries", draftBody(draft))
    }
    override suspend fun pendingDraft(): CatalogResult<ItineraryDraft?> {
        val actor = actor()
        if (actor !is CatalogResult.Ready) return actor.failure()
        return try {
            val saved = recovery.read()?.let { json.parseToJsonElement(it).jsonObject }
            if (saved == null || saved.text("profile_id") != actor.value || saved.text("path") != "/itineraries") CatalogResult.Ready(null)
            else {
                val body = json.parseToJsonElement(saved.text("body")).jsonObject
                CatalogResult.Ready(ItineraryDraft(body.text("title"), body.optional("description") ?: "", body.optional("cover_media_id")))
            }
        } catch (_: Exception) { CatalogResult.InvalidResponse }
    }
    override suspend fun pendingStop(): CatalogResult<PendingItineraryStop?> {
        val epoch = session.identityGeneration
        val actor = actor(epoch)
        if (actor !is CatalogResult.Ready) return actor.failure()
        return try {
            val saved = recovery.read()?.let { json.parseToJsonElement(it).jsonObject }
            val path = saved?.text("path")
            if (saved == null || saved.text("profile_id") != actor.value || path == null || !Regex("^/itineraries/[^/]+/stops$").matches(path)) CatalogResult.Ready(null)
            else {
                val body = json.parseToJsonElement(saved.text("body")).jsonObject
                CatalogResult.Ready(PendingItineraryStop(path.removePrefix("/itineraries/").removeSuffix("/stops"), body.text("spot_id"), body.number("day")!!.also { require(it >= 1) }))
            }
        } catch (_: Exception) { CatalogResult.InvalidResponse }
    }

    override suspend fun clone(id: String): ItineraryWriteResult<OwnedItineraryDetail> =
        if (id.isBlank()) ItineraryWriteResult.Failed(CatalogResult.HttpError(422)) else recovered(path(id) + "/clone", "{}")

    override suspend fun addStop(id: String, spotId: String, day: Int): ItineraryWriteResult<OwnedItineraryDetail> {
        if (id.isBlank() || spotId.isBlank() || day < 1) return ItineraryWriteResult.Failed(CatalogResult.HttpError(422))
        return recovered(path(id) + "/stops", buildJsonObject { put("spot_id", spotId); put("day", day); put("position", 1) }.toString())
    }

    override suspend fun update(id: String, draft: ItineraryDraft): CatalogResult<OwnedItineraryDetail> =
        if (id.isBlank() || draft.title.isBlank()) CatalogResult.HttpError(422) else read(RestMethod.PUT, path(id), draftBody(draft), decode = ::readMutation)
    override suspend fun delete(id: String) = emptyWrite(RestMethod.DELETE, path(id))
    override suspend fun removeStop(id: String, stopId: String) = emptyWrite(RestMethod.DELETE, path(id) + "/stops/" + stopId.encodeURLPathPart())
    override suspend fun updateStop(id: String, stop: OwnedStop): CatalogResult<OwnedItineraryDetail> {
        // The server appends the first Stop at position 0; preserve its ordering on edits.
        if (stop.day < 1 || stop.position < 0 || (stop.durationMinutes != null && stop.durationMinutes < 0)) return CatalogResult.HttpError(422)
        val body = buildJsonObject {
            put("day", stop.day); put("position", stop.position)
            put("override_note", stop.note.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
            put("override_tips", stop.tips.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
            put("override_duration_min", stop.durationMinutes?.let(::JsonPrimitive) ?: JsonNull)
            put("arrive_time", stop.arriveTime.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
            put("travel_note", stop.travelNote.ifEmpty { null }?.let(::JsonPrimitive) ?: JsonNull)
        }
        return read(RestMethod.PATCH, path(id) + "/stops/" + stop.id.encodeURLPathPart(), body.toString(), decode = ::readDetail)
    }
    override suspend fun reorder(id: String, stops: List<OwnedStop>): CatalogResult<OwnedItineraryDetail> {
        val body = buildJsonObject { put("stops", JsonArray(stops.map { stop -> buildJsonObject {
            put("stop_id", stop.id); put("day", stop.day); put("position", stop.position)
        } })) }
        return read(RestMethod.PUT, path(id) + "/stops", body.toString(), decode = ::readDetail)
    }
    override suspend fun titleDay(id: String, day: Int, title: String): CatalogResult<OwnedItineraryDetail> =
        if (day < 1) CatalogResult.HttpError(422) else read(RestMethod.PUT, path(id) + "/days/$day", buildJsonObject { put("title", title) }.toString(), decode = ::readDetail)
    override suspend fun duplicateDay(id: String, day: Int): CatalogResult<OwnedItineraryDetail> =
        if (day < 1) CatalogResult.HttpError(422) else read(RestMethod.POST, path(id) + "/days/$day/duplicate", decode = ::readDetail)

    /** Explicit new-action control only, after the UI has loaded current authorized plans/stops. */
    override suspend fun startNewAction(): CatalogResult<Unit> {
        if (!mutation.tryLock()) return CatalogResult.HttpError(409)
        return try {
            when (val actor = actor()) {
                is CatalogResult.Ready -> try { recovery.clear(); CatalogResult.Ready(Unit) } catch (_: Exception) { CatalogResult.InvalidResponse }
                else -> actor.failure()
            }
        } finally { mutation.unlock() }
    }

    private suspend fun recovered(path: String, body: String): ItineraryWriteResult<OwnedItineraryDetail> {
        if (!mutation.tryLock()) return ItineraryWriteResult.PendingDifferentAction
        try {
            val epoch = session.identityGeneration
            consent?.let { gate ->
                val decision = gate.ensure()
                if (decision !is CatalogResult.Ready) return ItineraryWriteResult.Failed(decision.failure())
            }
            val actor = actor(epoch)
            if (actor !is CatalogResult.Ready) return ItineraryWriteResult.Failed(actor.failure())
            val key = try {
                val saved = recovery.read()?.let { json.parseToJsonElement(it).jsonObject }
                if (saved != null && saved.text("profile_id") == actor.value) {
                    if (saved.text("path") != path || saved.text("body") != body) return ItineraryWriteResult.PendingDifferentAction
                    saved.text("key")
                } else {
                    val generated = newKey()
                    recovery.write(buildJsonObject {
                        put("profile_id", actor.value); put("path", path); put("body", body); put("key", generated)
                    }.toString())
                    generated
                }
            } catch (_: Exception) { return ItineraryWriteResult.StorageFailure }
            val issuedAt = key.replace("-", "").take(12).toLongOrNull(16)
            if (issuedAt == null || nowMillis() - issuedAt > 86_400_000L) return ItineraryWriteResult.Expired
            val result = read(RestMethod.POST, path, body, mapOf("Idempotency-Key" to key), expectedGeneration = epoch, decode = if (path.endsWith("/stops")) ::readDetail else ::readMutation)
            if (result is CatalogResult.Ready) {
                // Never clear a successor account's record after a late response.
                if (session.identityGeneration == epoch) runCatching { recovery.clear() }
                return ItineraryWriteResult.Completed(result.value)
            }
            // Keep exact input/key for unknown, validation and authorization outcomes; never silently replace intent.
            return ItineraryWriteResult.Failed(result.failure())
        } finally { mutation.unlock() }
    }

    private suspend fun actor(expectedGeneration: Long? = null) = read(RestMethod.GET, "/me", expectedGeneration = expectedGeneration) { it.jsonObject["profile"]!!.jsonObject.text("id") }
    private fun path(id: String) = "/itineraries/" + id.encodeURLPathPart()
    private fun draftBody(draft: ItineraryDraft) = buildJsonObject { put("title", draft.title); put("description", draft.description); put("cover_media_id", draft.coverMediaId?.let(::JsonPrimitive) ?: JsonNull) }.toString()
    private suspend fun emptyWrite(method: RestMethod, path: String): CatalogResult<Unit> = when (val result = http.execute(method, "/api/apps/v1$path")) {
        is ApiOutcome.Success -> if (result.status == 204) CatalogResult.Ready(Unit) else CatalogResult.InvalidResponse
        else -> result.failure()
    }
    private suspend fun <T> read(method: RestMethod, path: String, body: String? = null, headers: Map<String, String> = emptyMap(), expectedGeneration: Long? = null, decode: (JsonElement) -> T): CatalogResult<T> {
        return when (val result = http.execute(method, "/api/apps/v1$path", body, headers, expectedGeneration)) {
            is ApiOutcome.Success -> runCatching {
                val envelope = json.parseToJsonElement(result.responseBody).jsonObject
                require(envelope["error"] is JsonNull)
                require(envelope["meta"]!!.jsonObject.text("code").isNotBlank())
                CatalogResult.Ready(decode(envelope["data"] ?: error("Missing data")))
            }.getOrElse { CatalogResult.InvalidResponse }
            else -> result.failure()
        }
    }
    /** Reads all working or publication locations and rejects missing cutover fields. */
    private fun readItinerary(element: JsonElement) = element.jsonObject.let { value ->
        OwnedItinerary(value.text("id"), value.text("title"), value.number("day_count") ?: 0, value.number("stop_count") ?: 0,
            value.readLocations(), value.optional("description") ?: "", value.strictFlag("is_owner"), value.optional("source") == "curated", value.optional("cover_media_id"),
            visibility = value.optional("visibility"), coverUrl = itineraryCoverUrl(value["cover_image"]), isCurated = value.optional("source") == "curated",
            coverPhoto = catalogMediaPhoto(value["cover_image"], rest, value.text("title")),
            previewPhotos = (value["preview_images"] as? JsonArray)?.mapNotNull { catalogMediaPhoto(it, rest, value.text("title")) }?.distinctBy { it.url }?.take(4).orEmpty(),
            previewUrls = (value["preview_images"] as? JsonArray)?.mapNotNull(::itineraryCoverUrl)?.distinct()?.take(4).orEmpty())
    }
    private fun itineraryCoverUrl(element: JsonElement?): String? {
        return catalogMediaPhoto(element, rest, "")?.url
    }
    /** Create/clone/metadata responses are summaries, not a failed detail projection. */
    private fun readMutation(element: JsonElement): OwnedItineraryDetail =
        if (element.jsonObject.containsKey("stops")) readDetail(element)
        else OwnedItineraryDetail(readItinerary(element), emptyList(), stopsLoaded = false)
    private fun readDetail(element: JsonElement): OwnedItineraryDetail {
        val value = element.jsonObject
        val stops = value["stops"]!!.jsonArray.map { entry -> entry.jsonObject.let { stop ->
            OwnedStop(stop.text("id"), stop.text("spot_id"), (stop["spot"] as? JsonObject)?.optional("name") ?: stop.text("spot_id"),
                stop.number("day") ?: error("Missing day"), stop.number("position") ?: error("Missing position"),
                stop.optional("override_note") ?: "", stop.number("override_duration_min"), stop.optional("override_tips") ?: "",
                stop.optional("arrive_time") ?: "", stop.optional("travel_note") ?: "")
        } }.sortedWith(compareBy(OwnedStop::day, OwnedStop::position))
        val days = (value["days"] as? JsonArray)?.associate { it.jsonObject.let { day ->
            (day.number("day") ?: error("Missing day")) to (day.optional("title") ?: "")
        } } ?: emptyMap()
        return OwnedItineraryDetail(readItinerary(element), stops, days)
    }
}
private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.also { require(it.isString) }.content
private fun JsonObject.optional(key: String): String? = get(key)?.takeUnless { it is JsonNull }?.let { text(key) }
private fun JsonObject.number(key: String): Int? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.also { require(!it.isString) }?.int
private fun JsonObject.strictFlag(key: String): Boolean = get(key)?.jsonPrimitive?.also { require(!it.isString) }?.boolean ?: false
private fun <T> ApiOutcome.failure(): CatalogResult<T> = when (this) {
    is ApiOutcome.HttpFailure -> CatalogResult.HttpError(status, retryAfterSeconds)
    is ApiOutcome.Unavailable -> CatalogResult.Maintenance(this.retryAfterSeconds)
    ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
    else -> CatalogResult.InvalidResponse
}
private fun CatalogResult<*>.failure(): CatalogResult<Nothing> = when (this) {
    is CatalogResult.HttpError -> CatalogResult.HttpError(status, retryAfterSeconds)
    is CatalogResult.Maintenance -> this
    CatalogResult.NetworkError -> CatalogResult.NetworkError
    else -> CatalogResult.InvalidResponse
}
