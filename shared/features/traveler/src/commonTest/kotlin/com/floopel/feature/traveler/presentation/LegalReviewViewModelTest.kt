package com.floopel.feature.traveler.presentation

import androidx.lifecycle.viewModelScope
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.presentation.account.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LegalReviewViewModelTest {
    private class Identity : TravelerIdentity {
        override val view = MutableStateFlow(TravelerIdentityView(signedIn = true))
        override val generation get() = view.value.generation
        override suspend fun logout() { view.value = TravelerIdentityView(generation = generation + 1) }
        override suspend fun restore() = Unit
        override suspend fun login(email: String, password: String) = Unit
        override suspend fun loginGoogle(idToken: String) = Unit
        override suspend fun completeGoogle(draft: RegistrationDraft) = Unit
        override suspend fun verifyEmail(email: String, code: String) = Unit
        override suspend fun passport() = Unit
        override suspend fun quest(slug: String): CatalogResult<QuestReader> = CatalogResult.InvalidResponse
        override suspend fun takeQuest() = Unit
        override suspend fun continueQuest() = Unit
        override suspend fun continueQuest(id: String) = Unit
        override suspend fun activity(id: String) = Unit
        override suspend fun answer(value: String) = Unit
        override suspend fun collectGps() = Unit
        override suspend fun submitGps() = Unit
        override suspend fun background() = Unit
    }
    private val terms = LegalDocument("terms", "2.0", "id", "<h1>Dokumen simulasi</h1><p>Isi dari server.</p>", "exact-served-hash")
    private class Repository(var pending: List<LegalDocument>) : TravelerLegalRepository, TravelerLegalDocumentRepository {
        var failure: CatalogResult<List<LegalDocument>>? = null
        var outcome: CatalogResult<Unit> = CatalogResult.Ready(Unit)
        var beforeRead: suspend () -> Unit = {}
        var beforeWrite: suspend () -> Unit = {}
        val writes = mutableListOf<Triple<LegalDocument, String, Long>>()
        val currentReads = mutableListOf<String>()
        override suspend fun pending(locale: String): CatalogResult<List<LegalDocument>> { beforeRead(); return failure ?: CatalogResult.Ready(pending) }
        override suspend fun current(type: String, locale: String): CatalogResult<LegalDocument> {
            currentReads += "$type:$locale"
            return CatalogResult.Ready(LegalDocument(type, "current-3.0", "id", "<p>Current $type</p>", "$type-current-hash"))
        }
        override suspend fun accept(document: LegalDocument, key: String, identityGeneration: Long): CatalogResult<Unit> {
            writes += Triple(document, key, identityGeneration); beforeWrite()
            if (outcome is CatalogResult.Ready) pending = pending - document
            return outcome
        }
    }
    private fun runCase(pending: List<LegalDocument> = listOf(terms), manual: Boolean = true,
        block: suspend TestScope.(Identity, Repository, LegalReviewViewModel) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler); Dispatchers.setMain(dispatcher)
        val identity = Identity(); val repository = Repository(pending)
        var keys = 0
        val model = LegalReviewViewModel(repository, repository, identity, { "synthetic-key-${++keys}" }, dispatcher)
        try { model.open("en", manual); advanceUntilIdle(); block(identity, repository, model) }
        finally { model.close(); model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun acceptedAccountLoadsCurrentReadableDocumentsWithoutAnotherConsent() = runCase(emptyList()) { _, repository, model ->
        assertTrue(model.state.value.readOnly); assertEquals(2, model.state.value.documents.size)
        assertEquals(listOf("terms:en", "privacy:en"), repository.currentReads)
        val served = model.state.value.documents.first(); model.select(served)
        assertEquals(served, model.state.value.selected); assertEquals("id", served.locale)
        model.agree(true); model.accept(); advanceUntilIdle()
        assertFalse(model.state.value.agreed); assertTrue(repository.writes.isEmpty())
        model.backToList(); assertNull(model.state.value.selected)
    }
    @Test fun changedDocumentRequiresFreshAcknowledgementAndOnlyAcceptsItsActualHash() = runCase { _, repository, model ->
        model.agree(true)
        val replacement = terms.copy(version = "3.0", hash = "new-served-hash", contentHtml = "<p>New body</p>")
        repository.pending = listOf(replacement)
        model.accept(); advanceUntilIdle()
        assertTrue(model.state.value.changed); assertFalse(model.state.value.agreed); assertTrue(repository.writes.isEmpty())
        model.accept(); advanceUntilIdle(); assertTrue(repository.writes.isEmpty())
        model.agree(true); model.accept(); advanceUntilIdle()
        assertEquals(replacement, repository.writes.single().first); assertTrue(model.state.value.completed)
    }
    @Test fun doubleTapAndUnknownOutcomeNeverReplayAndExplicitRetryKeepsTheSameKey() = runCase { _, repository, model ->
        val gate = CompletableDeferred<Unit>(); repository.beforeWrite = { gate.await() }
        repository.outcome = CatalogResult.NetworkError; model.agree(true)
        model.accept(); model.accept(); runCurrent(); assertEquals(1, repository.writes.size)
        gate.complete(Unit); advanceUntilIdle(); assertTrue(model.state.value.uncertain)
        model.accept(); advanceUntilIdle(); assertEquals(1, repository.writes.size)
        model.refresh(); advanceUntilIdle(); assertFalse(model.state.value.uncertain)
        repository.outcome = CatalogResult.Ready(Unit); model.accept(); advanceUntilIdle()
        assertEquals(2, repository.writes.size); assertEquals(repository.writes[0].second, repository.writes[1].second)
        assertTrue(model.state.value.completed)
    }
    @Test fun lostSuccessfulResponseIsResolvedByAReadWithoutRecordingConsentAgain() = runCase { _, repository, model ->
        repository.outcome = CatalogResult.NetworkError; model.agree(true); model.accept(); advanceUntilIdle()
        repository.pending = emptyList(); model.refresh(); advanceUntilIdle()
        assertTrue(model.state.value.readOnly); assertEquals(1, repository.writes.size)
        model.accept(); advanceUntilIdle(); assertEquals(1, repository.writes.size)
    }
    @Test fun loadFailureCanRetryAndClosingOrChangingActorDiscardsLateReads() = runCase { identity, repository, model ->
        repository.failure = CatalogResult.NetworkError; model.refresh(); advanceUntilIdle()
        assertNotNull(model.state.value.failure)
        repository.failure = null; model.refresh(); advanceUntilIdle(); assertNull(model.state.value.failure)
        val gate = CompletableDeferred<Unit>(); repository.beforeRead = { withContext(NonCancellable) { gate.await() } }
        model.refresh(); runCurrent(); model.close(); gate.complete(Unit); advanceUntilIdle()
        assertEquals(LegalReviewState(), model.state.value)
        repository.beforeRead = {}; model.open("id", true); advanceUntilIdle()
        identity.logout(); advanceUntilIdle(); assertEquals(LegalReviewState(), model.state.value)
        model.agree(true); model.accept(); advanceUntilIdle(); assertTrue(repository.writes.isEmpty())
    }
}
