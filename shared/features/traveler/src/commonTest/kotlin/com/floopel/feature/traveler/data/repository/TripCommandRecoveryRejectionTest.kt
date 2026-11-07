package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.domain.model.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TripCommandRecoveryRejectionTest {
    private val firstKey = "11111111-1111-4111-8111-111111111111"
    private val nextKey = "22222222-2222-4222-8222-222222222222"

    @Test fun canonicalTerminalRejectionsReleaseTheRejectedIntentWithoutReplayingIt() = runTest {
        for ((field, reason) in listOf("invitation_id" to "invitation_expired", "invitation_id" to "invitation_revoked", "status" to "trip_inactive")) {
            val f = TripFixture()
            try {
                f.login()
                f.mutationFailure = HttpStatusCode.Conflict to failure(field, reason)
                val result = f.repository.decide("invitation", InvitationDecision.ACCEPT, firstKey)
                assertNotNull(tripCommandRejection(result)); assertNull(f.recovery.record)
                assertEquals(1, f.writes)
                f.mutationFailure = null
                assertIs<CatalogResult.Ready<Unit>>(f.repository.lifecycle("trip", TripLifecycleAction.CANCEL, nextKey))
                assertEquals(2, f.writes); assertEquals(listOf(firstKey, nextKey), f.keys)
            } finally { f.client.close() }
        }
    }

    @Test fun anUnstructuredServerConflictStillRetainsAndBlocksReplacingTheUnknownIntent() = runTest {
        val f = TripFixture()
        try {
            f.login()
            f.mutationFailure = HttpStatusCode.Conflict to failure("invitation_id", "future_reason")
            assertIs<CatalogResult.HttpError>(f.repository.decide("invitation", InvitationDecision.ACCEPT, firstKey))
            assertNotNull(f.recovery.record)
            f.mutationFailure = null
            assertEquals(CatalogResult.HttpError(409), f.repository.lifecycle("trip", TripLifecycleAction.CANCEL, nextKey))
            assertEquals(1, f.writes)
        } finally { f.client.close() }
    }

    private fun failure(field: String, code: String) = """{"meta":{"request_id":"33333333-3333-4333-8333-333333333333","timestamp":0,"code":"CONFLICT"},"error":{"message":"Synthetic localized text","details":[{"field":"$field","code":"$code","message":"Ignored UI copy"}]},"data":null}"""
}
