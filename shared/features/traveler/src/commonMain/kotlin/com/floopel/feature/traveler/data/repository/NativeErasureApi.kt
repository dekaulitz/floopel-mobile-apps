package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.feature.auth.*

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.TravelerSession
import kotlinx.serialization.json.*

/** Capability stays in memory, is channel-bound, and never enters normal identity storage. */
internal class NativeErasureApi(private val rest: FloopelRestClient, private val session: TravelerSession) : TravelerErasureRepository {
    private val owner = NativeAuthorizedRequests(rest, session)
    private val json = Json { ignoreUnknownKeys = true }
    private var proof: String? = null
    private var purpose: String? = null
    private var requestKey: String? = null
    private var generation: Long? = null
    override fun clear() { proof = null; purpose = null; requestKey = null; generation = null }
    override suspend fun authenticate(email: String, password: String): CatalogResult<Unit> {
        clear()
        if (email.isBlank() || password.isBlank()) return CatalogResult.HttpError(422)
        val epoch = session.identityGeneration
        val body = buildJsonObject { put("method", "password"); put("email", email.trim()); put("password", password) }.toString()
        val path = "/api/mobile/v1/auth/account-deletion-context"
        val response = if (session.view.value.signedIn) owner.execute(RestMethod.POST, path, body, expectedGeneration = epoch)
            else rest.execute(RestMethod.POST, path, body)
        return decode(response) {
            require(epoch == session.identityGeneration)
            val value = it.jsonObject
            val token = value.text("context_token").also { raw -> require(raw.isNotBlank()) }
            val action = value.text("purpose").also { action -> require(action in setOf("request", "cancel", "status")) }
            value.text("expires_at")
            proof = token; purpose = action; generation = epoch
            Unit
        }
    }
    override suspend fun state(): CatalogResult<AccountErasureState> {
        if (!session.view.value.signedIn && proof == null) {
            session.verifiedAccountRecovery()?.let { (epoch, recovery) ->
                proof = recovery.contextToken; purpose = recovery.purpose; generation = epoch
            }
        }
        val token = proof
        val response = if (token == null || session.view.value.signedIn) owner.execute(RestMethod.GET, "/api/apps/v1/me/account-deletion")
            else restricted(RestMethod.GET, "/api/apps/v1/me/account-deletion")
        return decode(response, ::readState)
    }
    override suspend fun request(key: String): CatalogResult<AccountErasureState> {
        if (purpose != "request" || key.length !in 16..512) return CatalogResult.HttpError(401)
        if (requestKey != null && requestKey != key) return CatalogResult.HttpError(409)
        val headers = mapOf("X-Account-Deletion-Context" to (proof ?: return CatalogResult.HttpError(401)), "Idempotency-Key" to key)
        val first = requestKey == null
        requestKey = key
        val response = if (first) owner.execute(RestMethod.POST, "/api/apps/v1/me/account-deletion", headers = headers, expectedGeneration = generation)
            else restricted(RestMethod.POST, "/api/apps/v1/me/account-deletion", key)
        return decode(response, ::readState)
    }
    override suspend fun cancel(key: String): CatalogResult<AccountErasureState> {
        if (purpose != "cancel" || key.length !in 16..512) return CatalogResult.HttpError(401)
        return decode(restricted(RestMethod.POST, "/api/apps/v1/me/account-deletion/cancellation", key), ::readState).also {
            if (it is CatalogResult.Ready && it.value.status == "active") session.clearAccountRecovery()
        }
    }
    private suspend fun restricted(method: RestMethod, path: String, key: String? = null): ApiOutcome {
        val token = proof ?: return ApiOutcome.HttpFailure(401)
        if (generation != session.identityGeneration) { clear(); return ApiOutcome.HttpFailure(401) }
        return rest.execute(method, path, headers = mapOf("X-Account-Deletion-Context" to token) + (key?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap()))
    }
    private fun readState(element: JsonElement): AccountErasureState {
        val value = element.jsonObject
        val status = value.text("status").also { require(it in setOf("active", "pending", "erasing")) }
        val eligibility = value["eligibility"] as? JsonObject
        return AccountErasureState(status, value["grace_expires_at"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
            value.flag("can_cancel"), eligibility?.flag("can_request") ?: false,
            eligibility?.get("blocking_reason")?.takeUnless { it is JsonNull }?.jsonPrimitive?.content)
    }
    private fun <T> decode(result: ApiOutcome, project: (JsonElement) -> T): CatalogResult<T> = when (result) {
        is ApiOutcome.Success -> runCatching { val envelope = json.parseToJsonElement(result.responseBody).jsonObject; require(envelope["error"] is JsonNull && envelope["meta"]!!.jsonObject.text("code").isNotBlank()); CatalogResult.Ready(project(envelope.getValue("data"))) }.getOrElse { CatalogResult.InvalidResponse }
        is ApiOutcome.HttpFailure -> CatalogResult.HttpError(result.status, result.retryAfterSeconds)
        is ApiOutcome.Unavailable -> CatalogResult.Maintenance(result.retryAfterSeconds)
        ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
    }
}
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.also { require(it.isString) }.content
private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }.boolean
