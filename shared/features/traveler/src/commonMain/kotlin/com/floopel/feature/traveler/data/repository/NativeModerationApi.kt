package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.feature.auth.*

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.TravelerSession
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.*

/** Existing Apps report/appeal contracts, without admin endpoints or sensitive response fields. */
internal class NativeModerationApi(rest: FloopelRestClient, session: TravelerSession,
    tripReportRecovery: TripRecoveryStore = MemoryTripReportRecoveryStore()) : TravelerModerationRepository {
    private val http = NativeAuthorizedRequests(rest, session)
    private val json = Json { ignoreUnknownKeys = true }
    private val tripReports = NativeTripReportRequests(rest, session, tripReportRecovery)
    override suspend fun catalogReport(target: CatalogReportTarget, id: String, reason: CatalogReportReason, details: String, key: String): CatalogResult<ReportReceipt> {
        if (visibleCharacterCount(details) !in 3..1000 || id.isBlank() || key.isBlank()) return CatalogResult.HttpError(422)
        val body = buildJsonObject {
            put("target_type", target.wire); put("target_id", id); put("reason_code", reason.wire); put("details", details.trim()); put("details_format", "plain")
        }
        return request(RestMethod.POST, "/reports", body.toString(), key, decode = ::receipt)
    }
    override suspend fun itineraryPreview(id: String) = request(RestMethod.GET, "/itineraries/${id.encodeURLPathPart()}/report-preview") {
        val value = it.jsonObject
        ItineraryReportPreview(value.text("id"), value.text("title"), value.text("description"), value.integer("stop_count").also { count -> require(count >= 0) })
    }
    override suspend fun itineraryReport(id: String, reason: ItineraryReportReason, details: String, key: String): CatalogResult<ReportReceipt> {
        if (visibleCharacterCount(details) > 1000 || id.isBlank() || key.isBlank()) return CatalogResult.HttpError(422)
        return request(RestMethod.POST, "/itineraries/${id.encodeURLPathPart()}/reports", buildJsonObject {
            put("reason_code", reason.wire); put("details", details.trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull)
        }.toString(), key, decode = ::receipt)
    }
    /** The public Trip evidence and reporter identity are captured by the server, never supplied by the client. */
    override suspend fun tripReport(id: String, reason: ItineraryReportReason, details: String, key: String): CatalogResult<ReportReceipt> {
        if (!validNotificationId(id) || visibleCharacterCount(details) > 1000 || key.isBlank()) return CatalogResult.HttpError(422)
        return submitTripReport(TripReportIntent(id, "", reason, details.trim(), key))
    }
    override suspend fun submitTripReport(intent: TripReportIntent) = tripReports.submit(intent)
    override suspend fun pendingTripReport(tripId: String) = tripReports.pending(tripId)
    override suspend fun checkTripReport(intent: TripReportIntent) = tripReports.check(intent)
    override suspend fun completeTripReport(intent: TripReportIntent) = tripReports.complete(intent)
    override suspend fun actions() = request(RestMethod.GET, "/me/itinerary-moderation-actions") { value -> value.jsonArray.map { element ->
        val action = element.jsonObject
        OwnerModerationAction(action.text("id"), action.text("itinerary_id"), action.text("itinerary_title"), ModerationActionKind.entries.single { it.wire == action.text("action") },
            action.text("reason_code"), action.text("created_at"), action.flag("appeal_available"), action["appeal"]?.takeUnless { it is JsonNull }?.let(::readAppeal))
    } }
    override suspend fun appeal(actionId: String) = request(RestMethod.GET, appealPath(actionId), decode = ::readAppeal)
    override suspend fun submitAppeal(actionId: String, reason: String, key: String): CatalogResult<TravelerAppeal> {
        if (visibleCharacterCount(reason) !in 10..2000 || actionId.isBlank() || key.isBlank()) return CatalogResult.HttpError(422)
        return request(RestMethod.POST, appealPath(actionId), buildJsonObject { put("reason", reason.trim()) }.toString(), key, decode = ::readAppeal)
    }
    private fun appealPath(id: String) = "/me/itinerary-moderation-actions/${id.encodeURLPathPart()}/appeal"
    private fun receipt(element: JsonElement) = element.jsonObject.let { ReportReceipt(it.text("id"), it.text("status")) }
    private fun readAppeal(element: JsonElement) = element.jsonObject.let {
        TravelerAppeal(it.text("id"), it.text("moderation_action_id"), AppealStatus.entries.single { status -> status.wire == it.text("status") },
            it.text("submitted_at"), it["decided_at"]?.takeUnless { value -> value is JsonNull }?.jsonPrimitive?.also { value -> require(value.isString) }?.content)
    }
    private suspend fun <T> request(method: RestMethod, path: String, body: String? = null, key: String? = null,
        expectedCode: String? = null, decode: (JsonElement) -> T): CatalogResult<T> =
        when (val result = http.execute(method, "/api/apps/v1$path", body, key?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap())) {
            is ApiOutcome.Success -> runCatching {
                val value = json.parseToJsonElement(result.responseBody).jsonObject
                require(value["error"] is JsonNull && value["meta"]!!.jsonObject.text("code").isNotBlank())
                require(expectedCode == null || value["meta"]!!.jsonObject.text("code") == expectedCode)
                CatalogResult.Ready(decode(value["data"] ?: error("Missing data")))
            }.getOrElse { CatalogResult.InvalidResponse }
            is ApiOutcome.HttpFailure -> CatalogResult.HttpError(result.status, result.retryAfterSeconds)
            is ApiOutcome.Unavailable -> CatalogResult.Maintenance(result.retryAfterSeconds)
            ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
        }
}
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.also { require(it.isString) }.content
private fun JsonObject.integer(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }.int
private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }.boolean
