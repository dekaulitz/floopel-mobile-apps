package com.floopel.feature.traveler.presentation.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.presentation.ReadFailure
import com.floopel.feature.traveler.presentation.ReadState
import com.floopel.feature.traveler.presentation.result
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock

internal enum class ErasurePhase { VERIFY, REVIEW, BLOCKED, PENDING, ERASING, CANCELLED }
internal enum class ErasureOperation { VERIFY, READ, REQUEST, CANCEL }
internal data class ErasureUiState(val phase: ErasurePhase = ErasurePhase.VERIFY,
    val status: AccountErasureState? = null, val busy: Boolean = false, val acknowledged: Boolean = false,
    val failure: ReadFailure? = null, val unknownOutcome: Boolean = false, val cancellationRetry: Boolean = false,
    val cancelVerification: Boolean = false, val operation: ErasureOperation? = null)

/** Restricted capability and mutations stay outside the normal Traveler navigation/content tree. */
internal class ErasureViewModel(private val repository: TravelerErasureRepository, private val identity: TravelerIdentity,
    private val newKey: () -> String, private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) : ViewModel() {
    private val mutable = MutableStateFlow(ErasureUiState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var actor = identity.generation
    private var authenticated = false
    private var verifiedStatus: String? = null
    private var revision = 0L
    private var ownLogout = false
    private var requestKey: String? = null
    private var cancelKey: String? = null
    private var opened = false
    init { viewModelScope.launch { identity.view.collect { current ->
        if (current.generation != actor && !ownLogout) {
            revision++; job?.cancel(); repository.clear(); authenticated = false; verifiedStatus = null; actor = current.generation
            requestKey = null; cancelKey = null; mutable.value = ErasureUiState()
        }
    } } }
    fun open(verifiedRecovery: Boolean) {
        if (opened) return
        opened = true; revision++; actor = identity.generation
        repository.clear()
        if (verifiedRecovery) loadStatus(recovery = true)
    }
    fun acknowledge(value: Boolean) { if (!state.value.busy) mutable.update { it.copy(acknowledged = value) } }
    fun verifyAgain(cancel: Boolean = state.value.cancelVerification || state.value.status?.status == "pending") {
        if (state.value.busy) return
        revision++; repository.clear(); authenticated = false; verifiedStatus = null
        mutable.value = ErasureUiState(cancelVerification = cancel)
    }
    fun authenticate(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) return
        operation(kind = ErasureOperation.VERIFY, call = {
            when (val result = repository.authenticate(email, password)) {
                is CatalogResult.Ready -> repository.state()
                else -> result.failureOnly()
            }
        }) { authenticated = true; verifiedStatus = it.status; acceptRead(it) }
    }
    fun loadStatus(recovery: Boolean = false) = operation(call = repository::state) { value ->
        if (recovery) authenticated = value.status in setOf("pending", "erasing")
        acceptRead(value)
    }
    fun checkStatus() { if (!authenticated) verifyAgain() else loadStatus() }
    private fun accept(value: AccountErasureState) {
        val phase = when (value.status) {
            "pending" -> ErasurePhase.PENDING
            "erasing" -> ErasurePhase.ERASING
            "active" -> if (authenticated && identitySignedIn) {
                if (value.canRequest) ErasurePhase.REVIEW else ErasurePhase.BLOCKED
            } else ErasurePhase.CANCELLED
            else -> { failure(CatalogResult.InvalidResponse); return }
        }
        mutable.update { it.copy(phase = phase, status = value, acknowledged = false, failure = null, unknownOutcome = false,
            cancellationRetry = false, operation = null) }
    }
    private var identitySignedIn = false
    fun signedIn(value: Boolean) { identitySignedIn = value }
    fun request() {
        val current = state.value
        if (!authenticated || !identitySignedIn || current.phase != ErasurePhase.REVIEW || !current.acknowledged ||
            current.status?.canRequest != true || current.unknownOutcome) return
        val key = requestKey ?: newKey().also { requestKey = it }
        mutate(ErasureOperation.REQUEST) { repository.request(key) }
    }
    fun cancel() {
        val current = state.value
        if (current.phase != ErasurePhase.PENDING || current.status?.canCancel != true) return
        if (current.status.deadline != null && !current.acknowledged && !current.cancellationRetry) return
        if (current.unknownOutcome && (!current.cancellationRetry || cancelKey == null)) return
        if (!authenticated || verifiedStatus != "pending") { verifyAgain(cancel = true); return }
        val key = cancelKey ?: newKey().also { cancelKey = it }
        mutable.update { it.copy(cancellationRetry = true) }
        mutate(ErasureOperation.CANCEL) { repository.cancel(key) }
    }
    private suspend fun acceptRead(value: AccountErasureState) {
        // A GET can confirm an accepted request whose POST response was lost.
        if (identitySignedIn && value.status in setOf("pending", "erasing")) disposeIdentity()
        accept(value)
    }
    private fun mutate(kind: ErasureOperation, call: suspend () -> CatalogResult<AccountErasureState>) = operation(kind = kind, call = call, mutation = true) { value ->
        disposeIdentity()
        accept(value)
    }
    private suspend fun disposeIdentity() {
        // Accepted erasure/cancellation never restores a normal identity. Clear its credentials.
        ownLogout = true
        try { withContext(worker) { identity.logout() } }
        finally { actor = identity.generation; ownLogout = false; repository.clear(); authenticated = false; verifiedStatus = null; identitySignedIn = false }
    }
    private fun <T> operation(call: suspend () -> CatalogResult<T>, mutation: Boolean = false,
        kind: ErasureOperation = ErasureOperation.READ, success: suspend (T) -> Unit) {
        val initial = state.value
        if (!opened || initial.busy || (initial.failure?.retryAtMillis ?: 0) > now()) return
        val epoch = identity.generation
        val ticket = ++revision
        mutable.update { it.copy(busy = true, failure = null, operation = kind) }
        job = viewModelScope.launch {
            try {
                val result = try { withContext(worker) { call() } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { CatalogResult.NetworkError }
                ensureActive()
                if (identity.generation != epoch || !opened || ticket != revision) return@launch
                if (result is CatalogResult.Ready) success(result.value)
                else {
                    failure(result)
                    if (mutation) mutable.update { it.copy(unknownOutcome = result.isUncertain()) }
                    if (result is CatalogResult.HttpError && result.status == 401) {
                        repository.clear(); authenticated = false; verifiedStatus = null
                        mutable.update { it.copy(phase = ErasurePhase.VERIFY, acknowledged = false) }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (identity.generation == epoch && ticket == revision) failure(CatalogResult.NetworkError) }
            finally { if (identity.generation == actor && ticket == revision) mutable.update { it.copy(busy = false) } }
        }
    }
    private fun failure(result: CatalogResult<*>) {
        mutable.update { it.copy(failure = ReadState<Any>().result(result.failureOnly(), now()).failure) }
    }
    fun close() {
        revision++; job?.cancel(); repository.clear(); authenticated = false; verifiedStatus = null; opened = false
        requestKey = null; cancelKey = null; mutable.value = ErasureUiState()
    }
    override fun onCleared() { repository.clear() }
}

private fun CatalogResult<*>.isUncertain() = this is CatalogResult.NetworkError || this is CatalogResult.InvalidResponse ||
    this is CatalogResult.Maintenance || this is CatalogResult.HttpError && status >= 500
private fun CatalogResult<*>.failureOnly(): CatalogResult<Nothing> = when (this) {
    is CatalogResult.HttpError -> this; is CatalogResult.Maintenance -> this
    CatalogResult.NetworkError -> CatalogResult.NetworkError; CatalogResult.InvalidResponse -> CatalogResult.InvalidResponse
    is CatalogResult.Ready -> CatalogResult.InvalidResponse
}
