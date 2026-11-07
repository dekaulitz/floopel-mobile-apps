package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.data.remote.canonicalAppsFailureCode
import com.floopel.feature.traveler.data.remote.requireCanonical
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerQuestSharingRepository
import com.floopel.feature.traveler.feature.auth.TravelerSession
import kotlinx.serialization.json.*

/** Existing owner/public reads provide fresh facts; sharing adds no backend endpoint or storage. */
internal class NativeQuestSharingApi(rest: FloopelRestClient, private val session: TravelerSession) : TravelerQuestSharingRepository {
    private val requests = NativeAuthorizedRequests(rest, session)
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun sharingFacts(slug: String, locale: String): CatalogResult<QuestShareFacts?> {
        if (!slug.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")) || locale !in setOf("id", "en")) return CatalogResult.HttpError(422)
        val generation = session.identityGeneration
        val ownerReply = read<QuestShareOwnerDto>("/quests/$slug/me?locale=$locale", generation)
        if (ownerReply !is CatalogResult.Ready) return ownerReply.failure()
        val owner = ownerReply.value
        val facts = runCatching { require(owner.slug == slug); owner.ownerFacts() }.getOrElse { return CatalogResult.InvalidResponse }
        if (owner.status != "not_completed" || owner.hidden != false) return CatalogResult.Ready(facts)
        val publicReply = read<QuestSharePublicDto>("/quests/$slug?locale=$locale", generation)
        if (publicReply !is CatalogResult.Ready) return publicReply.failure()
        return runCatching { CatalogResult.Ready(publicReply.value.invitationFacts(owner)) }.getOrElse { CatalogResult.InvalidResponse }
    }

    private suspend inline fun <reified T> read(path: String, generation: Long): CatalogResult<T> =
        when (val reply = requests.execute(RestMethod.GET, "/api/apps/v1/gamification$path", headers = mapOf("Cache-Control" to "no-cache"), expectedGeneration = generation)) {
            is ApiOutcome.Success -> runCatching {
                require(reply.status == 200)
                val envelope = json.parseToJsonElement(reply.responseBody).jsonObject
                val meta = json.decodeFromJsonElement<AppsMetaDto>(envelope.getValue("meta"))
                meta.requireCanonical()
                require(envelope.getValue("error") == JsonNull && meta.code == "OK")
                CatalogResult.Ready(json.decodeFromJsonElement<T>(envelope.getValue("data")))
            }.getOrElse { CatalogResult.InvalidResponse }
            is ApiOutcome.HttpFailure -> CatalogResult.HttpError(reply.status, reply.retryAfterSeconds, reply.code ?: canonicalAppsFailureCode(reply.jsonErrorBody))
            is ApiOutcome.Unavailable -> CatalogResult.Maintenance(reply.retryAfterSeconds)
            ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
        }
}

private fun CatalogResult<*>.failure(): CatalogResult<Nothing> = when (this) {
    is CatalogResult.HttpError -> this
    is CatalogResult.Maintenance -> this
    CatalogResult.NetworkError -> CatalogResult.NetworkError
    else -> CatalogResult.InvalidResponse
}
