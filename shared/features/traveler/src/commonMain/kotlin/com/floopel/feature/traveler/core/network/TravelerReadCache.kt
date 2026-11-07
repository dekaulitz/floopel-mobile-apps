package com.floopel.feature.traveler.core.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource
import kotlinx.serialization.json.*

/** Session-memory page reads only; bounded, short lived and never persisted to disk. */
internal class TravelerReadCache(
    private val ttlMillis: Long = 60_000,
    private val maximumEntries: Int = 96,
    private val nowMillis: () -> Long = monotonicClock(),
) {
    init { require(ttlMillis > 0 && maximumEntries > 0) }
    internal data class Key(val path: String, val authorization: String?)
    private data class Entry(val value: ApiOutcome.Success, val at: Long)
    private val lock = Mutex()
    private val entries = linkedMapOf<Key, Entry>()
    private var generation = 0L
    private val revisions = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val revision: Long get() = revisions.value
    private var freshReaders = 0
    internal suspend fun generation() = lock.withLock { generation }
    internal suspend fun get(key: Key): ApiOutcome.Success? = lock.withLock {
        if (freshReaders > 0) return@withLock null
        val entry = entries.remove(key) ?: return@withLock null
        if (nowMillis() - entry.at !in 0 until ttlMillis) return@withLock null
        entries[key] = entry
        entry.value
    }
    internal suspend fun put(key: Key, value: ApiOutcome.Success, epoch: Long) = lock.withLock {
        if (epoch != generation || value.responseBody.length > 262_144) return@withLock
        val envelope = runCatching { Json.parseToJsonElement(value.responseBody) as? JsonObject }.getOrNull()
        if (envelope == null || envelope["error"] != JsonNull || "data" !in envelope) return@withLock
        entries.remove(key)
        entries[key] = Entry(value, nowMillis())
        while (entries.size > maximumEntries || entries.values.sumOf { it.value.responseBody.length } > 2_097_152)
            entries.remove(entries.keys.first())
    }
    suspend fun clear() = lock.withLock { entries.clear(); generation++; revisions.value = generation }
    /** A fresh rejection/no-store response also fences older in-flight reads from restoring the entry. */
    internal suspend fun remove(key: Key) { lock.withLock { entries.remove(key); generation++ } }
    suspend fun withFreshReads(block: suspend () -> Unit) {
        lock.withLock { freshReaders++ }
        try { block() } finally { withContext(NonCancellable) { lock.withLock { freshReaders-- } } }
    }
    internal fun eligible(path: String): Boolean {
        val route = path.substringBefore('?')
        if (route == "/api/apps/v1/locations/nearest") return false
        // Explicit GPS refresh and radius changes must read the current nearby catalog.
        if (route == "/api/apps/v1/spots" && path.substringAfter('?', "").split('&').any { it.substringBefore('=') == "radius_m" }) return false
        // Membership and organizer permissions can change in another session.
        if (route == "/api/apps/v1/trips" || route.startsWith("/api/apps/v1/trips/") ||
            route == "/api/apps/v1/me/trips" || route == "/api/apps/v1/me/trip-invitations") return false
        val profilePath = "/api/apps/v1/profiles/"
        if (route.startsWith(profilePath) && '/' !in route.removePrefix(profilePath)) return false
        // Consent, legal, credentials, security, deletion and gameplay are always authoritative reads.
        val public = listOf("locations", "spots", "collections", "content", "search", "public-itineraries", "profiles", "comment-totals", "itineraries", "trips", "comments")
        if (public.any { route == "/api/apps/v1/$it" || route.startsWith("/api/apps/v1/$it/") }) return true
        val owner = listOf("profile", "interests", "settings", "saved-spots", "itineraries", "trips", "trip-invitations")
        return owner.any { route == "/api/apps/v1/me/$it" || route.startsWith("/api/apps/v1/me/$it/") }
    }
}

private fun monotonicClock(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
