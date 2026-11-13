package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.*

/** Owner distribution is distinct from the mutable builder and public snapshot. */
interface TravelerItineraryDistributionRepository {
    suspend fun pendingPublication(id: String): CatalogResult<ItineraryPublicationReview?> = CatalogResult.Ready(null)
    suspend fun read(id: String): CatalogResult<ItineraryDistribution>
    suspend fun publish(id: String, review: ItineraryPublicationReview, key: String): CatalogResult<ItineraryDistribution>
    suspend fun unpublish(id: String, key: String): CatalogResult<ItineraryDistribution>
    suspend fun clonePolicy(id: String, allow: Boolean, key: String): CatalogResult<ItineraryDistribution>
    suspend fun shareLink(id: String): CatalogResult<ItineraryShareLink>
    suspend fun rotateShareLink(id: String, key: String): CatalogResult<ItineraryShareLink>
    suspend fun revokeShareLink(id: String, key: String): CatalogResult<Unit>
    suspend fun cover(id: String): CatalogResult<ItineraryCoverStatus>
    suspend fun uploadCover(id: String, bytes: ByteArray, mimeType: String, key: String): CatalogResult<ItineraryCoverStatus>
}
