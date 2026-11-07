package com.floopel.feature.traveler.domain.model

/** Immutable reporter input; the title is local recovery context and is never submitted as evidence. */
data class TripReportIntent(val tripId: String, val title: String, val reason: ItineraryReportReason, val details: String, val key: String)
