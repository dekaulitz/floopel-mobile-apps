package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.feature.auth.*

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.TravelerSession
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.*
import com.floopel.feature.traveler.data.remote.canonicalAppsFailureCode
import com.floopel.feature.traveler.data.remote.canonicalAppsFailureIssues
import kotlin.time.Clock

/** Existing publication/share/media contracts. All owner writes are single attempts. */
internal class NativeItineraryDistributionApi(private val rest: FloopelRestClient, private val session: TravelerSession,
    private val recovery: TripRecoveryStore? = null,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val mediaUploads: NativeMediaUploads? = null) : TravelerItineraryDistributionRepository {
    private val http = NativeAuthorizedRequests(rest, session)
    private val json = Json { ignoreUnknownKeys = true }
    private val journal = recovery?.let { NativeCommandJournal(http, session, it, now) }
    override suspend fun pendingPublication(id: String): CatalogResult<ItineraryPublicationReview?> {
        val actor = actor()
        if (actor !is CatalogResult.Ready) return actor.failure()
        return runCatching {
            val record = recovery?.read()?.let { json.parseToJsonElement(it).jsonObject }
            if (record == null || record.text("profile_id") != actor.value || record.text("path") != path(id) + "/publish") CatalogResult.Ready(null)
            else {
                val body = json.parseToJsonElement(record.text("body")).jsonObject
                CatalogResult.Ready(ItineraryPublicationReview(body.text("expected_updated_at"), body.flag("allow_clone"),
                    body.flag("public_note_reviewed"), body.flag("pii_acknowledged")))
            }
        }.getOrElse { CatalogResult.InvalidResponse }
    }
    override suspend fun read(id: String) = request(RestMethod.GET, path(id), decode = ::distribution)
    override suspend fun publish(id: String, review: ItineraryPublicationReview, key: String): CatalogResult<ItineraryDistribution> {
        if (!review.notesReviewed || !review.piiAcknowledged || review.expectedUpdatedAt.isBlank()) return CatalogResult.HttpError(422)
        return request(RestMethod.POST, path(id) + "/publish", buildJsonObject {
            put("visibility", "public"); put("allow_clone", review.allowClone); put("public_note_reviewed", review.notesReviewed)
            put("pii_acknowledged", review.piiAcknowledged); put("readiness_policy_version", "v1"); put("expected_updated_at", review.expectedUpdatedAt)
        }.toString(), key, ::distribution)
    }
    override suspend fun unpublish(id: String, key: String) = request(RestMethod.POST, path(id) + "/unpublish", key = key, decode = ::distribution)
    override suspend fun clonePolicy(id: String, allow: Boolean, key: String) = request(RestMethod.PUT, path(id) + "/clone-policy", buildJsonObject { put("allow_clone", allow) }.toString(), key, ::distribution)
    override suspend fun shareLink(id: String) = request(RestMethod.GET, path(id) + "/share-link", decode = ::link)
    override suspend fun rotateShareLink(id: String, key: String) = request(RestMethod.POST, path(id) + "/share-link", key = key, decode = ::link)
    override suspend fun revokeShareLink(id: String, key: String) = request(RestMethod.DELETE, path(id) + "/share-link", key = key) { Unit }
    override suspend fun cover(id: String) = request(RestMethod.GET, path(id) + "/cover", decode = ::coverState)
    override suspend fun uploadCover(id: String, bytes: ByteArray, mimeType: String, key: String): CatalogResult<ItineraryCoverStatus> {
        if (bytes.isEmpty() || bytes.size > 5 * 1024 * 1024 || mimeType !in setOf("image/jpeg", "image/png", "image/webp") || key.isBlank()) return CatalogResult.HttpError(422)
        if (mediaUploads != null) return mediaUploads.upload(path(id) + "/cover", bytes, mimeType, key) { decode(it, ::coverState) }
        return decode(http.upload("/api/apps/v1" + path(id) + "/cover", bytes, mimeType, key), ::coverState)
    }
    private fun path(id: String): String { require(id.isNotBlank()); return "/itineraries/" + id.encodeURLPathPart() }
    /** Reads publication controls without treating removed scalar geography as current data. */
    private fun distribution(element: JsonElement): ItineraryDistribution {
        val value = element.jsonObject
        val visibility = value.text("visibility").also { require(it in setOf("private", "public", "unlisted")) }
        val status = value.optional("distribution_status") ?: "active"
        require(status in setOf("active", "moderation_hidden"))
        val cover = media(value["cover_image"])
        return ItineraryDistribution(value.text("id"), value.text("title"), value.optional("description") ?: "", visibility,
            value.flagOrMissing("allow_clone"), status, value.flagOrMissing("has_unpublished_changes"), value.optional("public_slug"),
            value.optional("updated_at"), if (cover != null) listOf(cover) else emptyList(), value.number("stop_count")?.also { require(it >= 0) },
            coverPhotos = catalogMediaPhoto(value["cover_image"], rest, value.text("title"))?.let(::listOf).orEmpty(), locations = value.readLocations())
    }
    private fun media(element: JsonElement?): String? {
        return catalogMediaPhoto(element, rest, "")?.url
    }
    private fun link(element: JsonElement) = element.jsonObject.let {
        val status = it.text("status").also { state -> require(state in setOf("active", "revoked")) }
        ItineraryShareLink(it.text("id"), status == "active", it.number("generation")!!.also { generation -> require(generation >= 1) }, it.text("token_hint"),
            it.optional("url")?.also { url -> require(url.startsWith("https://") || Regex("http://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?/.*").matches(url)) })
    }
    private fun coverState(element: JsonElement) = element.jsonObject.let {
        ItineraryCoverStatus(it.text("media_id"), CoverProcessing.entries.single { state -> state.wire == it.text("processing_status") }, it.flag("retryable"))
    }
    private suspend fun <T> request(method: RestMethod, path: String, body: String? = null, key: String? = null, decode: (JsonElement) -> T): CatalogResult<T> {
        if (key != null && key.isBlank()) return CatalogResult.HttpError(422)
        if (method == RestMethod.GET || journal == null)
            return decode(http.execute(method, "/api/apps/v1$path", body, key?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap()), decode)
        return journal.execute(method, path, body, key.orEmpty()) { frozen, epoch ->
            decode(http.execute(method, "/api/apps/v1$path", body, mapOf("Idempotency-Key" to frozen), expectedGeneration = epoch), decode)
        }
    }
    private suspend fun actor(): CatalogResult<String> = decode(http.execute(RestMethod.GET, "/api/apps/v1/me")) {
        it.jsonObject.getValue("profile").jsonObject.text("id")
    }
    private fun <T> decode(result: ApiOutcome, mapper: (JsonElement) -> T): CatalogResult<T> = when (result) {
        is ApiOutcome.Success -> runCatching {
            if (result.status == 204) CatalogResult.Ready(mapper(JsonNull)) else {
                val envelope = json.parseToJsonElement(result.responseBody).jsonObject
                require(envelope["error"] is JsonNull && envelope["meta"]!!.jsonObject.text("code").isNotBlank())
                CatalogResult.Ready(mapper(envelope["data"] ?: error("Missing data")))
            }
        }.getOrElse { CatalogResult.InvalidResponse }
        is ApiOutcome.HttpFailure -> CatalogResult.HttpError(result.status, result.retryAfterSeconds,
            result.code ?: canonicalAppsFailureCode(result.jsonErrorBody), canonicalAppsFailureIssues(result.jsonErrorBody))
        is ApiOutcome.Unavailable -> CatalogResult.Maintenance(result.retryAfterSeconds)
        ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
    }
}
private fun <T> CatalogResult<T>.failure(): CatalogResult<Nothing> = when (this) {
    is CatalogResult.HttpError -> this
    is CatalogResult.Maintenance -> this
    CatalogResult.InvalidResponse -> CatalogResult.InvalidResponse
    CatalogResult.NetworkError -> CatalogResult.NetworkError
    is CatalogResult.Ready -> error("Expected failure")
}
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.also { require(it.isString) }.content
private fun JsonObject.optional(key: String) = get(key)?.takeUnless { it is JsonNull }?.let { text(key) }
private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }.boolean
private fun JsonObject.flagOrMissing(key: String) = if (containsKey(key)) flag(key) else false
private fun JsonObject.number(key: String) = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.also { require(!it.isString) }?.int
