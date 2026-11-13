package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.*

import com.floopel.feature.traveler.domain.model.CatalogResult
import com.floopel.feature.traveler.domain.model.TravelerSettings
import com.floopel.feature.traveler.domain.repository.TravelerSettingsRepository
import com.floopel.feature.traveler.feature.auth.TravelerSession
import com.floopel.feature.traveler.data.remote.canonicalAppsFailureCode
import com.floopel.feature.traveler.data.remote.canonicalAppsFailureIssues
import kotlinx.serialization.json.*

/** Native Bearer adapter for the existing Apps notification/privacy settings contract. */
internal class NativeSettingsApi(rest: FloopelRestClient, session: TravelerSession) : TravelerSettingsRepository {
    private val authorized = NativeAuthorizedRequests(rest, session)
    private val path = "/api/apps/v1/me/settings"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun load(): CatalogResult<TravelerSettings> = request(RestMethod.GET)

    /** PATCH is never retried automatically after an uncertain response. */
    override suspend fun save(settings: TravelerSettings): CatalogResult<TravelerSettings> {
        val body = buildJsonObject {
            put("notifications", buildJsonObject {
                settings.tripUpdates?.let { put("trip_updates", it) }
                settings.marketing?.let { put("marketing", it) }
                settings.product?.let { put("product", it) }
            })
            put("privacy", buildJsonObject {
                settings.profileDiscoverable?.let { put("profile_discoverable", it) }
            })
        }
        return request(RestMethod.PATCH, body.toString())
    }

    private suspend fun request(method: RestMethod, body: String? = null): CatalogResult<TravelerSettings> {
        return when (val result = authorized.execute(method, path, body)) {
            is ApiOutcome.Success -> {
                val envelope = runCatching { json.parseToJsonElement(result.responseBody) as? JsonObject }.getOrNull()
                    ?: return CatalogResult.InvalidResponse
                if (envelope["error"] !is JsonNull ||
                    (envelope["meta"] as? JsonObject)?.get("code") !is JsonPrimitive) return CatalogResult.InvalidResponse
                val data = envelope["data"] as? JsonObject ?: return CatalogResult.InvalidResponse
                if (listOf("notifications", "privacy").any { key ->
                    data[key]?.let { it !is JsonObject } == true
                }) return CatalogResult.InvalidResponse
                val notifications = data["notifications"] as? JsonObject
                val privacy = data["privacy"] as? JsonObject
                val values = listOf(notifications?.get("trip_updates"), notifications?.get("marketing"),
                    notifications?.get("product"), privacy?.get("profile_discoverable"))
                if (values.any { it != null && (it !is JsonPrimitive || it.isString || it.booleanOrNull == null) }) {
                    return CatalogResult.InvalidResponse
                }
                CatalogResult.Ready(TravelerSettings(
                    notifications?.boolean("trip_updates"), notifications?.boolean("marketing"),
                    notifications?.boolean("product"), privacy?.boolean("profile_discoverable"),
                ))
            }
            is ApiOutcome.HttpFailure -> CatalogResult.HttpError(result.status, result.retryAfterSeconds,
                result.code ?: canonicalAppsFailureCode(result.jsonErrorBody), canonicalAppsFailureIssues(result.jsonErrorBody))
            is ApiOutcome.Unavailable -> CatalogResult.Maintenance(result.retryAfterSeconds)
            ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
        }
    }
}

private fun JsonObject.boolean(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull
