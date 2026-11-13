package com.floopel.feature.traveler.domain.repository

/** Installation state is local and independent from accounts, API origins and legal consent. */
data class TravelerIntroProgress(val completed: Boolean = false, val step: Int = 0) {
    init { require(step in 0..3) }
}

interface TravelerStartupRepository {
    suspend fun read(): TravelerIntroProgress
    suspend fun saveStep(step: Int)
    suspend fun complete()
}
