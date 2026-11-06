package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.*

import com.floopel.feature.traveler.domain.model.CatalogResult
import com.floopel.feature.traveler.domain.model.SavedSpotPage
import com.floopel.feature.traveler.feature.auth.TravelerSession
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Public owner fields only from GET /api/apps/v1/me. */
internal data class TravelerAccount(
    val username: String,
    val firstName: String,
    val hasPendingReconsent: Boolean,
)

/** Native Bearer consumer of existing Apps Saved Spot contracts. */
internal class NativeSavedSpotApi(
    private val rest: FloopelRestClient,
    private val session: TravelerSession,
    private val catalog: AppsTravelerCatalog,
    private val consent: com.floopel.feature.traveler.domain.repository.TravelerConsentGate? = null,
    private val locale: String = "id",
) : TravelerSavedSpotRepository {
    private val json = Json { ignoreUnknownKeys = true }
    private val base = "/api/apps/v1"
    private val authorizedRequests = NativeAuthorizedRequests(rest, session)

    suspend fun account(): CatalogResult<TravelerAccount> {
        return when (val response = authorized(RestMethod.GET, "/me")) {
            is ApiOutcome.Success -> {
                val data = validData(response.responseBody) as? JsonObject
                    ?: return CatalogResult.InvalidResponse
                val profile = data["profile"] as? JsonObject ?: return CatalogResult.InvalidResponse
                val username = profile.text("username") ?: return CatalogResult.InvalidResponse
                val consent = data["pending_reconsent"] as? JsonArray
                    ?: return CatalogResult.InvalidResponse
                CatalogResult.Ready(
                    TravelerAccount(
                        username = username,
                        firstName = profile.text("first_name") ?: username,
                        hasPendingReconsent = consent.isNotEmpty(),
                    ),
                )
            }
            else -> failure(response)
        }
    }

    override suspend fun savedSpots(page: Int): CatalogResult<SavedSpotPage> = readSavedSpots(page, true)

    private suspend fun readSavedSpots(page: Int, resolveLocations: Boolean): CatalogResult<SavedSpotPage> {
        val epoch = session.identityGeneration
        if (page < 1) return CatalogResult.HttpError(422)
        return when (val response = authorized(RestMethod.GET, "/me/saved-spots?per_page=20&page=$page")) {
            is ApiOutcome.Success -> {
                val data = validData(response.responseBody) as? JsonArray
                    ?: return CatalogResult.InvalidResponse
                // Spot projection is reused from public catalog mapping, not a new DTO.
                val spots = data.map { record ->
                    val item = record as? JsonObject ?: return CatalogResult.InvalidResponse
                    val spot = item["spot"] ?: return CatalogResult.InvalidResponse
                    catalog.readSavedSpot(spot) ?: return CatalogResult.InvalidResponse
                }
                val envelope = json.parseToJsonElement(response.responseBody) as? JsonObject
                val pagination = (envelope?.get("meta") as? JsonObject)?.get("pagination") as? JsonObject
                    ?: return CatalogResult.InvalidResponse
                val currentPage = (pagination["page"] as? JsonPrimitive)?.intOrNull
                    ?: return CatalogResult.InvalidResponse
                val totalPages = (pagination["total_pages"] as? JsonPrimitive)?.intOrNull
                    ?: return CatalogResult.InvalidResponse
                if (currentPage != page || totalPages < 0 || (spots.isNotEmpty() && currentPage > totalPages)) {
                    return CatalogResult.InvalidResponse
                }
                val projected = if (resolveLocations) catalog.withSpotLocations(spots, locale) else spots
                if (session.identityGeneration != epoch) return CatalogResult.HttpError(401)
                CatalogResult.Ready(SavedSpotPage(projected, currentPage, totalPages))
            }
            else -> failure(response)
        }
    }

    /** Resolve membership through existing paginated Saved Spots, never assume an absent first-page item is unsaved. */
    override suspend fun isSaved(spotId: String): CatalogResult<Boolean> {
        val epoch = session.identityGeneration
        var page = 1
        while (true) {
            val result = readSavedSpots(page, false)
            if (session.identityGeneration != epoch) return CatalogResult.HttpError(401)
            when (result) {
                is CatalogResult.Ready -> {
                    if (result.value.spots.any { it.id == spotId }) return CatalogResult.Ready(true)
                    if (page >= result.value.totalPages) return CatalogResult.Ready(false)
                    page++
                }
                is CatalogResult.HttpError -> return result
                is CatalogResult.Maintenance -> return result
                CatalogResult.NetworkError -> return CatalogResult.NetworkError
                CatalogResult.InvalidResponse -> return CatalogResult.InvalidResponse
            }
        }
    }

    /**
     * Enforce the Web re-consent gate before writes; server stays authoritative.
     * PUT/DELETE are idempotent, no optimistic "success" without HTTP 204.
     */
    override suspend fun setSaved(spotId: String, saved: Boolean): CatalogResult<Boolean> {
        val epoch = session.identityGeneration
        if (spotId.isBlank()) return CatalogResult.HttpError(422)
        when (val me = account()) {
            is CatalogResult.Ready -> if (me.value.hasPendingReconsent) {
                when (val decision = consent?.ensure() ?: CatalogResult.HttpError(409)) {
                    is CatalogResult.Ready -> Unit
                    is CatalogResult.HttpError -> return decision
                    is CatalogResult.Maintenance -> return decision
                    CatalogResult.NetworkError -> return CatalogResult.NetworkError
                    else -> return CatalogResult.InvalidResponse
                }
            }
            else -> return when (me) {
                is CatalogResult.HttpError -> me
                is CatalogResult.Maintenance -> me
                CatalogResult.NetworkError -> CatalogResult.NetworkError
                else -> CatalogResult.InvalidResponse
            }
        }
        val path = "/spots/" + spotId.encodeURLPathPart() + "/save"
        val result = authorized(if (saved) RestMethod.PUT else RestMethod.DELETE, path, epoch)
        return when (result) {
            is ApiOutcome.Success -> if (result.status == 204) {
                CatalogResult.Ready(saved)
            } else CatalogResult.InvalidResponse
            else -> failure(result)
        }
    }

    /**
     * 401 can be followed by exactly one single-flight native refresh. Never retry
     * uncertain non-idempotent mutations; no browser cookies or CSRF are attached.
     */
    private suspend fun authorized(method: RestMethod, path: String, expectedGeneration: Long? = null): ApiOutcome =
        authorizedRequests.execute(method, base + path, expectedGeneration = expectedGeneration)

    private fun validData(body: String): kotlinx.serialization.json.JsonElement? {
        val envelope = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return null
        if (envelope["error"] !is JsonNull ||
            (envelope["meta"] as? JsonObject)?.text("code").isNullOrBlank()
        ) return null
        return envelope["data"]
    }

    private fun <T> failure(response: ApiOutcome): CatalogResult<T> = when (response) {
        is ApiOutcome.HttpFailure -> CatalogResult.HttpError(response.status, response.retryAfterSeconds)
        is ApiOutcome.Unavailable -> CatalogResult.Maintenance(response.retryAfterSeconds)
        ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
        is ApiOutcome.Success -> CatalogResult.InvalidResponse
    }
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.content
