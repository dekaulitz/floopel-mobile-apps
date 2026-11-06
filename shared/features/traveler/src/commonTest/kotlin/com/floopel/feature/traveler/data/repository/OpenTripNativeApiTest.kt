package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.domain.model.*
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

private const val publicTripId = "11111111-1111-4111-8111-111111111111"
private const val publicLocationId = "22222222-2222-4222-8222-222222222222"
private const val publicSpotId = "33333333-3333-4333-8333-333333333333"
private const val publicApplicationId = "44444444-4444-4444-8444-444444444444"
private const val publicMembershipId = "55555555-5555-4555-8555-555555555555"

/** Native boundary tests use literal wire fields from the public/owned API projections. */
class OpenTripNativeApiTest {
    @Test fun publicUpdateInstantIsParsedFromTheTripProjectionWithoutInventingLegacyMetadata() = runTest {
        withFixture { f ->
            val data = Json.parseToJsonElement(publicTripData()).jsonObject
            for (value in listOf("2026-11-05T03:10:12.123456Z", "2026-11-05T10:10:12.123456+07:00")) {
                f.overrideBody = envelope("OPEN_TRIP", kotlinx.serialization.json.JsonObject(data +
                    ("updated_at" to kotlinx.serialization.json.JsonPrimitive(value))).toString())
                val trip = assertIs<CatalogResult.Ready<OpenTrip>>(f.repository.openTrip(publicTripId)).value
                assertEquals(kotlin.time.Instant.parse("2026-11-05T03:10:12.123456Z"), trip.updatedAt)
            }
            for (value in listOf(kotlinx.serialization.json.JsonPrimitive("not-a-date"),
                kotlinx.serialization.json.JsonPrimitive(""), kotlinx.serialization.json.JsonPrimitive(42), kotlinx.serialization.json.JsonNull)) {
                f.overrideBody = envelope("OPEN_TRIP", kotlinx.serialization.json.JsonObject(data + ("updated_at" to value)).toString())
                assertEquals(CatalogResult.InvalidResponse, f.repository.openTrip(publicTripId))
            }
            f.overrideBody = envelope("OPEN_TRIP", publicTripData())
            assertNull(assertIs<CatalogResult.Ready<OpenTrip>>(f.repository.openTrip(publicTripId)).value.updatedAt)
            assertEquals(0, f.writes)
        }
    }

    @Test fun publicDescriptionIsAnOptionalStrictStringFromThePublicProjectionOnly() = runTest {
        withFixture { f ->
            val data = Json.parseToJsonElement(publicTripData()).jsonObject
            for (value in listOf(kotlinx.serialization.json.JsonPrimitive("Approved source 🌋"), kotlinx.serialization.json.JsonNull)) {
                f.overrideBody = envelope("OPEN_TRIP", kotlinx.serialization.json.JsonObject(data + ("description" to value)).toString())
                val trip = assertIs<CatalogResult.Ready<OpenTrip>>(f.repository.openTrip(publicTripId)).value
                assertEquals(if (value is kotlinx.serialization.json.JsonNull) null else "Approved source 🌋", trip.description)
            }
            f.overrideBody = envelope("OPEN_TRIP", kotlinx.serialization.json.JsonObject(data + ("description" to kotlinx.serialization.json.JsonPrimitive(42))).toString())
            assertEquals(CatalogResult.InvalidResponse, f.repository.openTrip(publicTripId))
            f.overrideBody = envelope("OPEN_TRIP", publicTripData())
            assertNull(assertIs<CatalogResult.Ready<OpenTrip>>(f.repository.openTrip(publicTripId)).value.description)
            assertEquals(0, f.writes)
        }
    }
    @Test fun personalDiscoveryUsesServerEligibilityAndRejectsAnUnfilteredResponse() = runTest {
        withFixture { f ->
            val item = publicTripData()
            f.overrideBody = envelope("OPEN_TRIPS", """{"items":[$item],"next_cursor":null}""")
            val query = OpenTripQuery(locationId = publicLocationId, canApplyOnly = true)
            val page = assertIs<CatalogResult.Ready<OpenTripPage>>(f.repository.openTrips(query)).value
            assertTrue(page.items.single().canApply)
            assertEquals("true", f.queryValues.single()["can_apply_only"])
            assertEquals(publicLocationId, f.queryValues.single()["location_id"])
            f.overrideBody = envelope("OPEN_TRIPS", """{"items":[${item.replace("\"can_apply\":true", "\"can_apply\":false").replace("\"apply_unavailable_reason\":null", "\"apply_unavailable_reason\":\"pending_application\"")}],"next_cursor":null}""")
            assertEquals(CatalogResult.InvalidResponse, f.repository.openTrips(query))
            assertIs<CatalogResult.Ready<OpenTripPage>>(f.repository.openTrips(query.copy(canApplyOnly = false)))
            assertNull(f.queryValues.last()["can_apply_only"])
            assertEquals(0, f.writes)
        }
    }
    @Test fun confirmedDecisionConflictReleasesItsIntentButUnknownOutcomesKeepTheOriginalChoice() = runTest {
        withFixture { f ->
            f.status = HttpStatusCode.Conflict
            assertIs<CatalogResult.HttpError>(f.repository.decideTripApplication(publicApplicationId, TripApplicationDecision.APPROVE, publicMembershipId))
            assertNull(f.recovery.record)
            f.status = HttpStatusCode.OK
            f.networkFailure = true
            assertEquals(CatalogResult.NetworkError, f.repository.decideTripApplication(publicApplicationId, TripApplicationDecision.REJECT, publicSpotId))
            val frozen = f.recovery.record
            val writes = f.writes
            assertEquals(CatalogResult.HttpError(409), f.repository.decideTripApplication(publicApplicationId, TripApplicationDecision.APPROVE, publicTripId))
            assertEquals(writes, f.writes)
            assertEquals(frozen, f.recovery.record)
        }
    }

    @Test fun mismatchedDecisionAcknowledgementDoesNotClearAnUnknownIntent() = runTest {
        withFixture { f ->
            f.overrideBody = envelope("TRIP_APPLICATION_UPDATED", applicationData(false))
            assertEquals(CatalogResult.InvalidResponse, f.repository.decideTripApplication(publicApplicationId, TripApplicationDecision.APPROVE, publicMembershipId))
            assertNotNull(f.recovery.record)
        }
    }

    @Test fun applicationViewerUsesTheRealProfileRatherThanTheApplicantsMembership() = runTest {
        withFixture { f ->
            val data = applicationData(true)
            val applicant = Json.parseToJsonElement(data).jsonObject.getValue("profile_id").jsonPrimitive.content
            f.overrideBody = envelope("TRIP_APPLICATION", data)
            f.owner = applicant
            val own = assertIs<CatalogResult.Ready<TripApplication>>(f.repository.tripApplication(publicApplicationId)).value
            assertEquals(TripApplicationViewer.APPLICANT, own.viewer)
            f.owner = "current-organizer-profile"
            val organizer = assertIs<CatalogResult.Ready<TripApplication>>(f.repository.tripApplication(publicApplicationId)).value
            assertEquals(TripApplicationViewer.ORGANIZER, organizer.viewer)
            assertTrue(own.currentMember && organizer.currentMember)
            f.status = HttpStatusCode.Forbidden
            assertIs<CatalogResult.HttpError>(f.repository.tripApplication(publicApplicationId))
        }
    }

    @Test fun interruptedDecisionsRestoreOnlyTheSameActorAndApplicationWithoutPosting() = runTest {
        for (decision in TripApplicationDecision.entries) withFixture { f ->
            f.networkFailure = true
            assertEquals(CatalogResult.NetworkError, f.repository.decideTripApplication(publicApplicationId, decision, publicMembershipId))
            val restarted = f.newRepository()
            assertEquals(decision, assertIs<CatalogResult.Ready<TripApplicationDecision?>>(restarted.pendingTripApplicationDecision(publicApplicationId)).value)
            assertNull(assertIs<CatalogResult.Ready<TripApplicationDecision?>>(restarted.pendingTripApplicationDecision(publicSpotId)).value)
            assertEquals(1, f.writes)
            f.owner = "another-profile"
            assertNull(assertIs<CatalogResult.Ready<TripApplicationDecision?>>(restarted.pendingTripApplicationDecision(publicApplicationId)).value)
            assertEquals(1, f.writes)
        }
    }

    @Test fun interruptedApplicationMessageIsRestoredWithoutReplayingOrExposingAnotherTripIntent() = runTest {
        withFixture { f ->
            f.networkFailure = true
            assertEquals(CatalogResult.NetworkError, f.repository.applyToOpenTrip(publicTripId, "  Frozen message  ", publicApplicationId))
            assertEquals(1, f.writes)
            val restarted = f.newRepository()
            assertEquals("Frozen message", assertIs<CatalogResult.Ready<String?>>(restarted.pendingOpenTripApplication(publicTripId)).value)
            assertNull(assertIs<CatalogResult.Ready<String?>>(restarted.pendingOpenTripApplication(publicSpotId)).value)
            assertEquals(1, f.writes)
            f.owner = "different-profile"
            assertNull(assertIs<CatalogResult.Ready<String?>>(restarted.pendingOpenTripApplication(publicTripId)).value)
        }
    }
    @Test fun inclusiveDateFiltersRoundTripAndInvalidPairsNeverReachTransport() = runTest {
        withFixture { f ->
            f.overrideBody = envelope("OPEN_TRIPS", """{"items":[],"next_cursor":null}""")
            assertIs<CatalogResult.Ready<OpenTripPage>>(f.repository.openTrips(OpenTripQuery(
                locationId = publicLocationId, fromDate = "2026-11-14", toDate = "2026-11-16")))
            assertEquals("2026-11-14", f.queryValues.single()["from_date"])
            assertEquals("2026-11-16", f.queryValues.single()["to_date"])
            assertEquals(publicLocationId, f.queryValues.single()["location_id"])
            val calls = f.paths.size
            for (query in listOf(OpenTripQuery(fromDate = "2026-11-14"), OpenTripQuery(toDate = "2026-11-16"),
                OpenTripQuery(fromDate = "2026-11-16", toDate = "2026-11-14"),
                OpenTripQuery(fromDate = "2026-02-29", toDate = "2026-03-01")))
                assertEquals(CatalogResult.HttpError(422), f.repository.openTrips(query))
            assertEquals(calls, f.paths.size)
        }
    }
    @Test fun publicFiltersRoundTripPunctuationAndUnicodeWithoutPrivateReads() = runTest {
        withFixture { f ->
            val query = "Bali & + % # 🌋"
            f.overrideBody = envelope("OPEN_TRIPS", """{"items":[${publicTripData()}],"next_cursor":null}""")
            val page = assertIs<CatalogResult.Ready<OpenTripPage>>(f.repository.openTrips(
                OpenTripQuery(query, publicLocationId, publicSpotId, limit = 1))).value
            assertEquals(publicTripId, page.items.single().id)
            assertEquals(4, page.items.single().remainingParticipants)
            assertEquals(OpenTripViewer.VISITOR, page.items.single().viewer)
            assertTrue(page.items.single().canApply)
            assertEquals(mapOf("q" to query, "location_id" to publicLocationId, "spot_id" to publicSpotId, "limit" to "1"), f.queryValues.single())
            assertEquals(listOf("/api/apps/v1/open-trips"), f.paths)
            assertEquals(1, f.identityReads)
            assertEquals(2, page.items.single().organizer.appearance?.level)
            assertEquals("https://fixture.invalid/media/organizer.webp", page.items.single().organizer.appearance?.avatarUrl)
        }
    }

    @Test fun publicQueryCountsUnicodeScalarsAndTrimsBeforeSending() = runTest {
        withFixture { f ->
            f.overrideBody = envelope("OPEN_TRIPS", """{"items":[],"next_cursor":null}""")
            assertIs<CatalogResult.Ready<OpenTripPage>>(f.repository.openTrips(OpenTripQuery("  " + "🌋".repeat(120) + "  ")))
            assertEquals("🌋".repeat(120), f.queryValues.single()["q"])
            val calls = f.paths.size
            assertEquals(CatalogResult.HttpError(422), f.repository.openTrips(OpenTripQuery("🌋".repeat(121))))
            assertEquals(calls, f.paths.size)
        }
    }

    @Test fun publicSpotPreservesRatingVisibleCommentZeroAndLocation() = runTest {
        withFixture { f ->
            f.overrideBody = envelope("OPEN_TRIP", publicTripData(spot = publicSpotData()))
            val trip = assertIs<CatalogResult.Ready<OpenTrip>>(f.repository.openTrip(publicTripId)).value
            val stop = assertNotNull(trip.previewSpot)
            assertEquals(publicLocationId, stop.location.id)
            assertEquals(4.8, stop.spot.aiReference?.score)
            assertEquals(0L, stop.spot.commentCount)
            assertEquals(GeoPoint(-6.1, 106.8), stop.spot.geo)
            f.overrideBody = envelope("OPEN_TRIP_SPOTS", """{"items":[${publicSpotData()}],"next_cursor":"opaque-next"}""")
            val route = assertIs<CatalogResult.Ready<OpenTripStopPage>>(f.repository.openTripStops(publicTripId)).value
            assertEquals("opaque-next", route.nextCursor)
            assertEquals(publicSpotId, route.items.single().spot.id)
            assertEquals(listOf("/api/apps/v1/open-trips/$publicTripId", "/api/apps/v1/open-trips/$publicTripId/spots"), f.paths)
        }
    }

    @Test fun publicSuppressionAndMissingCountersNeverBecomeGuessedRatingsOrZero() = runTest {
        withFixture { f ->
            val suppressed = publicSpotData().replace("\"score\":4.8", "\"score\":null")
                .replace("\"suppressed\":false", "\"suppressed\":true")
            f.overrideBody = envelope("OPEN_TRIP", publicTripData(spot = suppressed))
            val rating = assertIs<CatalogResult.Ready<OpenTrip>>(f.repository.openTrip(publicTripId)).value.previewSpot?.spot?.aiReference
            assertTrue(assertNotNull(rating).suppressed)
            assertNull(rating.score)
            for (invalid in listOf(publicSpotData().replace("\"comment_total\":0,", ""),
                publicSpotData().replace("\"latitude\":-6.1", "\"latitude\":null"),
                publicSpotData().replace("\"suppressed\":false", "\"suppressed\":true"))) {
                f.overrideBody = envelope("OPEN_TRIP", publicTripData(spot = invalid))
                assertEquals(CatalogResult.InvalidResponse, f.repository.openTrip(publicTripId))
            }
        }
    }

    @Test fun publicPagesRejectRepeatedCursorsOversizedPagesAndMalformedCapacity() = runTest {
        withFixture { f ->
            val item = publicTripData()
            f.overrideBody = envelope("OPEN_TRIPS", """{"items":[$item],"next_cursor":"same"}""")
            assertEquals(CatalogResult.InvalidResponse, f.repository.openTrips(OpenTripQuery(cursor = "same")))
            f.overrideBody = envelope("OPEN_TRIPS", """{"items":[$item,$item],"next_cursor":null}""")
            assertEquals(CatalogResult.InvalidResponse, f.repository.openTrips(OpenTripQuery(limit = 1)))
            f.overrideBody = envelope("OPEN_TRIP", item.replace("\"remaining_participants\":4", "\"remaining_participants\":5"))
            assertEquals(CatalogResult.InvalidResponse, f.repository.openTrip(publicTripId))
            f.overrideBody = envelope("OPEN_TRIP", item.replace(publicTripId, publicSpotId))
            assertEquals(CatalogResult.InvalidResponse, f.repository.openTrip(publicTripId))
            for (malformed in listOf(item.replace("\"participant_limit\":5", "\"participant_limit\":\"5\""),
                item.replace("\"can_apply\":true", "\"can_apply\":\"true\""))) {
                f.overrideBody = envelope("OPEN_TRIP", malformed)
                assertEquals(CatalogResult.InvalidResponse, f.repository.openTrip(publicTripId))
            }
        }
    }

    @Test fun applicationHistoryDoesNotRecreateAnEndedMembership() = runTest {
        withFixture { f ->
            for (member in listOf(false, true)) {
                f.overrideBody = envelope("TRIP_APPLICATION", applicationData(member))
                val application = assertIs<CatalogResult.Ready<TripApplication>>(f.repository.tripApplication(publicApplicationId)).value
                assertEquals(TripApplicationStatus.CLOSED, application.status)
                assertEquals("joined_elsewhere", application.closedReason)
                assertEquals(publicMembershipId, application.membershipId)
                assertEquals(member, application.currentMember)
                assertFalse(application.canApprove)
                assertFalse(application.canWithdraw)
            }
        }
    }

    @Test fun ownerAndOrganizerApplicationPagesStayScopedAndRetainServerCapabilities() = runTest {
        withFixture { f ->
            val pending = pendingApplicationData()
            f.overrideBody = envelope("TRIP_APPLICATIONS", """{"items":[$pending],"next_cursor":null}""")
            val own = assertIs<CatalogResult.Ready<TripApplicationPage>>(f.repository.myTripApplications(publicTripId)).value
            assertTrue(own.items.single().canApprove)
            assertTrue(own.items.single().canWithdraw)
            assertIs<CatalogResult.Ready<TripApplicationPage>>(f.repository.tripApplications(publicTripId))
            assertEquals(listOf("/api/apps/v1/me/trip-applications", "/api/apps/v1/trips/$publicTripId/applications"), f.paths)
            assertEquals(publicTripId, f.queryValues.first()["trip_id"])
            f.overrideBody = envelope("TRIP_APPLICATIONS", """{"items":[${pending.replace(publicTripId, publicSpotId)}],"next_cursor":null}""")
            assertEquals(CatalogResult.InvalidResponse, f.repository.tripApplications(publicTripId))
        }
    }

    @Test fun unknownStatusesRemainSafeAndMissingNullableApplicationFieldsFail() = runTest {
        withFixture { f ->
            val unknownPublic = publicTripData().replace("\"viewer_role\":\"visitor\"", "\"viewer_role\":\"future_role\"")
                .replace("upcoming", "future_schedule").replace("\"can_apply\":true", "\"can_apply\":false")
            f.overrideBody = envelope("OPEN_TRIP", unknownPublic)
            val trip = assertIs<CatalogResult.Ready<OpenTrip>>(f.repository.openTrip(publicTripId)).value
            assertEquals(OpenTripViewer.UNKNOWN, trip.viewer)
            assertEquals(OpenTripSchedule.UNKNOWN, trip.schedule)
            assertFalse(trip.canApply)
            f.overrideBody = envelope("TRIP_APPLICATION", pendingApplicationData().replace("pending", "future_status")
                .replace("\"can_approve\":true", "\"can_approve\":false").replace("\"can_withdraw\":true", "\"can_withdraw\":false"))
            assertEquals(TripApplicationStatus.UNKNOWN, assertIs<CatalogResult.Ready<TripApplication>>(f.repository.tripApplication(publicApplicationId)).value.status)
            f.overrideBody = envelope("TRIP_APPLICATION", pendingApplicationData().replace("\"membership_id\":null,", ""))
            assertEquals(CatalogResult.InvalidResponse, f.repository.tripApplication(publicApplicationId))
        }
    }

    @Test fun ambiguousApplicationRetriesTheSameNormalizedBodyAndKeyAfterTheLegacyWindow() = runTest {
        withFixture { f ->
            val message = "🌋".repeat(500)
            f.networkFailure = true
            assertEquals(CatalogResult.NetworkError, f.repository.applyToOpenTrip(publicTripId, "  $message  ", publicApplicationId))
            val retained = assertNotNull(f.recovery.record)
            assertFalse(retained.contains("fixture-password"))
            assertFalse(retained.contains("Bearer"))
            f.time += 2 * 86_400_000L
            f.networkFailure = false
            f.status = HttpStatusCode.Created
            f.overrideBody = envelope("TRIP_APPLICATION_CREATED", pendingApplicationData())
            assertIs<CatalogResult.Ready<TripApplication>>(f.newRepository().applyToOpenTrip(publicTripId, message, publicMembershipId))
            assertEquals(listOf(publicApplicationId, publicApplicationId), f.keys)
            assertEquals(message, Json.parseToJsonElement(assertNotNull(f.lastBody)).jsonObject.getValue("message").jsonPrimitive.content)
            assertEquals(2, f.writes)
            assertNull(f.recovery.record)
        }
    }

    @Test fun applicationUncertaintyCannotBeReplacedByDifferentTextOrAnotherTrip() = runTest {
        withFixture { f ->
            f.networkFailure = true
            f.repository.applyToOpenTrip(publicTripId, "Retained draft", publicApplicationId)
            val retained = f.recovery.record
            f.networkFailure = false
            assertEquals(CatalogResult.HttpError(409), f.repository.applyToOpenTrip(publicTripId, "Changed draft", publicMembershipId))
            assertEquals(CatalogResult.HttpError(409), f.repository.applyToOpenTrip(publicSpotId, "Retained draft", publicMembershipId))
            assertEquals(retained, f.recovery.record)
            assertEquals(1, f.writes)
        }
    }

    @Test fun applicationDecisionsUseLiteralAuthorizedEndpointsAndReturnServerHistory() = runTest {
        for (decision in TripApplicationDecision.entries) {
            withFixture { f ->
                val status = when (decision) {
                    TripApplicationDecision.APPROVE -> "approved"
                    TripApplicationDecision.REJECT -> "rejected"
                    TripApplicationDecision.WITHDRAW -> "withdrawn"
                }
                var data = applicationData(decision == TripApplicationDecision.APPROVE)
                    .replace("\"status\":\"closed\"", "\"status\":\"$status\"")
                    .replace("\"closed_reason\":\"joined_elsewhere\"", "\"closed_reason\":null")
                if (decision != TripApplicationDecision.APPROVE) data = data.replace("\"membership_id\":\"$publicMembershipId\"", "\"membership_id\":null")
                f.overrideBody = envelope("TRIP_APPLICATION_UPDATED", data)
                val result = assertIs<CatalogResult.Ready<TripApplication>>(
                    f.repository.decideTripApplication(publicApplicationId, decision, publicMembershipId)).value
                assertEquals(status, result.status.wire)
                assertEquals("/api/apps/v1/trip-applications/$publicApplicationId/${decision.wire}", f.paths.last())
                assertEquals(1, f.writes)
            }
        }
    }

    @Test fun oversizedUnicodeApplicationAndInvalidDecisionResourceNeverWrite() = runTest {
        withFixture { f ->
            assertEquals(CatalogResult.HttpError(422), f.repository.applyToOpenTrip(publicTripId, "🌋".repeat(501), publicApplicationId))
            assertEquals(CatalogResult.HttpError(404), f.repository.decideTripApplication("invalid", TripApplicationDecision.APPROVE, publicApplicationId))
            assertTrue(f.paths.isEmpty())
            assertEquals(0, f.writes)
        }
    }

    private suspend fun withFixture(block: suspend (TripFixture) -> Unit) {
        val fixture = TripFixture()
        try { fixture.login(); block(fixture) } finally { fixture.client.close() }
    }
}

private fun envelope(code: String, data: String) = """{"meta":{"code":"$code"},"error":null,"data":$data}"""
private fun publicTripData(spot: String = "null") = """{"id":"$publicTripId","name":"Public fixture","starts_on":"2026-11-04","ends_on":"2026-11-06","timezone":"Asia/Makassar","schedule_state":"upcoming","registration_opens_at":"2026-11-01T01:00:00Z","registration_closes_at":"2026-11-04T01:00:00Z","participant_count":1,"participant_limit":5,"organizer_count":1,"remaining_participants":4,"organizer":{"profile_id":"real-profile-id","username":"organizer","display_name":"Organizer"},"locations":[],"locations_truncated":false,"banner_image":null,"preview_spot":$spot,"viewer_role":"visitor","application_id":null,"application_status":null,"can_apply":true,"apply_unavailable_reason":null}"""
private fun publicSpotData() = """{"spot_id":"$publicSpotId","name":"Public Spot","slug":"public-spot","location":{"id":"$publicLocationId","name":"City fixture","slug":"city-fixture","level":"city"},"day":1,"position":0,"latitude":-6.1,"longitude":106.8,"reference_rating":{"score":4.8,"confidence":0.9,"provided_at":"2026-11-01T01:00:00Z","origin":"ai","label":"AI rating","suppressed":false},"comment_total":0,"cover_image":null}"""
private fun applicationData(currentMember: Boolean) = """{"id":"$publicApplicationId","trip_id":"$publicTripId","profile_id":"real-applicant-profile","applicant":{"username":"applicant","display_name":"Applicant"},"trip":{"name":"Public fixture","starts_on":"2026-11-04","ends_on":"2026-11-06","timezone":"Asia/Makassar","status":"active","registration_opens_at":"2026-11-01T01:00:00Z","registration_closes_at":"2026-11-04T01:00:00Z"},"status":"closed","message":"Private fixture message","membership_id":"$publicMembershipId","closed_reason":"joined_elsewhere","current_member":$currentMember,"can_approve":false,"approval_unavailable_reason":"application_resolved","can_withdraw":false,"admission_closed":false,"created_at":"2026-11-01T01:00:00Z","updated_at":"2026-11-01T02:00:00Z","decided_at":"2026-11-01T02:00:00Z"}"""
private fun pendingApplicationData() = applicationData(false).replace("\"status\":\"closed\"", "\"status\":\"pending\"")
    .replace("\"membership_id\":\"$publicMembershipId\"", "\"membership_id\":null")
    .replace("\"closed_reason\":\"joined_elsewhere\"", "\"closed_reason\":null")
    .replace("\"can_approve\":false", "\"can_approve\":true").replace("\"can_withdraw\":false", "\"can_withdraw\":true")
    .replace("\"approval_unavailable_reason\":\"application_resolved\"", "\"approval_unavailable_reason\":null")
    .replace("\"decided_at\":\"2026-11-01T02:00:00Z\"", "\"decided_at\":null")
