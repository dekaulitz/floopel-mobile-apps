package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.domain.repository.TripRecoveryStore

/** Isolated fallback for nonproduction consumers; Android supplies the encrypted platform store explicitly. */
internal class MemoryTripReportRecoveryStore : TripRecoveryStore {
    private var record: String? = null
    override fun read() = record
    override fun write(record: String) { this.record = record }
    override fun clear() { record = null }
}
