package com.floopel.feature.traveler.presentation.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.presentation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.Clock

internal data class LegalReviewState(
    val documents: List<LegalDocument> = emptyList(), val selected: LegalDocument? = null,
    val loading: Boolean = false, val submitting: Boolean = false, val agreed: Boolean = false,
    val readOnly: Boolean = false, val changed: Boolean = false, val completed: Boolean = false,
    val failure: ReadFailure? = null, val uncertain: Boolean = false,
)

/** The displayed body and consent payload share the same server version/locale/hash. */
internal class LegalReviewViewModel(
    private val repository: TravelerLegalRepository, private val documents: TravelerLegalDocumentRepository?,
    private val identity: TravelerIdentity, private val newKey: () -> String,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : ViewModel() {
    private val mutable = MutableStateFlow(LegalReviewState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var revision = 0L
    private var opened = false
    private var locale = "id"
    private var manual = false
    private var actor = identity.generation
    private val keys = mutableMapOf<LegalDocument, String>()

    init { viewModelScope.launch { identity.view.collect {
        if (actor != it.generation || !it.signedIn) { close(); actor = it.generation }
    } } }

    fun open(language: String, fromSettings: Boolean) {
        require(language in setOf("id", "en"))
        if (opened && locale == language && manual == fromSettings) return
        close(); opened = true; actor = identity.generation; locale = language; manual = fromSettings
        refresh()
    }
    fun close() {
        revision++; job?.cancel(); job = null; opened = false; keys.clear()
        mutable.value = LegalReviewState()
    }
    fun select(document: LegalDocument) {
        if (!state.value.loading && !state.value.submitting && document in state.value.documents)
            mutable.update { it.copy(selected = document) }
    }
    fun backToList() { if (!state.value.submitting) mutable.update { it.copy(selected = null) } }
    fun agree(value: Boolean) {
        if (!state.value.loading && !state.value.submitting && !state.value.readOnly && !state.value.uncertain)
            mutable.update { it.copy(agreed = value) }
    }
    fun refresh() = launch(submitting = false) {
        when (val result = remote { repository.pending(locale) }) {
            is CatalogResult.Ready -> showPending(result.value)
            else -> fail(result)
        }
    }
    private suspend fun showPending(pending: List<LegalDocument>) {
        if (pending.isNotEmpty()) {
            show(pending, readOnly = false)
        } else if (manual && !state.value.submitting) {
            val current = mutableListOf<LegalDocument>()
            for (type in listOf("terms", "privacy")) {
                when (val result = remote { documents?.current(type, locale) ?: CatalogResult.InvalidResponse }) {
                    is CatalogResult.Ready -> current += result.value
                    else -> { fail(result); return }
                }
            }
            show(current, readOnly = true)
        } else mutable.update { it.copy(completed = true, documents = emptyList(), selected = null, agreed = false) }
    }
    private fun show(documents: List<LegalDocument>, readOnly: Boolean) {
        val previous = state.value
        val changed = previous.documents.isNotEmpty() && previous.documents != documents
        mutable.update { it.copy(documents = documents, selected = null, readOnly = readOnly,
            agreed = if (changed || readOnly) false else it.agreed,
            changed = changed && !readOnly || it.changed && !readOnly, failure = null, uncertain = false) }
        keys.keys.retainAll(documents.toSet())
    }
    fun accept() {
        val initial = state.value
        if (!initial.agreed || initial.readOnly || initial.uncertain || initial.documents.isEmpty()) return
        launch(submitting = true) {
            // A newly published version must be reviewed and explicitly acknowledged again.
            val fresh = remote { repository.pending(locale) }
            if (fresh !is CatalogResult.Ready) { fail(fresh); return@launch }
            if (fresh.value != initial.documents) { showPending(fresh.value); return@launch }
            for (document in initial.documents) {
                if (!currentCoroutineContext().isActive || actor != identity.generation) return@launch
                val key = keys.getOrPut(document, newKey)
                val result = remote { repository.accept(document, key, actor) }
                if (result !is CatalogResult.Ready) {
                    val uncertain = result is CatalogResult.NetworkError || result is CatalogResult.InvalidResponse ||
                        result is CatalogResult.Maintenance || result is CatalogResult.HttpError && result.status >= 500
                    fail(result, uncertain)
                    return@launch // Reconcile through an explicit GET before any retry; never replay here.
                }
            }
            when (val pending = remote { repository.pending(locale) }) {
                is CatalogResult.Ready -> showPending(pending.value)
                else -> fail(pending, uncertain = true)
            }
        }
    }
    private fun fail(result: CatalogResult<*>, uncertain: Boolean = false) {
        val failure = ReadState<Unit>().result(result.failureOnly(), now()).failure ?: ReadFailure()
        mutable.update { it.copy(failure = failure, uncertain = uncertain) }
    }
    private fun launch(submitting: Boolean, block: suspend () -> Unit) {
        if (!opened || actor != identity.generation || job?.isActive == true || (state.value.failure?.retryAtMillis ?: 0) > now()) return
        val stamp = revision; val epoch = actor
        mutable.update { it.copy(loading = !submitting, submitting = submitting, failure = null) }
        job = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (stamp == revision && epoch == identity.generation) fail(CatalogResult.NetworkError, submitting) }
            finally { if (stamp == revision && epoch == identity.generation) mutable.update { it.copy(loading = false, submitting = false) } }
        }
    }
    private suspend fun <T> remote(call: suspend () -> T): T = withContext(worker) { call() }.also {
        currentCoroutineContext().ensureActive()
    }
}

private fun CatalogResult<*>.failureOnly(): CatalogResult<Unit> = when (this) {
    is CatalogResult.HttpError -> this
    is CatalogResult.Maintenance -> this
    CatalogResult.NetworkError -> CatalogResult.NetworkError
    else -> CatalogResult.InvalidResponse
}
