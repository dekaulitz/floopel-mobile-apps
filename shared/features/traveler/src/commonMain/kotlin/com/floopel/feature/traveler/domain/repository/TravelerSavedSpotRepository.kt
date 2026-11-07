package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.*

interface TravelerSavedSpotRepository {
    suspend fun savedSpots(page: Int = 1): CatalogResult<SavedSpotPage>
    suspend fun isSaved(spotId: String): CatalogResult<Boolean>
    suspend fun setSaved(spotId: String, saved: Boolean): CatalogResult<Boolean>
}
