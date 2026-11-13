package com.floopel.feature.traveler.domain.model

import kotlin.test.*

class TripCommandRejectionTest {
    @Test fun canonicalRejectionsRequireTheirStatusEnvelopeAndFieldPair() {
        val cases = listOf(
            Triple(CatalogFieldIssue("invitation_id", "invitation_expired"), 409, TripCommandRejection.INVITATION_EXPIRED),
            Triple(CatalogFieldIssue("invitation_id", "invitation_revoked"), 409, TripCommandRejection.INVITATION_REVOKED),
            Triple(CatalogFieldIssue("status", "trip_inactive"), 409, TripCommandRejection.INACTIVE),
            Triple(CatalogFieldIssue("starts_on", "trip_not_started"), 422, TripCommandRejection.NOT_STARTED),
        )
        cases.forEach { (hint, status, expected) ->
            val code = if (status == 409) "CONFLICT" else "VALIDATION"
            assertEquals(expected, tripCommandRejection(CatalogResult.HttpError(status, code = code, fieldIssues = listOf(hint))))
            assertNull(tripCommandRejection(CatalogResult.HttpError(500, code = code, fieldIssues = listOf(hint))))
            assertNull(tripCommandRejection(CatalogResult.HttpError(status, code = "OTHER", fieldIssues = listOf(hint))))
            assertNull(tripCommandRejection(CatalogResult.HttpError(status, code = code, fieldIssues = listOf(hint.copy(field = "other")))))
        }
    }

    @Test fun localLegacyFutureAndContradictoryConflictsNeverBecomeTerminalRejections() {
        listOf(CatalogResult.HttpError(409), CatalogResult.HttpError(409, code = "CONFLICT"),
            CatalogResult.HttpError(409, code = "CONFLICT", fieldIssues = listOf(CatalogFieldIssue("invitation_id", "future_reason"))),
            CatalogResult.HttpError(409, code = "CONFLICT", fieldIssues = listOf(
                CatalogFieldIssue("invitation_id", "invitation_expired"), CatalogFieldIssue("invitation_id", "invitation_revoked"))),
            CatalogResult.InvalidResponse, CatalogResult.NetworkError, CatalogResult.Maintenance(), CatalogResult.Ready(Unit))
            .forEach { assertNull(tripCommandRejection(it), "$it") }
    }
}
