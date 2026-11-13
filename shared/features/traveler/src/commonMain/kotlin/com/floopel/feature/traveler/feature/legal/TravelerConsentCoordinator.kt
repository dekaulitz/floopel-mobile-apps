package com.floopel.feature.traveler.feature.legal

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ConsentPrompt(val documents: List<LegalDocument>, val submitting: Boolean = false, val failure: CatalogResult<Nothing>? = null)

/** One pending consent dialog shared by concurrent gated writes, scoped to the current identity generation. */
internal class TravelerConsentCoordinator(private val repository: TravelerLegalRepository, private val locale: String,
    private val generation: () -> Long, private val newKey: () -> String) : TravelerConsentGate {
    private class Flight(val epoch: Long, val documents: List<LegalDocument>, val keys: List<String>,
        val decision: CompletableDeferred<Boolean> = CompletableDeferred())
    private val lock = Mutex()
    private var flight: Flight? = null
    private val state = MutableStateFlow<ConsentPrompt?>(null)
    val prompt = state.asStateFlow()

    override suspend fun ensure(): CatalogResult<Unit> {
        val epoch = generation()
        val pending = repository.pending(locale)
        if (pending !is CatalogResult.Ready) return pending.failure()
        if (epoch != generation()) return CatalogResult.HttpError(401)
        if (pending.value.isEmpty()) return CatalogResult.Ready(Unit)
        val current = lock.withLock {
            flight?.takeIf { it.epoch == epoch } ?: Flight(epoch, pending.value, pending.value.map { newKey() }).also {
                flight?.decision?.complete(false)
                flight = it; state.value = ConsentPrompt(it.documents)
            }
        }
        val accepted = current.decision.await()
        return if (epoch != generation()) CatalogResult.HttpError(401) else if (accepted) CatalogResult.Ready(Unit) else CatalogResult.HttpError(409)
    }

    suspend fun accept() {
        val current = lock.withLock {
            val active = flight ?: return
            if (active.epoch != generation()) { active.decision.complete(false); flight = null; state.value = null; return }
            if (state.value?.submitting == true) return
            state.value = ConsentPrompt(active.documents, submitting = true)
            active
        }
        try {
            for ((index, document) in current.documents.withIndex()) {
                if (retireIfStale(current)) return
                val result = repository.accept(document, current.keys[index], current.epoch)
                if (retireIfStale(current)) return
                if (result !is CatalogResult.Ready) {
                    lock.withLock { if (flight === current) state.value = ConsentPrompt(current.documents, failure = result.failure()) }
                    return
                }
            }
            val remaining = repository.pending(locale)
            if (retireIfStale(current)) return
            when (remaining) {
                is CatalogResult.Ready -> lock.withLock {
                    if (flight !== current || current.epoch != generation()) return@withLock
                    if (remaining.value.isEmpty()) {
                        flight = null; state.value = null; current.decision.complete(true)
                    } else {
                        // A new version arrived during review: require an explicit new acceptance.
                        val next = Flight(current.epoch, remaining.value, remaining.value.map { newKey() }, current.decision)
                        flight = next; state.value = ConsentPrompt(next.documents)
                    }
                }
                else -> lock.withLock { if (flight === current) state.value = ConsentPrompt(current.documents, failure = remaining.failure()) }
            }
        } catch (cancelled: CancellationException) {
            // No request is replayed; the caller must explicitly accept again if the identity still exists.
            withContext(NonCancellable) { lock.withLock { if (flight === current) state.value = state.value?.copy(submitting = false) } }
            throw cancelled
        }
    }

    private suspend fun retireIfStale(current: Flight): Boolean {
        if (current.epoch == generation()) return false
        lock.withLock { if (flight === current) { current.decision.complete(false); flight = null; state.value = null } }
        return true
    }

    suspend fun dismiss() = lock.withLock {
        if (state.value?.submitting == true) return@withLock
        flight?.decision?.complete(false); flight = null; state.value = null
    }
    suspend fun clear() = lock.withLock {
        flight?.decision?.complete(false); flight = null; state.value = null
    }
}
private fun CatalogResult<*>.failure(): CatalogResult<Nothing> = when (this) {
    is CatalogResult.HttpError -> CatalogResult.HttpError(status, retryAfterSeconds)
    is CatalogResult.Maintenance -> this
    CatalogResult.NetworkError -> CatalogResult.NetworkError
    else -> CatalogResult.InvalidResponse
}
