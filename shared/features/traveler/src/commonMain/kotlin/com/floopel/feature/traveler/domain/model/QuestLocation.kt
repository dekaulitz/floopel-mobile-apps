package com.floopel.feature.traveler.domain.model

/** A fresh foreground fix. Coordinates are never persisted or logged by the client. */
class QuestLocation(val latitude: Double, val longitude: Double, val accuracyMeters: Double, val capturedAt: String) {
    override fun toString() = "QuestLocation([REDACTED])"
}

class QuestLocationUnavailable : Exception("Foreground location unavailable")
