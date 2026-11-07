package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.*

/** Restricted recovery does not grant access to Traveler content or restore old sessions. */
interface TravelerErasureRepository {
    suspend fun authenticate(email: String, password: String): CatalogResult<Unit>
    suspend fun state(): CatalogResult<AccountErasureState>
    suspend fun request(key: String): CatalogResult<AccountErasureState>
    suspend fun cancel(key: String): CatalogResult<AccountErasureState>
    fun clear()
}
