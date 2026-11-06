package com.floopel.feature.traveler.presentation

import kotlin.test.*

class NotificationRouteTest {
    private val source = "00000000-0000-4000-8000-000000000051"
    @Test fun lifecycleNoticeUsesItsSourceId() {
        assertEquals(TravelerRoute(Destination.NOTICE, source), notificationRoute("/me/trip-notices/$source"))
        assertEquals(TravelerRoute(Destination.APPLICATION, source), notificationRoute("/trip-applications/$source"))
        assertEquals(TravelerRoute(Destination.TRIP, source), notificationRoute("/trips/$source"))
    }
    @Test fun unknownExternalAndEncodedTargetsRemainUnavailable() {
        listOf(null, "https://example.invalid/trips/$source", "//example.invalid/trips/$source", "/trips/$source?admin=true",
            "/me/trip-notices/$source#roster", "/trips/%2e%2e/$source", "/trips/not-a-uuid", "/me/trip-notices/$source/members").forEach {
            assertNull(notificationRoute(it), it)
        }
    }
}
