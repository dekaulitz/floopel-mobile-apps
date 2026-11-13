package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerTripRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NativeTripMutationRecoveryTest {
    private val oldKey = "00000000-0000-4000-8000-000000000001"
    private val newKey = "00000000-0000-4000-8000-000000000002"
    private val draft = TripDraft("Next Trip", "2026-12-01", "2026-12-02", "Asia/Jakarta")
    @Test fun acceptedLostCancelThenTerminalReadRequiresReviewAndExplicitReleaseBeforeOneNewCreate() = runTest {
        val f = TripFixture()
        try {
            f.login(); f.cancelAcceptedLost = true
            assertEquals(CatalogResult.NetworkError, f.repository.lifecycle("trip", TripLifecycleAction.CANCEL, oldKey))
            val record = f.recovery.record
            assertEquals(TripStatus.CANCELLED, assertIs<CatalogResult.Ready<TravelerTrip>>(f.repository.detail("trip")).value.status)
            assertEquals(record, f.recovery.record)
            assertEquals(CatalogResult.HttpError(409, code = TravelerTripRepository.PENDING_MUTATION_CODE), f.repository.create("plan-b", draft, newKey))
            assertEquals(1, f.writes)
            val review = assertIs<CatalogResult.Ready<List<TravelerTrip>>>(f.repository.reviewPendingMutation())
            assertEquals(TripStatus.CANCELLED, review.value.single().status)
            assertEquals(record, f.recovery.record); assertEquals(listOf(oldKey), f.keys)
            assertEquals(CatalogResult.Ready(Unit), f.repository.startNewAction())
            assertNull(f.recovery.record); assertEquals(1, f.writes)
            assertEquals("trip-b", assertIs<CatalogResult.Ready<TravelerTrip>>(f.repository.create("plan-b", draft, newKey)).value.id)
            assertEquals(listOf(oldKey, newKey), f.keys); assertEquals(2, f.writes)
        } finally { f.client.close() }
    }
    @Test fun legitimateCancelRetryRetainsTheOriginalKeyEvenAfterTerminalReads() = runTest {
        val f = TripFixture()
        try {
            f.login(); f.cancelAcceptedLost = true
            f.repository.lifecycle("trip", TripLifecycleAction.CANCEL, oldKey)
            f.repository.detail("trip"); f.repository.mine()
            f.cancelAcceptedLost = false
            assertIs<CatalogResult.Ready<Unit>>(f.repository.lifecycle("trip", TripLifecycleAction.CANCEL, newKey))
            assertEquals(listOf(oldKey, oldKey), f.keys); assertNull(f.recovery.record)
        } finally { f.client.close() }
    }
    @Test fun failedOrChangedReviewCannotReleaseTheSavedIntent() = runTest {
        val f = TripFixture()
        try {
            f.login(); f.cancelAcceptedLost = true; f.repository.lifecycle("trip", TripLifecycleAction.CANCEL, oldKey)
            val record = requireNotNull(f.recovery.record)
            f.networkFailure = true
            assertEquals(CatalogResult.NetworkError, f.repository.reviewPendingMutation())
            assertEquals(CatalogResult.HttpError(409), f.repository.startNewAction()); assertEquals(record, f.recovery.record)
            f.networkFailure = false
            f.beforeTripList = { f.recovery.record = record.replace(oldKey, newKey) }
            assertEquals(CatalogResult.HttpError(409, code = TravelerTripRepository.PENDING_MUTATION_CODE), f.repository.reviewPendingMutation())
            assertEquals(CatalogResult.HttpError(409), f.repository.startNewAction())
            f.beforeTripList = {}; assertIs<CatalogResult.Ready<*>>(f.repository.reviewPendingMutation())
            f.recovery.record = record
            assertEquals(CatalogResult.HttpError(409), f.repository.startNewAction()); assertEquals(record, f.recovery.record)
            assertEquals(listOf(oldKey), f.keys)
        } finally { f.client.close() }
    }
    @Test fun aChangedActorOrGenerationCannotUseThePreviousReviewTicket() = runTest {
        val f = TripFixture()
        try {
            f.login(); f.cancelAcceptedLost = true; f.repository.lifecycle("trip", TripLifecycleAction.CANCEL, oldKey)
            val record = f.recovery.record
            assertIs<CatalogResult.Ready<*>>(f.repository.reviewPendingMutation())
            f.owner = "another-fixture-actor"; f.login()
            assertEquals(CatalogResult.HttpError(409), f.repository.startNewAction())
            assertEquals(CatalogResult.HttpError(401), f.repository.reviewPendingMutation())
            assertEquals(record, f.recovery.record); assertEquals(listOf(oldKey), f.keys)
        } finally { f.client.close() }
    }
}
