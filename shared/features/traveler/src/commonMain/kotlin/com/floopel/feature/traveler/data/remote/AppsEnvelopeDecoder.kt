package com.floopel.feature.traveler.data.remote

import com.floopel.feature.traveler.data.dto.AppsFailureEnvelopeDto
import com.floopel.feature.traveler.data.dto.AppsMetaDto
import com.floopel.feature.traveler.domain.model.CatalogFieldIssue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val appsEnvelopeJson = Json { ignoreUnknownKeys = true }
private val canonicalAppsCode = Regex("^[A-Z][A-Z0-9]*(?:_[A-Z0-9]+)*$")
private val canonicalAppsId = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/** Validates UUID shape before an identifier is interpolated into an Apps path. */
internal fun validAppsId(value: String): Boolean = canonicalAppsId.matches(value)

/** Metadata constraints follow the canonical contract without normalizing business codes. */
internal fun AppsMetaDto.requireCanonical() {
    require(validAppsId(requestId) && timestamp >= 0 && canonicalAppsCode.matches(code))
}

/** Malformed/HTML failures remain unknown; raw messages, URLs and PII never become UI branch tokens. */
internal fun canonicalAppsFailureCode(body: String?): String? = body?.let {
    runCatching {
        val envelope = appsEnvelopeJson.decodeFromString<AppsFailureEnvelopeDto>(it)
        envelope.meta.requireCanonical()
        require(envelope.data is JsonNull)
        envelope.meta.code
    }.getOrNull()
}

/** Only canonical envelopes expose structured field/code pairs; messages never select client behavior. */
internal fun canonicalAppsFailureIssues(body: String?): List<CatalogFieldIssue> = body?.let {
    runCatching {
        val envelope = appsEnvelopeJson.decodeFromString<AppsFailureEnvelopeDto>(it)
        envelope.meta.requireCanonical()
        require(envelope.data is JsonNull)
        envelope.error.details.orEmpty().mapNotNull { detail ->
            val fields = detail as? JsonObject ?: return@mapNotNull null
            val field = (fields["field"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val code = (fields["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (field.isNullOrBlank() || code.isNullOrBlank()) null else CatalogFieldIssue(field, code)
        }
    }.getOrDefault(emptyList())
}.orEmpty()
