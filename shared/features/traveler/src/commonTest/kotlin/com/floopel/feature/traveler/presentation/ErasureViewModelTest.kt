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
class ErasureViewModelTest {
    private class Identity : TravelerIdentity {
        override val view = MutableStateFlow(TravelerIdentityView(signedIn = true))
        override val generation get() = view.value.generation
        var logouts = 0
        override suspend fun logout() { logouts++; view.value = TravelerIdentityView(generation = generation + 1) }
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
    private class Repository : TravelerErasureRepository {
        var status = AccountErasureState("active", null, false, true, null)
        var write: CatalogResult<AccountErasureState> = CatalogResult.Ready(AccountErasureState("pending", null, true, false, null))
        var reads = 0; var requests = 0; var cancellations = 0; var clears = 0
        var beforeWrite: suspend () -> Unit = {}
        var beforeAuth: suspend () -> Unit = {}
        val keys = mutableListOf<String>()
        override suspend fun authenticate(email: String, password: String): CatalogResult<Unit> { beforeAuth(); return CatalogResult.Ready(Unit) }
        override suspend fun state(): CatalogResult<AccountErasureState> { reads++; return CatalogResult.Ready(status) }
        override suspend fun request(key: String): CatalogResult<AccountErasureState> { requests++; keys += key; beforeWrite(); return write }
        override suspend fun cancel(key: String): CatalogResult<AccountErasureState> { cancellations++; keys += key; beforeWrite(); return write }
        override fun clear() { clears++ }
    }
    private fun runCase(block: suspend TestScope.(Identity, Repository, ErasureViewModel) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val identity = Identity(); val repository = Repository()
        val model = ErasureViewModel(repository, identity, { "synthetic-stable-operation-key" }, dispatcher)
        try { model.signedIn(true); model.open(false); runCurrent(); block(identity, repository, model) }
        finally { model.close(); model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun openingAndReadRetryNeverSubmitAndConsentIsRequired() = runCase { _, repository, model ->
        model.request(); model.cancel(); advanceUntilIdle()
        assertEquals(0, repository.requests); assertEquals(0, repository.cancellations)
        model.authenticate("fixture@example.invalid", "fixture-password"); advanceUntilIdle()
        assertEquals(ErasurePhase.REVIEW, model.state.value.phase)
        model.request(); advanceUntilIdle(); assertEquals(0, repository.requests)
        model.checkStatus(); advanceUntilIdle(); assertEquals(2, repository.reads)
        assertEquals(0, repository.requests)
    }
    @Test fun doubleTapSubmitsOnceThenClearsNormalIdentityAndRequiresFreshProof() = runCase { identity, repository, model ->
        model.authenticate("fixture@example.invalid", "fixture-password"); advanceUntilIdle(); model.acknowledge(true)
        val gate = CompletableDeferred<Unit>(); repository.beforeWrite = { gate.await() }
        model.request(); model.request(); runCurrent(); assertEquals(1, repository.requests)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, identity.logouts); assertFalse(identity.view.value.signedIn)
        assertEquals(ErasurePhase.PENDING, model.state.value.phase)
        model.cancel(); advanceUntilIdle()
        assertEquals(ErasurePhase.VERIFY, model.state.value.phase); assertEquals(0, repository.cancellations)
        assertTrue(model.state.value.cancelVerification)
    }
    @Test fun uncertainWriteRequiresReadAndRetainsOriginalKeyForAnExplicitRetry() = runCase { _, repository, model ->
        model.authenticate("fixture@example.invalid", "fixture-password"); advanceUntilIdle(); model.acknowledge(true)
        repository.write = CatalogResult.NetworkError
        model.request(); advanceUntilIdle(); assertTrue(model.state.value.unknownOutcome)
        model.request(); advanceUntilIdle(); assertEquals(1, repository.requests)
        model.checkStatus(); advanceUntilIdle(); assertFalse(model.state.value.unknownOutcome)
        assertEquals(1, repository.requests)
        model.acknowledge(true); repository.write = CatalogResult.Ready(AccountErasureState("pending", null, true, false, null))
        model.request(); advanceUntilIdle(); assertEquals(2, repository.requests)
        assertEquals(1, repository.keys.distinct().size)
    }
    @Test fun erasingAndServerIneligibleStatesNeverAllowMutation() = runCase { _, repository, model ->
        repository.status = AccountErasureState("active", null, false, false, "ACCOUNT_HAS_ACTIVE_TRIPS")
        model.authenticate("fixture@example.invalid", "fixture-password"); advanceUntilIdle(); model.acknowledge(true); model.request()
        assertEquals(ErasurePhase.BLOCKED, model.state.value.phase)
        repository.status = AccountErasureState("erasing", null, false, false, null)
        model.loadStatus(); advanceUntilIdle(); model.cancel(); model.request(); advanceUntilIdle()
        assertEquals(ErasurePhase.ERASING, model.state.value.phase)
        assertEquals(0, repository.requests); assertEquals(0, repository.cancellations)
    }
    @Test fun aReadConfirmingAcceptedRequestDisposesTheNormalIdentityWithoutRepeatingPost() = runCase { identity, repository, model ->
        model.authenticate("fixture@example.invalid", "fixture-password"); advanceUntilIdle(); model.acknowledge(true)
        repository.write = CatalogResult.NetworkError
        model.request(); advanceUntilIdle(); assertEquals(1, repository.requests)
        repository.status = AccountErasureState("pending", null, true, false, null)
        model.checkStatus(); advanceUntilIdle()
        assertEquals(ErasurePhase.PENDING, model.state.value.phase)
        assertFalse(identity.view.value.signedIn); assertEquals(1, identity.logouts); assertEquals(1, repository.requests)
        model.cancel(); advanceUntilIdle(); assertEquals(ErasurePhase.VERIFY, model.state.value.phase)
    }
    @Test fun uncertainCancellationIsNeverAutomaticAndExplicitRetryKeepsTheOriginalKey() = runCase { identity, repository, model ->
        model.signedIn(false); identity.view.value = identity.view.value.copy(signedIn = false)
        repository.status = AccountErasureState("pending", null, true, false, null)
        model.authenticate("fixture@example.invalid", "fixture-password"); advanceUntilIdle()
        repository.write = CatalogResult.NetworkError
        model.cancel(); advanceUntilIdle(); assertEquals(1, repository.cancellations)
        assertTrue(model.state.value.unknownOutcome); advanceUntilIdle(); assertEquals(1, repository.cancellations)
        repository.write = CatalogResult.Ready(AccountErasureState("active", null, false, false, null))
        model.cancel(); advanceUntilIdle()
        assertEquals(2, repository.cancellations); assertEquals(1, repository.keys.distinct().size)
        assertEquals(ErasurePhase.CANCELLED, model.state.value.phase); assertFalse(identity.view.value.signedIn)
    }
    @Test fun closeAndActorSwitchDiscardLateReauthentication() = runCase { identity, repository, model ->
        val gate = CompletableDeferred<Unit>(); repository.beforeAuth = { gate.await() }
        model.authenticate("fixture@example.invalid", "fixture-password"); runCurrent()
        model.close(); gate.complete(Unit); advanceUntilIdle()
        assertEquals(ErasurePhase.VERIFY, model.state.value.phase); assertEquals(0, repository.reads)
        model.open(false); model.authenticate("fixture@example.invalid", "fixture-password")
        identity.view.value = identity.view.value.copy(generation = 7); advanceUntilIdle()
        assertEquals(ErasurePhase.VERIFY, model.state.value.phase); assertEquals(0, repository.requests)
    }
}
