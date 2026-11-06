package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.data.remote.canonicalAppsFailureCode
import com.floopel.feature.traveler.data.remote.requireCanonical
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerCatalog
import com.floopel.feature.traveler.feature.auth.TravelerSession
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.*

/** Public reads retain their existing adapter; personalized discovery uses the single native identity owner. */
internal class NativeTravelerCatalog(
    publicCatalog: TravelerCatalog,
    private val rest: FloopelRestClient,
    private val session: TravelerSession,
) : TravelerCatalog by publicCatalog {
    private val requests = NativeAuthorizedRequests(rest, session)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun untakenLocationQuests(locationId: String, locale: String, page: Int): CatalogResult<LocationQuestPage> {
        if (!locationId.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) ||
            locale !in setOf("id", "en") || page < 1) return CatalogResult.HttpError(422)
        val generation = session.identityGeneration
        val path = "/api/apps/v1/gamification/me/quests?exclude_taken=true&status=active&locale=$locale" +
            "&location_id=${locationId.encodeURLParameter()}&page=$page&per_page=8"
        return when (val reply = requests.execute(RestMethod.GET, path,
            headers = mapOf("Cache-Control" to "no-cache"), expectedGeneration = generation)) {
            is ApiOutcome.Success -> runCatching {
                require(reply.status == 200)
                val envelope = json.parseToJsonElement(reply.responseBody).jsonObject
                val meta = json.decodeFromJsonElement<AppsMetaDto>(envelope.getValue("meta"))
                meta.requireCanonical()
                require(meta.code == "OK" && envelope.getValue("error") == JsonNull)
                val pageObject = envelope.getValue("meta").jsonObject.getValue("pagination").jsonObject
                for (field in listOf("page", "total_pages")) {
                    val number = pageObject.getValue(field).jsonPrimitive
                    require(!number.isString && number.intOrNull != null)
                }
                val pagination = json.decodeFromJsonElement<QuestPageInfo>(pageObject)
                require(pagination.page == page && pagination.totalPages >= 0)
                val rows = envelope.getValue("data").jsonArray
                for (row in rows) {
                    val taken = row.jsonObject.getValue("is_taken").jsonPrimitive
                    val count = row.jsonObject.getValue("activity_count").jsonPrimitive
                    require(!taken.isString && taken.booleanOrNull == false && !count.isString && count.intOrNull != null)
                }
                val quests = json.decodeFromJsonElement<List<QuestSummary>>(rows)
                // A concurrent admission may shrink the last page while this request is in flight.
                require(quests.size <= 8 && (page <= pagination.totalPages || quests.isEmpty()))
                CatalogResult.Ready(LocationQuestPage(quests.map { it.toUntakenLocationQuest(rest) }, page, pagination.totalPages))
            }.getOrElse { CatalogResult.InvalidResponse }
            is ApiOutcome.HttpFailure -> CatalogResult.HttpError(reply.status, reply.retryAfterSeconds,
                reply.code ?: canonicalAppsFailureCode(reply.jsonErrorBody))
            is ApiOutcome.Unavailable -> CatalogResult.Maintenance(reply.retryAfterSeconds)
            ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
        }
    }
}
