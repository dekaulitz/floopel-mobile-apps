package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.feature.auth.*

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.TravelerSession
import kotlinx.serialization.json.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Capability stays in memory, is channel-bound, and never enters normal identity storage. */
internal class NativeErasureApi(private val rest: FloopelRestClient, private val session: TravelerSession) : TravelerErasureRepository {
    private val owner = NativeAuthorizedRequests(rest, session)
    private val json = Json { ignoreUnknownKeys = true }
    private data class Context(val token: String, val purpose: String, val generation: Long)
    private data class Capability(val revision: Long = 0, val context: Context? = null, val requestKey: String? = null,
        val activePreflight: Boolean = false)
    private val capability = MutableStateFlow(Capability())
    override fun clear() { capability.update { Capability(revision = it.revision + 1) } }
    override suspend fun authenticate(email: String, password: String): CatalogResult<Unit> {
        clear()
        val initial = capability.value
        if (email.isBlank() || password.isBlank()) return CatalogResult.HttpError(422)
        val epoch = session.identityGeneration
        val body = buildJsonObject { put("method", "password"); put("email", email.trim()); put("password", password) }.toString()
        val path = "/api/mobile/v1/auth/account-deletion-context"
        val response = if (session.view.value.signedIn) ownerCapability(path, body, epoch = epoch)
            else rest.execute(RestMethod.POST, path, body)
        return decode<Unit>(response) {
            require(epoch == session.identityGeneration)
            val value = it.jsonObject
            val token = value.text("context_token").also { raw -> require(raw.isNotBlank()) }
            val action = value.text("purpose").also { action -> require(action in setOf("request", "cancel", "status")) }
            kotlin.time.Instant.parse(value.text("expires_at"))
            require(capability.compareAndSet(initial, initial.copy(context = Context(token, action, epoch))))
            Unit
        }
    }
    override suspend fun state(): CatalogResult<AccountErasureState> {
        val initial = capability.value
        if (!session.view.value.signedIn && initial.context == null) {
            session.verifiedAccountRecovery()?.let { (epoch, recovery) ->
                capability.compareAndSet(initial, initial.copy(context = Context(recovery.contextToken, recovery.purpose, epoch)))
            }
        }
        val current = capability.value
        if (current.context != null && current.context.generation != session.identityGeneration) {
            capability.compareAndSet(current, Capability(revision = current.revision + 1)); return CatalogResult.HttpError(401)
        }
        // Active preflight needs the native session. After a request was sent, its
        // response may have been lost and that session revoked: recover with proof.
        val response = if (current.context == null || session.view.value.signedIn && current.requestKey == null)
            owner.execute(RestMethod.GET, "/api/apps/v1/me/account-deletion", expectedGeneration = session.identityGeneration)
            else restricted(RestMethod.GET, "/api/apps/v1/me/account-deletion").let { restricted ->
                // An uncommitted/rejected request leaves an active account, whose preflight
                // forbids restricted-only reads. Reconcile with its still-valid session.
                if (restricted is ApiOutcome.HttpFailure && restricted.status == 403 && session.view.value.signedIn)
                    owner.execute(RestMethod.GET, "/api/apps/v1/me/account-deletion", expectedGeneration = current.context.generation)
                else restricted
            }
        return decode(response) { element -> readState(element).also { state ->
            if (state.status == "active") require(element.jsonObject["eligibility"] is JsonObject)
        } }.also { result ->
            if (result is CatalogResult.Ready) capability.compareAndSet(current, current.copy(activePreflight = result.value.status == "active"))
        }
    }
    override suspend fun request(key: String): CatalogResult<AccountErasureState> {
        val initial = capability.value
        val context = initial.context ?: return CatalogResult.HttpError(401)
        if (context.purpose != "request" || context.generation != session.identityGeneration || key.length !in 16..512) return CatalogResult.HttpError(401)
        if (initial.requestKey != null && initial.requestKey != key) return CatalogResult.HttpError(409)
        val headers = mapOf("X-Account-Deletion-Context" to context.token, "Idempotency-Key" to key)
        val first = initial.requestKey == null
        if (!capability.compareAndSet(initial, initial.copy(requestKey = key, activePreflight = false))) return CatalogResult.HttpError(409)
        val response = if (first || initial.activePreflight) ownerCapability("/api/apps/v1/me/account-deletion", headers = headers, epoch = context.generation)
            else restricted(RestMethod.POST, "/api/apps/v1/me/account-deletion", key)
        return decode(response, ::readState)
    }
    override suspend fun cancel(key: String): CatalogResult<AccountErasureState> {
        if (capability.value.context?.purpose != "cancel" || key.length !in 16..512) return CatalogResult.HttpError(401)
        return decode(restricted(RestMethod.POST, "/api/apps/v1/me/account-deletion/cancellation", key), ::readState).also {
            if (it is CatalogResult.Ready && it.value.status == "active") session.clearAccountRecovery()
        }
    }
    /** A password/capability 401 is not proof that the native Bearer session expired.
     * Verify that session with one normal GET; never replay the protected POST. */
    private suspend fun ownerCapability(path: String, body: String? = null, headers: Map<String, String> = emptyMap(), epoch: Long): ApiOutcome = try {
        val response = session.withIdentity(readOnly = false, expectedGeneration = epoch) { token ->
            rest.execute(RestMethod.POST, path, body, headers = headers + mapOf("Authorization" to "Bearer $token")).also { result ->
                if (result is ApiOutcome.HttpFailure && result.status == 429) throw ApiFailure(429, result.code, result.retryAfterSeconds)
                if (result is ApiOutcome.Unavailable) throw ApiFailure(503, null, result.retryAfterSeconds)
            }
        }
        if (response is ApiOutcome.HttpFailure && response.status == 401) {
            // The identity owner already invalidates a definitively rejected session.
            // A failed/throttled read is not proof that the Bearer credential is invalid.
            owner.execute(RestMethod.GET, "/api/apps/v1/me", expectedGeneration = epoch)
        }
        response
    } catch (failure: ApiFailure) {
        if (failure.status == 503) ApiOutcome.Unavailable(failure.retryAfterSeconds)
        else ApiOutcome.HttpFailure(failure.status, retryAfterSeconds = failure.retryAfterSeconds, code = failure.code)
    }
    private suspend fun restricted(method: RestMethod, path: String, key: String? = null): ApiOutcome {
        val initial = capability.value
        val context = initial.context ?: return ApiOutcome.HttpFailure(401)
        if (context.generation != session.identityGeneration) {
            capability.compareAndSet(initial, Capability(revision = initial.revision + 1)); return ApiOutcome.HttpFailure(401)
        }
        return rest.execute(method, path, headers = mapOf("X-Account-Deletion-Context" to context.token) + (key?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap()))
    }
    private fun readState(element: JsonElement): AccountErasureState {
        val value = element.jsonObject
        val status = value.text("status").also { require(it in setOf("active", "pending", "erasing")) }
        val eligibility = value["eligibility"] as? JsonObject
        eligibility?.let {
            val canRequest = it.flag("can_request")
            val blockingReason = it["blocking_reason"]?.takeUnless { reason -> reason is JsonNull }?.jsonPrimitive?.content
            require(if (canRequest) blockingReason == null else blockingReason in setOf("ACCOUNT_HAS_ACTIVE_TRIPS", "ACCOUNT_HAS_OTHER_ACTIVE_PROFILES"))
        }
        return AccountErasureState(status, value["grace_expires_at"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.also { kotlin.time.Instant.parse(it) },
            value.flag("can_cancel"), eligibility?.flag("can_request") ?: false,
            eligibility?.get("blocking_reason")?.takeUnless { it is JsonNull }?.jsonPrimitive?.content)
    }
    private fun <T> decode(result: ApiOutcome, project: (JsonElement) -> T): CatalogResult<T> = when (result) {
        is ApiOutcome.Success -> runCatching { val envelope = json.parseToJsonElement(result.responseBody).jsonObject; require(envelope["error"] is JsonNull && envelope["meta"]!!.jsonObject.text("code").isNotBlank()); CatalogResult.Ready(project(envelope.getValue("data"))) }.getOrElse { CatalogResult.InvalidResponse }
        is ApiOutcome.HttpFailure -> CatalogResult.HttpError(result.status, result.retryAfterSeconds, code = result.code)
        is ApiOutcome.Unavailable -> CatalogResult.Maintenance(result.retryAfterSeconds)
        ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
    }
}
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.also { require(it.isString) }.content
private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }.boolean
