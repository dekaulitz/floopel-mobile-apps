package com.floopel.feature.traveler.feature.legal

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TravelerConsentCoordinatorTest {
    @Test fun concurrentGatedActionsShareReviewAndStableKeysOnExplicitRetry() = runTest {
        val repository = ConsentFixture()
        var key = 0
        val coordinator = TravelerConsentCoordinator(repository, "en", { 1L }, { "key-${++key}" })
        val first = async { coordinator.ensure() }
        val second = async { coordinator.ensure() }
        runCurrent()
        assertEquals(2, coordinator.prompt.value!!.documents.size)
        assertFalse(first.isCompleted)
        repository.failPrivacy = true
        coordinator.accept()
        assertEquals(CatalogResult.Maintenance(), coordinator.prompt.value!!.failure)
        assertFalse(first.isCompleted)
        repository.failPrivacy = false
        coordinator.accept()
        assertEquals(CatalogResult.Ready(Unit), first.await())
        assertEquals(CatalogResult.Ready(Unit), second.await())
        assertNull(coordinator.prompt.value)
        assertEquals(listOf("key-1", "key-2", "key-1", "key-2"), repository.keys)
        assertEquals(CatalogResult.Ready(Unit), coordinator.ensure())
    }

    @Test fun dismissDoesNotAcceptAndNewLegalVersionRequiresAnotherExplicitReview() = runTest {
        val repository = ConsentFixture()
        val coordinator = TravelerConsentCoordinator(repository, "id", { 1L }, { "key" })
        val cancelled = async { coordinator.ensure() }; runCurrent()
        coordinator.dismiss()
        assertEquals(CatalogResult.HttpError(409), cancelled.await())
        assertTrue(repository.keys.isEmpty())
        repository.pending = listOf(repository.terms)
        val waiting = async { coordinator.ensure() }; runCurrent()
        repository.nextVersion = repository.terms.copy(version = "3.0", hash = "new-hash")
        coordinator.accept()
        assertEquals("3.0", coordinator.prompt.value!!.documents.single().version)
        assertFalse(waiting.isCompleted)
        coordinator.accept()
        assertEquals(CatalogResult.Ready(Unit), waiting.await())
    }

    @Test fun changedIdentityCannotAcceptTheRemainingDocumentsOrOverwriteASuccessorReview() = runTest {
        val repository = ConsentFixture()
        var epoch = 1L
        val coordinator = TravelerConsentCoordinator(repository, "en", { epoch }, { "key" })
        val old = async { coordinator.ensure() }; runCurrent()
        val release = CompletableDeferred<Unit>()
        repository.release = release
        val accepting = async { coordinator.accept() }; runCurrent()
        assertEquals(listOf(1L), repository.epochs)
        epoch = 2
        coordinator.clear()
        val successor = async { coordinator.ensure() }; runCurrent()
        release.complete(Unit); accepting.await()
        assertEquals(CatalogResult.HttpError(401), old.await())
        assertFalse(coordinator.prompt.value!!.submitting)
        assertEquals(listOf(1L), repository.epochs)
        coordinator.dismiss()
        assertEquals(CatalogResult.HttpError(409), successor.await())
    }

    @Test fun failedPendingReadDoesNotOpenOrApproveAReview() = runTest {
        val coordinator = TravelerConsentCoordinator(object : TravelerLegalRepository {
            override suspend fun pending(locale: String): CatalogResult<List<LegalDocument>> = CatalogResult.NetworkError
            override suspend fun accept(document: LegalDocument, key: String, identityGeneration: Long): CatalogResult<Unit> = error("No write expected")
        }, "en", { 1 }, { "key" })
        assertEquals(CatalogResult.NetworkError, coordinator.ensure())
        assertNull(coordinator.prompt.value)
    }
}

private class ConsentFixture : TravelerLegalRepository {
    val terms = LegalDocument("terms", "2.0", "en", "<p>Terms</p>", "terms-hash")
    private val privacy = LegalDocument("privacy", "2.0", "en", "<p>Privacy</p>", "privacy-hash")
    var pending = listOf(terms, privacy)
    var failPrivacy = false
    var nextVersion: LegalDocument? = null
    var release: CompletableDeferred<Unit>? = null
    val keys = mutableListOf<String>()
    val epochs = mutableListOf<Long>()
    override suspend fun pending(locale: String) = CatalogResult.Ready(pending)
    override suspend fun accept(document: LegalDocument, key: String, identityGeneration: Long): CatalogResult<Unit> {
        keys.add(key); epochs.add(identityGeneration)
        release?.await()
        if (failPrivacy && document.type == "privacy") return CatalogResult.Maintenance()
        pending = pending.filterNot { it.type == document.type && it.version == document.version }
        nextVersion?.let { pending = listOf(it); nextVersion = null }
        return CatalogResult.Ready(Unit)
    }
}
