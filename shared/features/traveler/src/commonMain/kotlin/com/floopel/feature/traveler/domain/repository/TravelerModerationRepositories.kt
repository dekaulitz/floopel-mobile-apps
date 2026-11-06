package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.*

/** Reporter/owner operations only; internal moderator notes are never projected to native UI. */
interface TravelerModerationRepository {
    suspend fun catalogReport(target: CatalogReportTarget, id: String, reason: CatalogReportReason, details: String, key: String): CatalogResult<ReportReceipt>
    suspend fun itineraryPreview(id: String): CatalogResult<ItineraryReportPreview>
    suspend fun itineraryReport(id: String, reason: ItineraryReportReason, details: String, key: String): CatalogResult<ReportReceipt>
    /** Trip uses the same safety reason vocabulary with its own server-built evidence snapshot. */
    suspend fun tripReport(id: String, reason: ItineraryReportReason, details: String, key: String): CatalogResult<ReportReceipt> = CatalogResult.InvalidResponse
    /** A durable Trip report freezes input before I/O; reads and re-entry never submit it implicitly. */
    suspend fun submitTripReport(intent: TripReportIntent): CatalogResult<ReportReceipt> = tripReport(intent.tripId, intent.reason, intent.details, intent.key)
    /** Returns only this target's actor-bound input; unrelated unresolved reports remain independent. */
    suspend fun pendingTripReport(tripId: String): CatalogResult<TripReportIntent?> = CatalogResult.Ready(null)
    /** Reads the original operation receipt without creating a new report. */
    suspend fun checkTripReport(intent: TripReportIntent): CatalogResult<ReportReceipt> = CatalogResult.InvalidResponse
    /** Releases only a matching confirmed local intent after the caller acknowledges the outcome. */
    suspend fun completeTripReport(intent: TripReportIntent): CatalogResult<Unit> = CatalogResult.Ready(Unit)
    suspend fun actions(): CatalogResult<List<OwnerModerationAction>>
    suspend fun appeal(actionId: String): CatalogResult<TravelerAppeal>
    suspend fun submitAppeal(actionId: String, reason: String, key: String): CatalogResult<TravelerAppeal>
}
