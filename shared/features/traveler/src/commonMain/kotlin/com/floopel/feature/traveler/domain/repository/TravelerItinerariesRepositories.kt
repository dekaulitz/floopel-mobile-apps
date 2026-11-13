package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.*

/** Platform storage encrypts this opaque recovery record; it is not an offline write queue. */
interface ItineraryRecoveryStore {
    fun read(): String?
    fun write(record: String)
    fun clear()
}

interface TravelerItineraryRepository {
    companion object {
        const val PENDING_MUTATION_CODE = "PENDING_OPERATION"
        const val EXPIRED_MUTATION_CODE = "EXPIRED_OPERATION"
    }
    suspend fun mine(): CatalogResult<List<OwnedItinerary>>
    suspend fun detail(id: String): CatalogResult<OwnedItineraryDetail>
    suspend fun create(draft: ItineraryDraft): ItineraryWriteResult<OwnedItineraryDetail>
    suspend fun pendingDraft(): CatalogResult<ItineraryDraft?>
    suspend fun pendingStop(): CatalogResult<PendingItineraryStop?>
    suspend fun clone(id: String): ItineraryWriteResult<OwnedItineraryDetail>
    suspend fun addStop(id: String, spotId: String, day: Int): ItineraryWriteResult<OwnedItineraryDetail>
    /** Authorized read review; never releases or replays the saved POST. */
    suspend fun reviewPendingMutation(): CatalogResult<List<OwnedItinerary>> = mine()
    /** Explicit release requires the same actor generation and exact record reviewed successfully. */
    suspend fun startNewAction(): CatalogResult<Unit>
    suspend fun update(id: String, draft: ItineraryDraft): CatalogResult<OwnedItineraryDetail>
    suspend fun delete(id: String): CatalogResult<Unit>
    suspend fun updateStop(id: String, stop: OwnedStop): CatalogResult<OwnedItineraryDetail>
    suspend fun removeStop(id: String, stopId: String): CatalogResult<Unit>
    suspend fun reorder(id: String, stops: List<OwnedStop>): CatalogResult<OwnedItineraryDetail>
    suspend fun titleDay(id: String, day: Int, title: String): CatalogResult<OwnedItineraryDetail>
    suspend fun duplicateDay(id: String, day: Int): CatalogResult<OwnedItineraryDetail>
}
