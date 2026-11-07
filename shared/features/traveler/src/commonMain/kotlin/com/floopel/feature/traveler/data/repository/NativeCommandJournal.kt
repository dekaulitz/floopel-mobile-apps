package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TripRecoveryStore
import com.floopel.feature.traveler.feature.auth.TravelerSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*
import kotlin.time.Clock

/** Freezes legacy 24h idempotent commands before I/O. No automatic replay or offline queue. */
internal class NativeCommandJournal(private val requests: NativeAuthorizedRequests, private val session: TravelerSession,
    private val store: TripRecoveryStore, private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    private val mutex = Mutex()
    private val json = Json
    suspend fun <T> execute(method: RestMethod, path: String, body: String?, key: String,
        send: suspend (String, Long) -> CatalogResult<T>): CatalogResult<T> {
        if (!mutex.tryLock()) return CatalogResult.HttpError(409, code = "PENDING_OPERATION")
        try {
            val epoch = session.identityGeneration
            val actorReply = requests.execute(RestMethod.GET, "/api/apps/v1/me", expectedGeneration = epoch)
            val actor = when (actorReply) {
                is ApiOutcome.Success -> runCatching {
                    val envelope = json.parseToJsonElement(actorReply.responseBody).jsonObject
                    require(envelope.getValue("error") == JsonNull)
                    envelope.getValue("data").jsonObject.getValue("profile").jsonObject.text("id")
                }.getOrNull() ?: return CatalogResult.InvalidResponse
                is ApiOutcome.HttpFailure -> return CatalogResult.HttpError(actorReply.status, actorReply.retryAfterSeconds, actorReply.code)
                is ApiOutcome.Unavailable -> return CatalogResult.Maintenance(actorReply.retryAfterSeconds)
                ApiOutcome.NetworkFailure -> return CatalogResult.NetworkError
            }
            var newIntent = false
            val frozen = try {
                val saved = store.read()?.let { json.parseToJsonElement(it).jsonObject }
                if (saved != null && saved.text("profile_id") == actor) {
                    val savedBody = saved.getValue("body").jsonPrimitive.also { require(it.isString) }.content
                    if (saved.text("path") != path || saved.text("method") != method.name || savedBody != body.orEmpty())
                        return CatalogResult.HttpError(409, code = "PENDING_OPERATION")
                    if (now() - saved.getValue("created_at").jsonPrimitive.long !in 0 until 86_400_000L)
                        return CatalogResult.HttpError(409, code = "EXPIRED_OPERATION")
                    saved.text("key")
                } else {
                    require(key.isNotBlank())
                    store.write(buildJsonObject {
                        put("profile_id", actor); put("path", path); put("method", method.name)
                        put("body", body.orEmpty()); put("key", key); put("created_at", now())
                    }.toString())
                    newIntent = true
                    key
                }
            } catch (_: Exception) { return CatalogResult.InvalidResponse }
            val result = send(frozen, epoch)
            if (session.identityGeneration != epoch) return CatalogResult.HttpError(401)
            // First-attempt validation rejection is definite. An old uncertain intent stays frozen.
            if (result is CatalogResult.Ready || newIntent && result is CatalogResult.HttpError && result.status in setOf(400, 422)) store.clear()
            return result
        } finally { mutex.unlock() }
    }
}

private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.also { require(it.isString && it.content.isNotBlank()) }.content
