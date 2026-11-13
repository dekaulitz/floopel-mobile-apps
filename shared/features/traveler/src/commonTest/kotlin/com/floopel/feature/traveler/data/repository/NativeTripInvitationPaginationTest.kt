package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.domain.model.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class NativeTripInvitationPaginationTest {
    private fun id(index: Int) = "00000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
    private fun row(index: Int) = """{"id":"${id(index)}","trip_id":"trip","target_label":"traveler","status":"accepted","expires_at":"2099-01-01T00:00:00Z","trip_name":"Trip $index","inviter_username":"","trip_starts_on":"2026-11-14","trip_ends_on":"2026-11-16"}"""
    private fun page(rows: List<String>) = """{"meta":{"code":"TRIP_INVITATIONS"},"error":null,"data":[${rows.joinToString()}]}"""
    @Test fun arrayPagesUseExistingLimitAndLastUuidWithoutInventingResponseMetadata() = runTest {
        val f = TripFixture()
        try {
            f.login(); f.overrideBody = page((1..20).map(::row))
            val first = assertIs<CatalogResult.Ready<TripInvitationPage>>(f.repository.myInvitations()).value
            assertEquals((1..20).map(::id), first.items.map { it.id }); assertEquals(id(20), first.nextCursor)
            f.overrideBody = page(listOf(row(21)))
            val second = assertIs<CatalogResult.Ready<TripInvitationPage>>(f.repository.myInvitations(first.nextCursor)).value
            assertEquals(id(21), second.items.single().id); assertNull(second.nextCursor)
            assertEquals(listOf(mapOf("limit" to "20"), mapOf("limit" to "20", "cursor" to id(20))), f.queryValues)
            assertEquals(0, f.writes)
        } finally { f.client.close() }
    }
    @Test fun exactFullPageAllowsAnEmptyEndReadAndNeverWrites() = runTest {
        val f = TripFixture()
        try {
            f.login(); f.overrideBody = page((1..20).map(::row))
            val first = assertIs<CatalogResult.Ready<TripInvitationPage>>(f.repository.myInvitations()).value
            f.overrideBody = page(emptyList())
            val end = assertIs<CatalogResult.Ready<TripInvitationPage>>(f.repository.myInvitations(first.nextCursor)).value
            assertTrue(end.items.isEmpty()); assertNull(end.nextCursor); assertEquals(0, f.writes)
        } finally { f.client.close() }
    }
    @Test fun invalidLimitsFollowServerDefaultAndMalformedCursorNeverReachesHttp() = runTest {
        val f = TripFixture()
        try {
            f.login(); f.overrideBody = page(emptyList())
            for (limit in listOf(-1, 0, 101)) {
                assertIs<CatalogResult.Ready<TripInvitationPage>>(f.repository.myInvitations(limit = limit))
                assertEquals("20", f.queryValues.last()["limit"])
            }
            val count = f.paths.size
            assertIs<CatalogResult.HttpError>(f.repository.myInvitations("malformed")); assertEquals(count, f.paths.size)
            assertEquals(0, f.writes)
        } finally { f.client.close() }
    }
    @Test fun oversizedMalformedAndNonadvancingPagesCannotCreateAContinuation() = runTest {
        val f = TripFixture()
        try {
            f.login()
            for (rows in listOf((1..21).map(::row), (1..19).map(::row) + row(20).replace(id(20), "not-a-uuid"),
                (1..19).map(::row) + row(1), listOf(row(1).replace(id(1), "")))) {
                f.overrideBody = page(rows); assertEquals(CatalogResult.InvalidResponse, f.repository.myInvitations())
            }
            f.overrideBody = page((1..20).map(::row))
            assertEquals(CatalogResult.InvalidResponse, f.repository.myInvitations(id(20))); assertEquals(0, f.writes)
        } finally { f.client.close() }
    }
}
