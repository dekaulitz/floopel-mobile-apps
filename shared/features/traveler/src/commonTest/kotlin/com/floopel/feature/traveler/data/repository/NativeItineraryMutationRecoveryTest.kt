package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.domain.model.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NativeItineraryMutationRecoveryTest {
    private suspend fun pending(f: ItineraryFixture): String {
        f.login(); f.acceptedCloneResponseLost = true
        assertEquals(CatalogResult.NetworkError, assertIs<ItineraryWriteResult.Failed>(f.repository.clone("source-a")).cause)
        f.now += 86_400_001L
        return requireNotNull(f.record)
    }
    @Test fun expiredAcceptedCloneRequiresReviewAndExplicitReleaseBeforeOneFreshCreate() = runTest {
        val f = ItineraryFixture()
        try {
            val record = pending(f)
            assertEquals(ItineraryWriteResult.Expired, f.repository.clone("source-a"))
            assertEquals(ItineraryWriteResult.PendingDifferentAction, f.repository.create(ItineraryDraft("Plan B", "")))
            assertIs<CatalogResult.Ready<*>>(f.repository.detail("plan")); assertIs<CatalogResult.Ready<*>>(f.repository.mine())
            assertEquals(record, f.record); assertEquals(1, f.postPaths.size); assertEquals(1, f.generatedKeys)
            assertEquals(CatalogResult.HttpError(409), f.repository.startNewAction())
            val review = assertIs<CatalogResult.Ready<List<OwnedItinerary>>>(f.repository.reviewPendingMutation())
            assertTrue(review.value.any { it.id == "copy-a" }); assertEquals(record, f.record)
            assertEquals(CatalogResult.Ready(Unit), f.repository.startNewAction()); assertNull(f.record)
            assertEquals(1, f.postPaths.size); f.freshKey()
            val created = assertIs<ItineraryWriteResult.Completed<OwnedItineraryDetail>>(f.repository.create(ItineraryDraft("Plan B", "")))
            assertEquals("plan-b", created.value.itinerary.id); assertEquals(2, f.postPaths.size)
            assertEquals(2, f.keys.distinct().size); assertEquals(2, f.generatedKeys)
        } finally { f.client.close() }
    }
    @Test fun cloneAndAddStopAlsoRemainBlockedUntilTheExplicitReviewedDecision() = runTest {
        for (clone in listOf(true, false)) {
            val f = ItineraryFixture()
            try {
                val record = pending(f)
                val blocked = if (clone) f.repository.clone("source-b") else f.repository.addStop("plan", "spot", 1)
                assertEquals(ItineraryWriteResult.PendingDifferentAction, blocked); assertEquals(record, f.record)
                assertEquals(1, f.postPaths.size)
                assertIs<CatalogResult.Ready<*>>(f.repository.reviewPendingMutation())
                assertEquals(CatalogResult.Ready(Unit), f.repository.startNewAction())
                f.acceptedCloneResponseLost = false; f.freshKey()
                val result = if (clone) f.repository.clone("source-b") else f.repository.addStop("plan", "spot", 1)
                assertIs<ItineraryWriteResult.Completed<*>>(result); assertEquals(2, f.postPaths.size)
            } finally { f.client.close() }
        }
    }
    @Test fun unexpiredSameActionRetryReusesItsOriginalKeyAndSavedReceipt() = runTest {
        val f = ItineraryFixture()
        try {
            f.login(); f.acceptedCloneResponseLost = true; f.repository.clone("source-a")
            f.acceptedCloneResponseLost = false
            assertEquals("copy-a", assertIs<ItineraryWriteResult.Completed<OwnedItineraryDetail>>(f.newRepository().clone("source-a")).value.itinerary.id)
            assertEquals<List<String?>>(listOf(f.key, f.key), f.keys); assertEquals(1, f.generatedKeys); assertNull(f.record)
            assertEquals(1, assertIs<CatalogResult.Ready<List<OwnedItinerary>>>(f.repository.mine()).value.count { it.id == "copy-a" })
        } finally { f.client.close() }
    }
    @Test fun failedStaleOrForeignActorReviewCannotReleaseTheRecord() = runTest {
        val f = ItineraryFixture()
        try {
            val record = pending(f); f.networkFailure = true
            assertEquals(CatalogResult.NetworkError, f.repository.reviewPendingMutation())
            assertEquals(CatalogResult.HttpError(409), f.repository.startNewAction()); assertEquals(record, f.record)
            f.networkFailure = false
            val changed = record.replace(f.key, f.key.replace("0001", "0999"))
            f.beforeMine = { f.record = changed }
            assertEquals(CatalogResult.HttpError(409), f.repository.reviewPendingMutation())
            assertEquals(CatalogResult.HttpError(409), f.repository.startNewAction()); assertEquals(changed, f.record)
            f.beforeMine = {}; assertIs<CatalogResult.Ready<*>>(f.repository.reviewPendingMutation())
            f.record = record; assertEquals(CatalogResult.HttpError(409), f.repository.startNewAction())
            assertIs<CatalogResult.Ready<*>>(f.repository.reviewPendingMutation())
            f.owner = "other-fixture-actor"; f.login()
            assertEquals(CatalogResult.HttpError(409), f.repository.startNewAction())
            assertEquals(CatalogResult.HttpError(401), f.repository.reviewPendingMutation())
            assertEquals(record, f.record); assertEquals(1, f.postPaths.size)
        } finally { f.client.close() }
    }
    @Test fun pendingExpiredRecordDoesNotBlockMetadataDeleteOrReorderAndThoseWritesDoNotClearIt() = runTest {
        for (action in 0..2) {
            val f = ItineraryFixture()
            try {
                val record = pending(f)
                when (action) {
                    0 -> assertIs<CatalogResult.Ready<*>>(f.repository.update("plan", ItineraryDraft("Edited title", "")))
                    1 -> { f.status = HttpStatusCode.NoContent; assertEquals(CatalogResult.Ready(Unit), f.repository.delete("plan")) }
                    else -> assertIs<CatalogResult.Ready<*>>(f.repository.reorder("plan", listOf(OwnedStop("stop", "spot", "Spot", 1, 1))))
                }
                assertEquals(record, f.record); assertEquals(1, f.postPaths.size); assertEquals(1, f.generatedKeys)
            } finally { f.client.close() }
        }
    }
}
