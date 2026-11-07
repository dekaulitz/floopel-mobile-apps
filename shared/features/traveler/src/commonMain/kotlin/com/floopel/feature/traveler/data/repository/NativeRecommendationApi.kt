package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.feature.auth.*

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.TravelerSession
import kotlinx.serialization.json.*

/** Personalized reads are owned by the same identity as Account and Quest; no public fallback leaks the previous viewer. */
internal class NativeRecommendationApi(rest: FloopelRestClient, private val session: TravelerSession, private val catalog: AppsTravelerCatalog) : TravelerRecommendationRepository {
    private val http = NativeAuthorizedRequests(rest, session)
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun load(locale: String): CatalogResult<TravelerRecommendation> {
        val generation = session.identityGeneration
        val selection = http.execute(RestMethod.GET, "/api/apps/v1/me/interests", expectedGeneration = generation)
        if (selection !is ApiOutcome.Success) return selection.failure()
        val interests = runCatching { envelope(selection.responseBody).getValue("interest_ids").jsonArray.map { it.jsonPrimitive.also { value -> require(value.isString) }.content } }.getOrNull()
            ?: return CatalogResult.InvalidResponse
        if (interests.isEmpty()) return CatalogResult.Ready(TravelerRecommendation.NeedsInterests)
        return when (val result = http.execute(RestMethod.GET, "/api/apps/v1/collections/for-you?locale=${if(locale == "en") "en" else "id"}&page=1&per_page=20", expectedGeneration = generation)) {
            is ApiOutcome.Success -> if (result.status == 204) CatalogResult.Ready(TravelerRecommendation.NoMatch) else runCatching {
                val data = envelope(result.responseBody)
                val collection = requireNotNull(catalog.readRecommendation(data))
                val matched = data.getValue("matched_interests").jsonArray.map { it.jsonPrimitive.also { primitive -> require(primitive.isString) }.content }
                CatalogResult.Ready(if (collection.related.isEmpty()) TravelerRecommendation.NoMatch else TravelerRecommendation.Matched(collection, matched))
            }.getOrElse { CatalogResult.InvalidResponse }
            else -> result.failure()
        }
    }
    private fun envelope(body: String): JsonObject = json.parseToJsonElement(body).jsonObject.let {
        require(it["error"] is JsonNull && it["meta"]!!.jsonObject.getValue("code").jsonPrimitive.content.isNotBlank())
        it.getValue("data").jsonObject
    }
}
private fun ApiOutcome.failure(): CatalogResult<Nothing> = when (this) {
    is ApiOutcome.HttpFailure -> CatalogResult.HttpError(status, retryAfterSeconds)
    is ApiOutcome.Unavailable -> CatalogResult.Maintenance(this.retryAfterSeconds)
    ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
    else -> CatalogResult.InvalidResponse
}
