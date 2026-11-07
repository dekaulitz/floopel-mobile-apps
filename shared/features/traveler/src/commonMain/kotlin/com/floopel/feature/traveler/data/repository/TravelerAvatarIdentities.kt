package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.ApiOutcome
import com.floopel.feature.traveler.core.network.FloopelRestClient
import com.floopel.feature.traveler.core.network.RestMethod
import com.floopel.feature.traveler.data.dto.PublicProfileIdentityDto
import com.floopel.feature.traveler.domain.model.TravelerAvatarIdentity
import io.ktor.http.encodeURLQueryComponent
import kotlinx.serialization.json.*

/** Appearance metadata is loaded once per page, never by a composable or once per avatar. */
internal class TravelerAvatarIdentities(private val get: suspend (String) -> ApiOutcome,
    private val media: (String?) -> String?) {
    constructor(rest: FloopelRestClient) : this({ rest.execute(RestMethod.GET, it) }, rest::resolveMediaUrl)

    /** Missing, ineligible or unavailable identities keep the caller's honest initials and unknown level. */
    suspend fun read(usernames: List<String>): Map<String, TravelerAvatarIdentity> {
        val names = usernames.filter(String::isNotBlank).map { it.lowercase() }.distinct()
        val identities = mutableMapOf<String, TravelerAvatarIdentity>()
        for (batch in names.chunked(100)) {
            val path = "/api/apps/v1/profile-identities?usernames=" + batch.joinToString(",").encodeURLQueryComponent()
            val response = get(path) as? ApiOutcome.Success ?: continue
            val values = runCatching { decode(response.responseBody, batch.toSet()) }.getOrNull() ?: continue
            identities.putAll(values)
        }
        return identities
    }

    /** Rejects unexpected identities and malformed levels; media uses the shared URL boundary. */
    private fun decode(body: String, requested: Set<String>): Map<String, TravelerAvatarIdentity> {
        val envelope = Json.parseToJsonElement(body).jsonObject
        require(envelope["error"] == JsonNull && envelope["meta"]?.jsonObject?.get("code")?.jsonPrimitive?.content == "OK")
        val json = Json { ignoreUnknownKeys = true }
        return envelope.getValue("data").jsonObject.mapValues { (name, value) ->
            require(name in requested)
            require(!value.jsonObject.getValue("level").jsonPrimitive.isString)
            value.jsonObject["avatar_url"]?.takeUnless { it is JsonNull }?.let { require(it.jsonPrimitive.isString) }
            val dto = json.decodeFromJsonElement<PublicProfileIdentityDto>(value)
            require(dto.level in 1..10)
            TravelerAvatarIdentity(dto.level, media(dto.avatarUrl?.takeIf(String::isNotBlank)))
        }
    }
}
