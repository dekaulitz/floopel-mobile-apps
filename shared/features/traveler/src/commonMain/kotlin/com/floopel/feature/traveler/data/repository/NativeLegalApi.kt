package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.feature.auth.*

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.TravelerSession
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.*

/** Resolves each exact pending legal version and echoes its actual served locale/hash. */
internal class NativeLegalApi(private val rest: FloopelRestClient, session: TravelerSession) : TravelerLegalRepository, TravelerLegalDocumentRepository {
    private val owner = NativeAuthorizedRequests(rest, session)
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun current(type: String, locale: String): CatalogResult<LegalDocument> {
        if (type !in setOf("terms","privacy") || locale !in setOf("id","en")) return CatalogResult.HttpError(422)
        val result = rest.execute(RestMethod.GET,"/api/apps/v1/legal/$type?locale=$locale")
        if (result !is ApiOutcome.Success) return result.failure()
        return runCatching {
            val value = envelope(result.responseBody).jsonObject
            require(value.text("doc_type") == type)
            CatalogResult.Ready(LegalDocument(type,value.text("version"),value.text("locale_served").also { require(it in setOf("id","en")) },
                value.text("content_html"),value.text("content_hash").also { require(it.isNotBlank()) }))
        }.getOrElse { CatalogResult.InvalidResponse }
    }
    override suspend fun pending(locale: String): CatalogResult<List<LegalDocument>> {
        if (locale !in setOf("id", "en")) return CatalogResult.HttpError(422)
        val result = owner.execute(RestMethod.GET, "/api/apps/v1/me")
        if (result !is ApiOutcome.Success) return result.failure()
        val pending = runCatching { envelope(result.responseBody).jsonObject["pending_reconsent"]!!.jsonArray }.getOrNull()
            ?: return CatalogResult.InvalidResponse
        val documents = mutableListOf<LegalDocument>()
        for (item in pending) {
            val target = runCatching { item.jsonObject.text("doc_type") to item.jsonObject.text("version") }.getOrNull()
                ?: return CatalogResult.InvalidResponse
            if (target.first !in setOf("terms", "privacy")) return CatalogResult.InvalidResponse
            val document = rest.execute(RestMethod.GET, "/api/apps/v1/legal/${target.first}/${target.second.encodeURLPathPart()}?locale=$locale")
            if (document !is ApiOutcome.Success) return document.failure()
            val parsed = runCatching {
                val value = envelope(document.responseBody).jsonObject
                require(value.text("doc_type") == target.first && value.text("version") == target.second)
                LegalDocument(target.first, target.second, value.text("locale_served").also { require(it in setOf("id", "en")) },
                    value.text("content_html"), value.text("content_hash").also { require(it.isNotBlank()) })
            }.getOrNull() ?: return CatalogResult.InvalidResponse
            documents.add(parsed)
        }
        return CatalogResult.Ready(documents)
    }
    override suspend fun accept(document: LegalDocument, key: String, identityGeneration: Long): CatalogResult<Unit> {
        val body = buildJsonObject {
            put("doc_type", document.type); put("version", document.version); put("locale", document.locale); put("content_hash", document.hash)
        }
        return when (val result = owner.execute(RestMethod.POST, "/api/apps/v1/consent", body.toString(), mapOf("Idempotency-Key" to key), expectedGeneration = identityGeneration)) {
            is ApiOutcome.Success -> runCatching { envelope(result.responseBody); CatalogResult.Ready(Unit) }.getOrElse { CatalogResult.InvalidResponse }
            else -> result.failure()
        }
    }
    private fun envelope(raw: String): JsonElement {
        val value = json.parseToJsonElement(raw).jsonObject
        require(value["error"] is JsonNull && value["meta"]!!.jsonObject.text("code").isNotBlank())
        return value["data"] ?: error("Missing data")
    }
}
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.also { require(it.isString) }.content
private fun <T> ApiOutcome.failure(): CatalogResult<T> = when (this) {
    is ApiOutcome.HttpFailure -> CatalogResult.HttpError(status, retryAfterSeconds)
    is ApiOutcome.Unavailable -> CatalogResult.Maintenance(this.retryAfterSeconds)
    ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
    else -> CatalogResult.InvalidResponse
}
