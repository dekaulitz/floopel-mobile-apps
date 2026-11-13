package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.domain.model.CatalogResult
import com.floopel.feature.traveler.domain.model.OpenTripSchedule
import com.floopel.feature.traveler.domain.model.TravelerTrip
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TripMemberScheduleTest {
    @Test fun scheduleUsesTheAuthorizedServerProjectionWithoutDeviceDateInference() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            for (schedule in listOf(OpenTripSchedule.UPCOMING, OpenTripSchedule.ONGOING, OpenTripSchedule.ENDED, OpenTripSchedule.UNAVAILABLE)) {
                fixture.overrideBody = envelope(fixture.trip.dropLast(1) + ",\"schedule_state\":\"${schedule.wire}\"}")
                val trip = assertIs<CatalogResult.Ready<TravelerTrip>>(fixture.repository.detail("trip")).value
                assertEquals(schedule, trip.capabilities!!.schedule)
            }
            assertEquals(0, fixture.writes)
        } finally { fixture.client.close() }
    }

    @Test fun oldPayloadAndFutureEnumsRemainUnknownWithoutInventingAnOngoingTrip() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            for (body in listOf(fixture.trip, fixture.trip.dropLast(1) + ",\"schedule_state\":\"future_schedule\"}")) {
                fixture.overrideBody = envelope(body)
                val trip = assertIs<CatalogResult.Ready<TravelerTrip>>(fixture.repository.detail("trip")).value
                assertEquals(OpenTripSchedule.UNKNOWN, trip.capabilities!!.schedule)
            }
        } finally { fixture.client.close() }
    }

    @Test fun presentScheduleMustBeAStringRatherThanNullOrACoercedPrimitive() = runTest {
        val fixture = TripFixture()
        try {
            fixture.login()
            for (wire in listOf("null", "42", "true", "{}", "[]")) {
                fixture.overrideBody = envelope(fixture.trip.dropLast(1) + ",\"schedule_state\":$wire}")
                assertEquals(CatalogResult.InvalidResponse, fixture.repository.detail("trip"))
            }
        } finally { fixture.client.close() }
    }

    private fun envelope(data: String) = """{"meta":{"code":"TRIP"},"error":null,"data":$data}"""
}
