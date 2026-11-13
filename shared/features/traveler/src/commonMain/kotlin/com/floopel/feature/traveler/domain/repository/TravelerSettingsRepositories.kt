package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.*

/** Account-screen boundary independent of HTTP and platform UI. */
interface TravelerSettingsRepository {
    suspend fun load(): CatalogResult<TravelerSettings>
    suspend fun save(settings: TravelerSettings): CatalogResult<TravelerSettings>
}
