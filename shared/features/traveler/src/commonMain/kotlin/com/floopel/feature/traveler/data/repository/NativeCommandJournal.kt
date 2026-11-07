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
    private var reviewed: Pair<Long, String?>? = null

    /** Read only; the ticket is bound to an authorized actor, generation and exact stored record. */
    suspend fun <T> review(read: suspend (String) -> CatalogResult<T>): CatalogResult<T> {
        if (!mutex.tryLock()) return CatalogResult.HttpError(409)
        try {
            reviewed = null
            val epoch = session.identityGeneration
            val actor = actor(epoch)
            if (actor !is CatalogResult.Ready) return actor.failure()
            val record = try {
                store.read().also { raw ->
                    if (raw != null && json.parseToJsonElement(raw).jsonObject.text("profile_id") != actor.value)
                        return CatalogResult.HttpError(401)
                }
            } catch (_: Exception) { return CatalogResult.InvalidResponse }
            val result = read(actor.value)
            if (session.identityGeneration != epoch) return CatalogResult.HttpError(401)
            if (result is CatalogResult.Ready) {
                try { if (store.read() != record) return CatalogResult.HttpError(409) }
                catch (_: Exception) { return CatalogResult.InvalidResponse }
                reviewed = epoch to record
            }
            return result
        } finally { mutex.unlock() }
    }

    /** Explicit local release only. Never sends or rotates a command. */
    suspend fun startNewAction(): CatalogResult<Unit> {
        if (!mutex.tryLock()) return CatalogResult.HttpError(409)
        return try {
            val ticket = reviewed ?: return CatalogResult.HttpError(409)
            if (ticket.first != session.identityGeneration || store.read() != ticket.second) return CatalogResult.HttpError(409)
            store.clear(); reviewed = null
            CatalogResult.Ready(Unit)
        } catch (_: Exception) { CatalogResult.InvalidResponse }
        finally { mutex.unlock() }
    }

    suspend fun <T> execute(method: RestMethod, path: String, body: String?, key: String,
        send: suspend (String, Long) -> CatalogResult<T>): CatalogResult<T> {
        if (!mutex.tryLock()) return CatalogResult.HttpError(409, code = "PENDING_OPERATION")
        try {
            reviewed = null
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

    private suspend fun actor(epoch: Long): CatalogResult<String> = when (val reply = requests.execute(RestMethod.GET, "/api/apps/v1/me", expectedGeneration = epoch)) {
        is ApiOutcome.Success -> try {
            val envelope = json.parseToJsonElement(reply.responseBody).jsonObject
            require(envelope.getValue("error") == JsonNull)
            CatalogResult.Ready(envelope.getValue("data").jsonObject.getValue("profile").jsonObject.text("id"))
        } catch (_: Exception) { CatalogResult.InvalidResponse }
        is ApiOutcome.HttpFailure -> CatalogResult.HttpError(reply.status, reply.retryAfterSeconds, reply.code)
        is ApiOutcome.Unavailable -> CatalogResult.Maintenance(reply.retryAfterSeconds)
        ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
    }
}

private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.also { require(it.isString && it.content.isNotBlank()) }.content
private fun CatalogResult<*>.failure(): CatalogResult<Nothing> = when (this) {
    is CatalogResult.HttpError -> this
    is CatalogResult.Maintenance -> this
    CatalogResult.NetworkError -> CatalogResult.NetworkError
    else -> CatalogResult.InvalidResponse
}
