package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.domain.model.CatalogFieldIssue
import com.floopel.feature.traveler.domain.model.CatalogResult
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TripTransferPrivacyTest {
    @Test fun confirmedTransferDenialPreservesTheCanonicalDetailAndReleasesOnlyTheRejectedIntent() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            fixture.status = HttpStatusCode.UnprocessableEntity
            fixture.overrideBody = failure("""{"field":"profile_discoverable","code":"public_profile_required","message":"Not UI copy"}""")
            val result = assertIs<CatalogResult.HttpError>(fixture.repository.transferOrganizer("trip", "private-target", "transfer-one"))
            assertEquals("VALIDATION", result.code)
            assertEquals(listOf(CatalogFieldIssue("profile_discoverable", "public_profile_required")), result.fieldIssues)
            assertEquals("""{"new_organizer_profile_id":"private-target"}""", fixture.lastBody)
            assertNull(fixture.recovery.read())
            assertEquals(1, fixture.writes)
            fixture.overrideBody = failure("""{"field":"other","code":"other"}""")
            fixture.repository.transferOrganizer("trip", "other-target", "transfer-two")
            assertEquals(listOf("transfer-one", "transfer-two"), fixture.keys)
        } finally { fixture.client.close() }
    }

    @Test fun malformedDetailsAndHtmlDoNotCreateAPrivacyRequirement() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            fixture.status = HttpStatusCode.UnprocessableEntity
            val bodies = listOf("<html>public_profile_required</html>",
                failure("""{"field":42,"code":"public_profile_required"}"""),
                failure("""{"field":"profile_discoverable","code":"public_profile_required"}""").replace("\"data\":null", "\"data\":{}"))
            bodies.forEachIndexed { index, body ->
                fixture.overrideBody = body
                val result = assertIs<CatalogResult.HttpError>(fixture.repository.transferOrganizer("trip", "target", "invalid-$index"))
                assertTrue(result.fieldIssues.isEmpty())
            }
            assertEquals(bodies.size, fixture.writes)
        } finally { fixture.client.close() }
    }

    private fun failure(detail: String) = """{"meta":{"request_id":"11111111-1111-4111-8111-111111111111","code":"VALIDATION","timestamp":1},"error":{"message":"Not UI copy","details":[$detail]},"data":null}"""
}
