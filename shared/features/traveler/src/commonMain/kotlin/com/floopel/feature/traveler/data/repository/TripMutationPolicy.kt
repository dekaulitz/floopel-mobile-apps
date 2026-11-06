package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.RestMethod

/** Database-ledger and immutable-application operations outlive the legacy Redis replay window. */
internal fun hasDurableTripReplay(method: RestMethod, path: String): Boolean {
    if (method != RestMethod.POST) return false
    val parts = path.trim('/').split('/')
    if (parts.size != 3) return false
    return parts[0] == "itineraries" && parts[2] == "trips" ||
        parts[0] == "open-trips" && parts[2] == "applications" ||
        parts[0] == "trip-applications" && parts[2] in setOf("approve", "reject", "withdraw")
}
