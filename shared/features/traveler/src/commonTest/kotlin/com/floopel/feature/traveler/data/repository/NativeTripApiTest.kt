package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.data.remote.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.*
import com.floopel.feature.traveler.feature.legal.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*

private const val noticeId = "11111111-1111-4111-8111-111111111111"
private const val noticeTripId = "22222222-2222-4222-8222-222222222222"
private const val noticeMemberId = "33333333-3333-4333-8333-333333333333"

class NativeTripApiTest {
    @Test fun memberAffordancesUseServerFlagsWithoutRecomputingFromTheDeviceCalendar() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            val public = fixture.trip.replace("\"visibility\":\"private\"", "\"visibility\":\"public\"")
                .replace("\"registration_opens_at\":null", "\"registration_opens_at\":\"2026-10-19T00:00:00Z\"")
                .replace("\"registration_closes_at\":null", "\"registration_closes_at\":\"2026-10-21T00:00:00Z\"")
            for (editable in listOf(false, true)) {
                fixture.overrideBody = noticeEnvelope(public.replace("\"can_edit_registration\":false", "\"can_edit_registration\":$editable"))
                val trip = assertIs<CatalogResult.Ready<TravelerTrip>>(fixture.repository.detail("trip")).value
                val capabilities = assertNotNull(trip.capabilities)
                assertEquals(TripVisibility.PUBLIC, capabilities.visibility)
                assertEquals(editable, capabilities.canEditRegistration)
                assertTrue(capabilities.canInvite)
                assertEquals(4, capabilities.remainingParticipants)
            }
            fixture.overrideBody = noticeEnvelope(public.replace("\"can_invite\":true", "\"can_invite\":false")
                .replace("\"invite_unavailable_reason\":null", "\"invite_unavailable_reason\":\"schedule_ended\""))
            val ended = assertIs<CatalogResult.Ready<TravelerTrip>>(fixture.repository.detail("trip")).value
            assertEquals(TripStatus.ACTIVE, ended.status)
            assertFalse(assertNotNull(ended.capabilities).canInvite)
            assertEquals("schedule_ended", ended.capabilities?.inviteUnavailableReason)
        } finally { fixture.client.close() }
    }

    @Test fun malformedOrMissingMemberCapabilitiesAreNotInferredAsPermission() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            listOf(fixture.trip.replace("\"can_invite\":true,", ""),
                fixture.trip.replace("\"can_invite\":true", "\"can_invite\":\"true\""),
                fixture.trip.replace("\"participant_limit\":5", "\"participant_limit\":\"5\""),
                fixture.trip.replace("\"can_edit_registration\":false", "\"can_edit_registration\":true"),
                fixture.trip.replace("\"caller_role\":\"organizer\"", "\"caller_role\":\"participant\""))
                .forEach { malformed ->
                    fixture.overrideBody = noticeEnvelope(malformed)
                    assertEquals(CatalogResult.InvalidResponse, fixture.repository.detail("trip"))
                }
        } finally { fixture.client.close() }
    }

    @Test fun ownedNoticeKeepsTheHistoricalEpisodeSeparateFromCurrentMembership() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            val data = ownedNoticeData()
            for (current in listOf(false, true)) {
                fixture.overrideBody = noticeEnvelope(data.replace("\"current_member\":false", "\"current_member\":$current"))
                val notice = assertIs<CatalogResult.Ready<TripOutcomeNotice>>(fixture.repository.ownedTripNotice(noticeId)).value
                assertEquals(current, notice.currentMember)
                assertEquals(TripNoticeMembership.REMOVED, notice.membershipStatus)
                assertEquals(TripNoticeOutcome.MEMBER_REMOVED, notice.outcome)
                assertEquals("2026-11-04", notice.startsOn)
                assertEquals("Asia/Makassar", notice.timezone)
            }
            assertEquals(listOf("/api/apps/v1/me/trip-notices/$noticeId", "/api/apps/v1/me/trip-notices/$noticeId"), fixture.paths)
            assertEquals(0, fixture.writes)
        } finally { fixture.client.close() }
    }

    @Test fun ownedNoticeHandlesApplicantAndUnknownOutcomesWithoutInventingMembership() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            val applicant = ownedNoticeData().replace("member_removed", "cancelled")
                .replace("\"membership_id\":\"$noticeMemberId\"", "\"membership_id\":null")
                .replace("\"membership_status\":\"removed\"", "\"membership_status\":null")
                .replace("\"application_id\":null", "\"application_id\":\"$noticeMemberId\"")
                .replace("\"application_status\":null", "\"application_status\":\"pending\"")
            fixture.overrideBody = noticeEnvelope(applicant)
            val notice = assertIs<CatalogResult.Ready<TripOutcomeNotice>>(fixture.repository.ownedTripNotice(noticeId)).value
            assertNull(notice.membershipId)
            assertEquals(TripApplicationStatus.PENDING, notice.applicationStatus)
            assertFalse(notice.currentMember)
            fixture.overrideBody = noticeEnvelope(applicant.replace("cancelled", "future_outcome").replace("\"trip_status\":\"active\"", "\"trip_status\":\"future_status\""))
            val unknown = assertIs<CatalogResult.Ready<TripOutcomeNotice>>(fixture.repository.ownedTripNotice(noticeId)).value
            assertEquals(TripNoticeOutcome.UNKNOWN, unknown.outcome)
            assertNull(unknown.tripStatus)
        } finally { fixture.client.close() }
    }

    @Test fun ownedNoticeRejectsMissingNullableFieldsInvalidEpisodesAndMalformedAuthority() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            val data = ownedNoticeData()
            listOf(
                data.replace("\"application_id\":null,", ""),
                data.replace("\"application_id\":null", "\"application_id\":\"$noticeMemberId\""),
                data.replace("\"membership_id\":\"$noticeMemberId\"", "\"membership_id\":null"),
                data.replace("2026-11-04", "2026-02-30"),
                data.replace("\"current_member\":false", "\"current_member\":\"false\""),
                data.replace(noticeId, noticeMemberId),
                data.replace("\"timezone\":\"Asia/Makassar\"", "\"timezone\":\"\""),
            ).forEach { invalid ->
                fixture.overrideBody = noticeEnvelope(invalid)
                assertEquals(CatalogResult.InvalidResponse, fixture.repository.ownedTripNotice(noticeId))
            }
            val reads = fixture.paths.size
            assertEquals(CatalogResult.HttpError(404), fixture.repository.ownedTripNotice("invalid"))
            assertEquals(reads, fixture.paths.size)
        } finally { fixture.client.close() }
    }

    @Test fun tripFailuresPreserveCanonicalKnownAndUnknownCodes() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            fixture.status = HttpStatusCode.UnprocessableEntity
            for (code in listOf("VALIDATION", "FUTURE_TRIP_RULE")) {
                fixture.overrideBody = """{"meta":{"request_id":"$noticeId","timestamp":1,"code":"$code"},"error":{"message":"Synthetic failure","details":null},"data":null}"""
                val failure = assertIs<CatalogResult.HttpError>(fixture.repository.ownedTripNotice(noticeId))
                assertEquals(code, failure.code)
                assertEquals(422, failure.status)
            }
        } finally { fixture.client.close() }
    }

    private fun noticeEnvelope(data: String) = """{"meta":{"code":"TRIP_NOTICE"},"error":null,"data":$data}"""
    private fun ownedNoticeData() = """{"id":"$noticeId","trip_id":"$noticeTripId","outcome":"member_removed","trip_name":"Synthetic Trip","starts_on":"2026-11-04","ends_on":"2026-11-06","timezone":"Asia/Makassar","occurred_at":"2026-11-04T01:00:00Z","trip_status":"active","membership_id":"$noticeMemberId","application_id":null,"membership_status":"removed","application_status":null,"current_member":false}"""

    @Test fun invitationSummaryAllowsEmptyWorkingPlansButRejectsNegativeCounts() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            fun response(days: Int, stops: Int) = """{"meta":{"code":"OK"},"error":null,"data":[{"id":"invitation","trip_id":"trip","target_label":"traveler","status":"pending","expires_at":"2099-01-01T00:00:00Z","itinerary_summary":{"id":"plan","title":"Plan","day_count":$days,"stop_count":$stops}}]}"""
            fixture.overrideBody = response(0, 0)
            val invitation = assertIs<CatalogResult.Ready<List<TripInvitation>>>(fixture.repository.myInvitations()).value.single()
            assertEquals(0, invitation.itinerary?.days)
            assertEquals(0, invitation.itinerary?.stops)
            listOf(-1 to 0, 0 to -1).forEach { (days, stops) ->
                fixture.overrideBody = response(days, stops)
                assertEquals(CatalogResult.InvalidResponse, fixture.repository.myInvitations())
            }
        } finally { fixture.client.close() }
    }
    @Test fun completedTripsStillUseLiveWorkingCoverageAndEmptyCoverageDoesNotAddAGate() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            val canonical = fixture.trip.replace("\"locations\":[]", "\"locations\":$coverage")
                .replace("\"status\":\"active\"", "\"status\":\"completed\"")
                .replace("\"can_invite\":true", "\"can_invite\":false")
                .replace("\"invite_unavailable_reason\":null", "\"invite_unavailable_reason\":\"trip_closed\"")
            fun response(data: String) = """{"meta":{"code":"OK"},"error":null,"data":$data}"""
            fixture.overrideBody = response(canonical)
            val trip = assertIs<CatalogResult.Ready<TravelerTrip>>(fixture.repository.detail("trip")).value
            assertEquals(TripStatus.COMPLETED, trip.status)
            assertEquals(coverageLocations, trip.locations)
            fixture.overrideBody = response(canonical.replace("\"locations\":$coverage", "\"locations\":[]"))
            assertTrue(assertIs<CatalogResult.Ready<TravelerTrip>>(fixture.repository.detail("trip")).value.locations.isEmpty())
            fixture.overrideBody = response(canonical.replace("\"locations\":$coverage,", ""))
            assertEquals(CatalogResult.InvalidResponse, fixture.repository.detail("trip"))
            fixture.overrideBody = response(canonical.replace("\"locations\":$coverage", "\"locations\":null"))
            assertEquals(CatalogResult.InvalidResponse, fixture.repository.detail("trip"))
        } finally { fixture.client.close() }
    }
    @Test fun memberAndInviterPhotosComeFromOneIdentityBatchPerRead() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            fixture.overrideBody = """{"meta":{"code":"OK"},"error":null,"data":[{"profile_id":"member","username":"traveler","display_name":"Traveler","role":"participant","status":"active"}]}"""
            val member = assertIs<CatalogResult.Ready<List<TripMember>>>(fixture.repository.members("trip")).value.single()
            assertEquals(2, member.level)
            assertEquals("https://fixture.invalid/media/traveler.webp", member.avatarUrl)
            assertEquals(1, fixture.identityReads)
            fixture.overrideBody = null
            val invitation = assertIs<CatalogResult.Ready<List<TripInvitation>>>(fixture.repository.myInvitations()).value.single()
            assertEquals("https://fixture.invalid/media/organizer.webp", invitation.inviterAvatarUrl)
            assertEquals(2, fixture.identityReads)
        } finally { fixture.client.close() }
    }
    @Test fun pendingInviteeIsCallerAccessOnlyAndCannotAppearAsAMemberRole() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            val invitationId = "33333333-3333-4333-8333-333333333333"
            val preview = fixture.trip.replace("\"caller_role\":\"organizer\"", "\"caller_role\":\"invitee\",\"invitation_id\":\"$invitationId\"")
                .replace("\"can_invite\":true", "\"can_invite\":false")
                .replace("\"invite_unavailable_reason\":null", "\"invite_unavailable_reason\":\"organizer_required\"")
            fixture.overrideBody = """{"meta":{"code":"OK"},"error":null,"data":$preview}"""
            val trip = assertIs<CatalogResult.Ready<TravelerTrip>>(fixture.repository.detail("trip")).value
            assertEquals(TripCallerRole.INVITEE, trip.role)
            assertEquals(invitationId, trip.invitationId)
            fixture.overrideBody = """{"meta":{"code":"OK"},"error":null,"data":[{"profile_id":"member","username":"traveler","display_name":"Traveler","role":"invitee","status":"active"}]}"""
            assertEquals(CatalogResult.InvalidResponse, fixture.repository.members("trip"))
            fixture.overrideBody = """{"meta":{"code":"OK"},"error":null,"data":${preview.replace("\"invitee\"", "\"participant\"")}}"""
            assertEquals(CatalogResult.InvalidResponse, fixture.repository.detail("trip"))
        } finally { fixture.client.close() }
    }
    private val draft = TripDraft("Trip", "2026-10-20", "2026-10-21", "Asia/Jakarta")
    private val firstKey = "00000000-0000-4000-8000-000000000001"
    private val nextKey = "00000000-0000-4000-8000-000000000002"

    @Test fun publicWindowPatchRestoresExactFieldsWithoutChangingVisibility() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            val public = draft.copy(visibility = TripVisibility.PUBLIC,
                registrationOpensAt = "2026-10-15T01:00:00Z", registrationClosesAt = "2026-10-21T16:00:00Z")
            fixture.networkFailure = true
            assertEquals(CatalogResult.NetworkError, fixture.repository.update("trip", public, firstKey))
            val body = requireNotNull(fixture.lastBody)
            assertFalse(Json.parseToJsonElement(body).jsonObject.containsKey("visibility"))
            val recovered = assertIs<CatalogResult.Ready<TripDraft?>>(fixture.repository.pendingDraft("trip", false)).value
            assertEquals(public, recovered)
            fixture.networkFailure = false
            assertIs<CatalogResult.Ready<TravelerTrip>>(fixture.repository.update("trip", requireNotNull(recovered), nextKey))
            assertEquals(body, fixture.lastBody)
            assertEquals(listOf(firstKey, firstKey), fixture.keys)
        } finally { fixture.client.close() }
    }

    @Test fun legacyPatchOmissionDoesNotInventPrivateTypeAndItsReplayKeepsTheOriginalBody() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            fixture.networkFailure = true
            fixture.repository.update("trip", draft, firstKey)
            val body = fixture.lastBody
            val recovered = requireNotNull(assertIs<CatalogResult.Ready<TripDraft?>>(fixture.repository.pendingDraft("trip", false)).value)
            assertEquals(TripVisibility.UNKNOWN, recovered.visibility)
            fixture.networkFailure = false
            assertIs<CatalogResult.Ready<TravelerTrip>>(fixture.repository.update("trip", recovered, nextKey))
            assertEquals(body, fixture.lastBody)
            assertEquals(listOf(firstKey, firstKey), fixture.keys)
        } finally { fixture.client.close() }
    }

    @Test fun tripNameLimitCountsUnicodeScalarsInsteadOfRejectingValidSupplementaryCharacters() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            assertIs<CatalogResult.Ready<TravelerTrip>>(fixture.repository.create("plan", draft.copy(name = "🌋".repeat(120)), firstKey))
            assertEquals(CatalogResult.HttpError(422), fixture.repository.create("plan", draft.copy(name = "🌋".repeat(121)), nextKey))
            assertEquals(1, fixture.writes)
        } finally { fixture.client.close() }
    }

    @Test fun publicCreationRestoresExactAdmissionFieldsAndKeyWithoutAnotherPost() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            val public = draft.copy(timezone = "Asia/Makassar", visibility = TripVisibility.PUBLIC,
                registrationOpensAt = "2026-10-15T01:00:00Z", registrationClosesAt = "2026-10-21T16:00:00Z",
                approvedDescription = "<p>Reviewed source 🌋</p>")
            fixture.networkFailure = true
            assertEquals(CatalogResult.NetworkError, fixture.repository.create("plan", public, firstKey))
            val body = requireNotNull(fixture.lastBody)
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals("public", json.getValue("visibility").jsonPrimitive.content)
            assertEquals(public.registrationClosesAt, json.getValue("registration_closes_at").jsonPrimitive.content)
            assertEquals(public.approvedDescription, json.getValue("approved_description").jsonPrimitive.content)
            assertEquals(public, assertIs<CatalogResult.Ready<TripDraft?>>(fixture.repository.pendingDraft("plan", true)).value)
            val writes = fixture.writes
            fixture.networkFailure = false
            assertIs<CatalogResult.Ready<TravelerTrip>>(fixture.repository.create("plan", public, nextKey))
            assertEquals(body, fixture.lastBody)
            assertEquals(listOf(firstKey, firstKey), fixture.keys)
            assertEquals(writes + 1, fixture.writes)
        } finally { fixture.client.close() }
    }

    @Test fun missingSourceTimezoneAndPrivateRegistrationNeverSendAWrite() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            assertEquals(CatalogResult.HttpError(422), fixture.repository.create("plan", draft.copy(timezone = ""), firstKey))
            assertEquals(CatalogResult.HttpError(422), fixture.repository.create("plan", draft.copy(approvedDescription = "Private content"), firstKey))
            assertEquals(CatalogResult.HttpError(422), fixture.repository.create("plan", draft.copy(registrationOpensAt = "2026-10-15T01:00:00Z"), firstKey))
            assertEquals(0, fixture.writes)
        } finally { fixture.client.close() }
    }

    @Test
    fun confirmedCreationWithoutBannerSurvivesRepositoryRecreationUntilAcknowledged() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            val created =
                assertIs<CatalogResult.Ready<TravelerTrip>>(
                        fixture.repository.create("plan", draft, firstKey)
                    )
                    .value
            assertTrue(fixture.recovery.record!!.contains("confirmed_trip_id"))
            val restarted = fixture.newRepository()
            assertEquals(draft, assertIs<CatalogResult.Ready<TripDraft?>>(restarted.pendingDraft("plan", true)).value)
            assertEquals(
                created,
                assertIs<CatalogResult.Ready<TravelerTrip?>>(restarted.pendingCreatedTrip("plan"))
                    .value,
            )
            assertEquals(1, fixture.writes)
            assertNull(
                assertIs<CatalogResult.Ready<TravelerTrip?>>(
                        restarted.pendingCreatedTrip("another-plan")
                    )
                    .value
            )
            assertEquals(
                CatalogResult.Ready(Unit),
                restarted.completeTripCreation("different-trip"),
            )
            assertNotNull(fixture.recovery.record)
            assertEquals(CatalogResult.Ready(Unit), restarted.completeTripCreation(created.id))
            assertNull(fixture.recovery.record)
            assertEquals(1, fixture.writes)
        } finally {
            fixture.client.close()
        }
    }

    @Test
    fun uncertainBannerRetryUsesIdenticalMultipartBytesBoundaryAndKey() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            fixture.networkFailure = true
            val bytes = byteArrayOf(0, 1, 2, -1)
            assertEquals(
                CatalogResult.NetworkError,
                fixture.repository.uploadBanner("trip", bytes, "image/png", firstKey),
            )
            fixture.networkFailure = false
            val result =
                assertIs<CatalogResult.Ready<TripBannerStatus>>(
                        fixture.repository.uploadBanner("trip", bytes, "image/png", firstKey)
                    )
                    .value
            assertEquals(TripBannerStatus("banner", CoverProcessing.PROCESSING, false), result)
            assertEquals(2, fixture.bannerPayloads.size)
            assertEquals(fixture.bannerPayloads.first(), fixture.bannerPayloads.last())
            assertTrue(fixture.bannerPayloads.first().first.contains("FloopelUpload-$firstKey"))
            assertTrue(
                fixture.bannerPayloads
                    .first()
                    .second
                    .toByteArray()
                    .decodeToString()
                    .contains("filename=cover.png")
            )
            assertEquals(listOf(firstKey, firstKey), fixture.keys)
            assertNull(fixture.recovery.record)
        } finally {
            fixture.client.close()
        }
    }

    @Test
    fun invalidBannerIsRejectedBeforeTransportAndReadStatusKeepsProcessingState() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            for ((bytes, mime, key) in
                listOf(
                    Triple(byteArrayOf(), "image/png", firstKey),
                    Triple(ByteArray(5 * 1024 * 1024 + 1), "image/png", firstKey),
                    Triple(byteArrayOf(1), "image/gif", firstKey),
                    Triple(byteArrayOf(1), "image/png", ""),
                )) {
                assertEquals(
                    CatalogResult.HttpError(422),
                    fixture.repository.uploadBanner("trip", bytes, mime, key),
                )
            }
            assertEquals(0, fixture.writes)
            assertEquals(
                CoverProcessing.PROCESSING,
                assertIs<CatalogResult.Ready<TripBannerStatus>>(
                        fixture.repository.bannerStatus("trip")
                    )
                    .value
                    .processing,
            )
            fixture.overrideBody =
                """{"error":null,"data":{"media_id":"banner","processing_status":"unknown","retryable":false}}"""
            assertEquals(CatalogResult.InvalidResponse, fixture.repository.bannerStatus("trip"))
        } finally {
            fixture.client.close()
        }
    }

    @Test
    fun uncertainCreationRestoresExactIntentAndKeyAcrossRepositoryRecreation() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            fixture.commitCreations = true
            fixture.networkFailure = true
            assertEquals(
                CatalogResult.NetworkError,
                fixture.repository.create("plan", draft, firstKey),
            )
            assertEquals(1, fixture.writes)
            fixture.networkFailure = false
            val restarted = fixture.newRepository()
            assertEquals(
                draft,
                assertIs<CatalogResult.Ready<TripDraft?>>(restarted.pendingDraft("plan", true))
                    .value,
            )
            assertFalse(fixture.recovery.record!!.contains("fixture-password"))
            assertFalse(fixture.recovery.record!!.contains("Bearer"))
            val created = assertIs<CatalogResult.Ready<TravelerTrip>>(restarted.create("plan", draft, nextKey)).value
            assertEquals(listOf(firstKey, firstKey), fixture.keys)
            assertEquals(1, fixture.committedCreationKeys.size)
            assertNotNull(fixture.recovery.record)
            assertEquals(CatalogResult.Ready(Unit), restarted.completeTripCreation(created.id))
            assertNull(fixture.recovery.record)
        } finally {
            fixture.client.close()
        }
    }

    @Test
    fun pendingIntentCannotBeReplacedWithoutSuccessfulAuthoritativeReview() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            fixture.networkFailure = true
            fixture.repository.create("plan", draft, firstKey)
            assertEquals(CatalogResult.HttpError(409), fixture.repository.startNewAction())
            assertEquals(
                CatalogResult.HttpError(409),
                fixture.repository.create("plan", draft.copy(name = "Changed"), nextKey),
            )
            assertEquals(1, fixture.writes)
            assertEquals(CatalogResult.NetworkError, fixture.repository.reviewPendingMutation())
            assertEquals(CatalogResult.HttpError(409), fixture.repository.startNewAction())
            fixture.networkFailure = false
            assertIs<CatalogResult.Ready<*>>(fixture.repository.reviewPendingMutation())
            assertEquals(CatalogResult.Ready(Unit), fixture.repository.startNewAction())
            assertIs<CatalogResult.Ready<*>>(
                fixture.repository.create("plan", draft.copy(name = "Changed"), nextKey)
            )
            assertEquals(listOf(firstKey, nextKey), fixture.keys)
        } finally {
            fixture.client.close()
        }
    }

    @Test
    fun expiredLegacyUpdateNeverResendsOutsideTheIdempotencyWindow() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            fixture.networkFailure = true
            fixture.repository.update("trip", draft, firstKey)
            fixture.networkFailure = false
            fixture.time += 86_400_000L
            assertEquals(
                CatalogResult.HttpError(409),
                fixture.newRepository().update("trip", draft, nextKey),
            )
            assertEquals(1, fixture.writes)
        } finally {
            fixture.client.close()
        }
    }

    @Test fun durableCreationKeepsItsOriginalKeyAfterTheLegacyWindow() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            fixture.commitCreations = true
            fixture.networkFailure = true
            assertEquals(CatalogResult.NetworkError, fixture.repository.create("plan", draft, firstKey))
            fixture.networkFailure = false
            fixture.time += 86_400_001L
            assertIs<CatalogResult.Ready<TravelerTrip>>(fixture.newRepository().create("plan", draft, nextKey))
            assertEquals(listOf(firstKey, firstKey), fixture.keys)
            assertEquals(1, fixture.committedCreationKeys.size)
        } finally { fixture.client.close() }
    }

    @Test fun interruptedCreationRecoversByReadAndRetainsAnAbsentOperationIntent() = runTest {
        for (committed in listOf(false, true)) {
            val fixture = TripFixture()
            try {
                fixture.login()
                fixture.commitCreations = committed
                fixture.networkFailure = true
                fixture.repository.createWithBanner("plan", draft, firstKey)
                fixture.networkFailure = false
                val result = assertIs<CatalogResult.Ready<TravelerTrip?>>(fixture.newRepository().pendingCreatedTrip("plan"))
                assertEquals(committed, result.value != null)
                assertEquals(1, fixture.writes)
                assertNotNull(fixture.recovery.record)
                assertEquals(committed, fixture.recovery.record!!.contains("confirmed_trip_id"))
                assertTrue(fixture.paths.any { it == "/api/apps/v1/me/trip-creation-operations/$firstKey" })
            } finally { fixture.client.close() }
        }
    }

    @Test fun acknowledgedCreationCanStillBeRecoveredByItsSavedRouteKeyWithoutAnotherWrite() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login(); fixture.commitCreations = true
            val trip = assertIs<CatalogResult.Ready<TravelerTrip>>(fixture.repository.create("plan", draft, firstKey)).value
            fixture.repository.completeTripCreation(trip.id)
            assertNull(fixture.recovery.record)
            val recreated = fixture.newRepository()
            assertEquals(trip.id, assertIs<CatalogResult.Ready<TravelerTrip?>>(recreated.createdTripForOperation(firstKey)).value?.id)
            assertEquals(CatalogResult.Ready(null), recreated.createdTripForOperation(nextKey))
            assertEquals(1, fixture.writes)
        } finally { fixture.client.close() }
    }

    @Test fun reenteredAndLegacyRoutesKeepTheOriginalCreationAfterAcknowledgement() = runTest {
        for (routeKey in listOf(nextKey, "restored-trip-create:plan")) {
            val fixture = TripFixture()
            try {
                fixture.login(); fixture.commitCreations = true; fixture.networkFailure = true
                fixture.repository.create("plan", draft, firstKey)
                fixture.networkFailure = false
                val reentered = fixture.newRepository()
                val confirmed = assertIs<CatalogResult.Ready<TravelerTrip?>>(reentered.pendingCreatedTrip("plan")).value!!
                fixture.creationRoutes.failWrites = true
                assertEquals(CatalogResult.InvalidResponse, reentered.rememberTripCreationRoute(routeKey, confirmed.id))
                assertNotNull(fixture.recovery.record)
                fixture.creationRoutes.failWrites = false
                assertEquals(CatalogResult.Ready(Unit), reentered.rememberTripCreationRoute(routeKey, confirmed.id))
                reentered.completeTripCreation(confirmed.id)
                assertNull(fixture.recovery.record)
                assertEquals(confirmed.id, assertIs<CatalogResult.Ready<TravelerTrip?>>(fixture.newRepository().createdTripForOperation(routeKey)).value?.id)
                assertEquals(1, fixture.writes)
                fixture.committedCreationKeys.clear() // The owned operation reader is now unavailable.
                assertEquals(404, assertIs<CatalogResult.HttpError>(fixture.newRepository().createdTripForOperation(routeKey)).status)
                assertEquals(1, fixture.writes)
                fixture.owner = "another-profile"
                assertEquals(CatalogResult.Ready(null), fixture.newRepository().createdTripForOperation(routeKey))
            } finally { fixture.client.close() }
        }
    }

    @Test
    fun confirmedCreationStaysConfirmedWhenIntentCleanupFails() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            fixture.recovery.failClears = true
            assertIs<CatalogResult.Ready<*>>(fixture.repository.create("plan", draft, firstKey))
            assertNotNull(fixture.recovery.record)
            assertEquals(
                CatalogResult.HttpError(409),
                fixture.repository.create("plan", draft.copy(name = "Another intent"), nextKey),
            )
            assertEquals(1, fixture.writes)
            assertIs<CatalogResult.Ready<*>>(fixture.repository.reviewPendingMutation())
            assertEquals(CatalogResult.InvalidResponse, fixture.repository.startNewAction())
            fixture.recovery.failClears = false
            assertEquals(CatalogResult.Ready(Unit), fixture.repository.startNewAction())
        } finally {
            fixture.client.close()
        }
    }

    @Test
    fun storageFailureStopsTheMutationAndAccountChangesDoNotReuseAnotherOwnersKey() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            fixture.recovery.failWrites = true
            assertEquals(
                CatalogResult.InvalidResponse,
                fixture.repository.create("plan", draft, firstKey),
            )
            assertEquals(0, fixture.writes)
            fixture.recovery.failWrites = false
            fixture.networkFailure = true
            fixture.repository.create("plan", draft, firstKey)
            fixture.networkFailure = false
            fixture.owner = "successor-profile"
            fixture.login()
            assertNull(
                assertIs<CatalogResult.Ready<TripDraft?>>(
                        fixture.newRepository().pendingDraft("plan", true)
                    )
                    .value
            )
            assertIs<CatalogResult.Ready<*>>(fixture.newRepository().create("plan", draft, nextKey))
            assertEquals(listOf(firstKey, nextKey), fixture.keys)
        } finally {
            fixture.client.close()
        }
    }

    @Test
    fun tripsAndInvitationsUseMembershipProjectionAndExactExistingMutations() = runTest {
        val fixture = TripFixture()
        try {
            assertEquals(CatalogResult.HttpError(401), fixture.repository.mine())
            fixture.login()
            assertEquals(
                TripCallerRole.ORGANIZER,
                assertIs<CatalogResult.Ready<List<TravelerTrip>>>(fixture.repository.mine())
                    .value
                    .single()
                    .role,
            )
            assertEquals(
                "Trip",
                assertIs<CatalogResult.Ready<TravelerTrip>>(fixture.repository.detail("trip"))
                    .value
                    .name,
            )
            assertEquals(
                "member",
                assertIs<CatalogResult.Ready<List<TripMember>>>(fixture.repository.members("trip"))
                    .value
                    .single()
                    .profileId,
            )
            assertEquals(
                "Museum",
                assertIs<CatalogResult.Ready<TripWorkingPlan>>(
                        fixture.repository.workingPlan("trip")
                    )
                    .value
                    .stops
                    .single()
                    .label,
            )
            val draft = TripDraft("Trip", "2026-10-20", "2026-10-21", "Asia/Jakarta")
            assertIs<CatalogResult.Ready<*>>(
                fixture.repository.create("plan", draft, "00000000-0000-4000-8000-000000000001")
            )
            assertEquals(
                "trip-readiness-v1",
                Json.parseToJsonElement(fixture.lastBody!!)
                    .jsonObject["readiness_policy_version"]!!
                    .jsonPrimitive
                    .content,
            )
            assertNotNull(fixture.recovery.record)
            assertEquals(CatalogResult.Ready(Unit), fixture.repository.completeTripCreation("trip"))
            assertNull(fixture.recovery.record)
            assertIs<CatalogResult.Ready<*>>(
                fixture.repository.update("trip", draft, "00000000-0000-4000-8000-000000000001")
            )
            assertFalse(
                Json.parseToJsonElement(fixture.lastBody!!)
                    .jsonObject
                    .containsKey("readiness_policy_version")
            )
            assertIs<CatalogResult.Ready<*>>(fixture.repository.invitations("trip"))
            assertEquals(
                "Plan",
                assertIs<CatalogResult.Ready<List<TripInvitation>>>(
                        fixture.repository.myInvitations()
                    )
                    .value
                    .single()
                    .itinerary
                    ?.title,
            )
            assertEquals(
                CatalogResult.Ready(Unit),
                fixture.repository.invite(
                    "trip",
                    "@traveler",
                    "00000000-0000-4000-8000-000000000001",
                ),
            )
            assertEquals("""{"target_type":"profile","username":"traveler"}""", fixture.lastBody)
            assertEquals(
                CatalogResult.Ready(Unit),
                fixture.repository.invite(
                    "trip",
                    "fixture@example.invalid",
                    "00000000-0000-4000-8000-000000000001",
                ),
            )
            assertEquals(
                """{"target_type":"email","email":"fixture@example.invalid"}""",
                fixture.lastBody,
            )
            assertEquals(
                CatalogResult.Ready(Unit),
                fixture.repository.resend(
                    "trip",
                    "invitation",
                    "00000000-0000-4000-8000-000000000001",
                ),
            )
            assertEquals(
                CatalogResult.Ready(Unit),
                fixture.repository.revoke(
                    "trip",
                    "invitation",
                    "00000000-0000-4000-8000-000000000001",
                ),
            )
            assertEquals(
                CatalogResult.Ready(Unit),
                fixture.repository.removeMember(
                    "trip",
                    "member",
                    "00000000-0000-4000-8000-000000000001",
                ),
            )
            assertEquals(
                CatalogResult.Ready(Unit),
                fixture.repository.transferOrganizer(
                    "trip",
                    "member",
                    "00000000-0000-4000-8000-000000000001",
                ),
            )
            assertEquals("""{"new_organizer_profile_id":"member"}""", fixture.lastBody)
            TripLifecycleAction.entries.forEach {
                assertEquals(
                    CatalogResult.Ready(Unit),
                    fixture.repository.lifecycle("trip", it, "00000000-0000-4000-8000-000000000001"),
                )
            }
            InvitationDecision.entries.forEach {
                assertEquals(
                    CatalogResult.Ready(Unit),
                    fixture.repository.decide(
                        "invitation",
                        it,
                        "00000000-0000-4000-8000-000000000001",
                    ),
                )
            }
            assertEquals(
                CatalogResult.Ready(Unit),
                fixture.repository.block("invitation", "00000000-0000-4000-8000-000000000001"),
            )
            assertEquals(
                CatalogResult.Ready(Unit),
                fixture.repository.report(
                    "invitation",
                    InvitationReportReason.UNWANTED,
                    "Context",
                    "00000000-0000-4000-8000-000000000001",
                ),
            )
            assertEquals(
                "unwanted_invitation",
                Json.parseToJsonElement(fixture.lastBody!!)
                    .jsonObject["reason_code"]!!
                    .jsonPrimitive
                    .content,
            )
            assertTrue(fixture.paths.contains("/api/apps/v1/trip-invitations/invitation/decline"))
        } finally {
            fixture.client.close()
        }
    }

    @Test
    fun invalidReadModelsAndUnavailableWritesFailWithoutRetriesOrSessionLoss() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            val draft = TripDraft("Trip", "2026-10-20", "2026-10-21", "Asia/Jakarta")
            val before = fixture.writes
            assertEquals(
                CatalogResult.HttpError(422),
                fixture.repository.create(
                    "plan",
                    draft.copy(name = ""),
                    "00000000-0000-4000-8000-000000000001",
                ),
            )
            assertEquals(
                CatalogResult.HttpError(422),
                fixture.repository.update(
                    "trip",
                    draft.copy(endsOn = "2026-10-19"),
                    "00000000-0000-4000-8000-000000000001",
                ),
            )
            assertEquals(
                CatalogResult.HttpError(422),
                fixture.repository.invite("trip", " ", "00000000-0000-4000-8000-000000000001"),
            )
            assertEquals(
                CatalogResult.HttpError(422),
                fixture.repository.report(
                    "invitation",
                    InvitationReportReason.OTHER,
                    "x".repeat(1001),
                    "00000000-0000-4000-8000-000000000001",
                ),
            )
            assertEquals(CatalogResult.HttpError(422), fixture.repository.create("plan", draft, ""))
            assertEquals(
                CatalogResult.HttpError(422),
                fixture.repository.create(
                    "plan",
                    draft.copy(startsOn = "2026-02-30"),
                    "00000000-0000-4000-8000-000000000001",
                ),
            )
            assertEquals(
                CatalogResult.HttpError(422),
                fixture.repository.create(
                    "plan",
                    draft.copy(startsOn = "0000-01-01"),
                    "00000000-0000-4000-8000-000000000001",
                ),
            )
            assertEquals(before, fixture.writes)
            fixture.status = HttpStatusCode.ServiceUnavailable
            assertEquals(
                CatalogResult.Maintenance(),
                fixture.repository.invite(
                    "trip",
                    "@traveler",
                    "00000000-0000-4000-8000-000000000001",
                ),
            )
            assertEquals(before + 1, fixture.writes)
            assertTrue(fixture.session.view.value.signedIn)
            fixture.status = HttpStatusCode.Forbidden
            assertEquals(
                CatalogResult.HttpError(409),
                fixture.repository.transferOrganizer(
                    "trip",
                    "member",
                    "00000000-0000-4000-8000-000000000001",
                ),
            )
            fixture.status = HttpStatusCode.OK
            assertIs<CatalogResult.Ready<*>>(fixture.repository.reviewPendingMutation())
            assertEquals(CatalogResult.Ready(Unit), fixture.repository.startNewAction())
            fixture.status = HttpStatusCode.Forbidden
            assertEquals(
                CatalogResult.HttpError(403),
                fixture.repository.transferOrganizer(
                    "trip",
                    "member",
                    "00000000-0000-4000-8000-000000000001",
                ),
            )
            fixture.status = HttpStatusCode.OK
            fixture.overrideBody = """{"meta":{"code":"OK"},"error":null,"data":[]}"""
            assertEquals(CatalogResult.InvalidResponse, fixture.repository.detail("trip"))
            fixture.overrideBody = """{"meta":{"code":"OK"},"error":null,"data":{"id":"trip"}}"""
            assertEquals(CatalogResult.InvalidResponse, fixture.repository.detail("trip"))
            fixture.overrideBody = null
            fixture.networkFailure = true
            assertEquals(CatalogResult.NetworkError, fixture.repository.myInvitations())
            assertTrue(fixture.session.view.value.signedIn)
        } finally {
            fixture.client.close()
        }
    }
}

internal class TripFixture {
    var identityReads = 0
    var time = 1_000L
    var owner = "trip-fixture-owner"
    val recovery = TripTestRecoveryStore()
    val creationRoutes = TripTestRecoveryStore()
    val keys = mutableListOf<String>()
    var commitCreations = false
    val committedCreationKeys = mutableSetOf<String>()
    var status = HttpStatusCode.OK
    var overrideBody: String? = null
    var mutationFailure: Pair<HttpStatusCode, String>? = null
    var networkFailure = false
    var lastBody: String? = null
    var writes = 0
    val paths = mutableListOf<String>()
    val queryValues = mutableListOf<Map<String, String>>()
    val bannerPayloads = mutableListOf<Pair<String, List<Byte>>>()
    val trip =
        """{"id":"trip","locations":[],"working_itinerary_id":"plan","name":"Trip","starts_on":"2026-10-20","ends_on":"2026-10-21","timezone":"Asia/Jakarta","status":"active","caller_role":"organizer","participant_count":1,"visibility":"private","registration_opens_at":null,"registration_closes_at":null,"participant_limit":5,"remaining_participants":4,"can_invite":true,"invite_unavailable_reason":null,"can_edit_registration":false}"""
    private val invitation =
        """{"id":"invitation","trip_id":"trip","target_label":"traveler","status":"pending","expires_at":"2099-01-01T00:00:00Z","trip_name":"Trip","inviter_username":"organizer","trip_starts_on":"2026-10-20","trip_ends_on":"2026-10-21","itinerary_summary":{"id":"plan","locations":[],"title":"Plan","day_count":1,"stop_count":2}}"""
    val client =
        HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val data =
                    when {
                        path.endsWith("/profile-identities") -> {
                            identityReads++
                            request.url.parameters["usernames"]!!.split(',').joinToString(prefix = "{", postfix = "}") {
                                """"$it":{"level":2,"avatar_url":"https://fixture.invalid/media/$it.webp"}"""
                            }
                        }
                        path.endsWith("/login") ->
                            """{"tokens":{"token_type":"Bearer","access_token":"fixture","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"protected","refresh_expires_at":"2099-02-01T00:00:00Z"}}"""
                        path.endsWith("/progress") ->
                            """{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
                        else -> {
                            assertEquals(
                                "Bearer fixture",
                                request.headers[HttpHeaders.Authorization],
                            )
                            assertNull(request.headers[HttpHeaders.Cookie])
                            paths.add(path)
                            queryValues.add(request.url.parameters.entries().associate { (key, values) -> key to values.single() })
                            if (request.method != HttpMethod.Get) {
                                writes++
                                keys.add(
                                    request.headers["Idempotency-Key"]
                                        ?: error("Missing operation key")
                                )
                                if (
                                    commitCreations &&
                                        request.method == HttpMethod.Post &&
                                        path.endsWith("/trips")
                                )
                                    committedCreationKeys.add(keys.last())
                            }
                            lastBody = (request.body as? TextContent)?.text
                            if (request.method == HttpMethod.Post && path.endsWith("/banner")) {
                                bannerPayloads +=
                                    request.body.contentType.toString() to
                                        request.body.toByteArray().toList()
                            }
                            if (path == "/api/apps/v1/me")
                                return@MockEngine respond(
                                    """{"meta":{"code":"ME"},"error":null,"data":{"profile":{"id":"$owner"}}}""",
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            if (networkFailure) error("Fixture network failure")
                            if (path.startsWith("/api/apps/v1/me/trip-creation-operations/")) {
                                val committed = path.substringAfterLast('/') in committedCreationKeys
                                return@MockEngine respond(
                                    if (committed) """{"meta":{"code":"TRIP"},"error":null,"data":$trip}"""
                                    else """{"meta":{"request_id":"$noticeId","timestamp":1,"code":"NOT_FOUND"},"error":{"message":"Synthetic absent operation","details":null},"data":null}""",
                                    if (committed) HttpStatusCode.OK else HttpStatusCode.NotFound,
                                    headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            }
                            val response =
                                when {
                                    path == "/api/apps/v1/me" ->
                                        """{"profile":{"id":"trip-fixture-owner"}}"""
                                    request.method == HttpMethod.Get && path.endsWith("/members") ->
                                        """[{"profile_id":"member","username":"traveler","display_name":"Traveler","role":"participant","status":"active"}]"""
                                    request.method == HttpMethod.Get &&
                                        path.endsWith("/itinerary") ->
                                        """{"locations":[],"title":"Plan","stops":[{"id":"stop","spot_id":"spot","spot_name":"Museum","day":1,"position":1}]}"""
                                    request.method == HttpMethod.Get && path.endsWith("/trips") ->
                                        "[$trip]"
                                    request.method == HttpMethod.Get &&
                                        (path.endsWith("/invitations") ||
                                            path.endsWith("/trip-invitations")) -> "[$invitation]"
                                    path.endsWith("/banner") ->
                                        """{"media_id":"banner","processing_status":"processing","retryable":false}"""
                                    path.endsWith("/trips") || path.endsWith("/trip") -> trip
                                    else -> "{}"
                                }
                            val actualStatus =
                                if (
                                    status == HttpStatusCode.OK &&
                                        (request.method == HttpMethod.Delete ||
                                            path.endsWith("/leave") ||
                                            path.endsWith("/block-inviter"))
                                )
                                    HttpStatusCode.NoContent
                                else status
                            val failure = mutationFailure?.takeIf { request.method != HttpMethod.Get }
                            return@MockEngine respond(
                                failure?.second ?: overrideBody
                                    ?: """{"meta":{"code":"OK"},"error":null,"data":$response}""",
                                failure?.first ?: actualStatus,
                                headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }
                respond(
                    """{"meta":{"code":"OK"},"error":null,"data":$data}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        )
    private val api = NativeApi(client, "https://fixture.invalid", AppPlatform.ANDROID)
    val session =
        TravelerSession(
            api,
            object : RefreshCredentialStore {
                var credential: String? = null

                override fun read() = credential

                override fun write(credential: String) {
                    this.credential = credential
                }

                override fun clear() {
                    credential = null
                }
            },
            "en",
        )

    fun newRepository() = NativeTripApi(api.catalogTransport(), session, recovery, nowMillis = { time }, creationRoutes = creationRoutes)

    val repository = newRepository()

    suspend fun login() {
        session.login("fixture@example.invalid", "fixture-password")
    }
}

internal class TripTestRecoveryStore : TripRecoveryStore {
    var record: String? = null
    var failWrites = false
    var failClears = false

    override fun read() = record

    override fun write(record: String) {
        check(!failWrites)
        this.record = record
    }

    override fun clear() {
        check(!failClears)
        record = null
    }
}
