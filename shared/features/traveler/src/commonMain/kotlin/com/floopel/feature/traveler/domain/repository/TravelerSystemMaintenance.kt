package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.SystemMaintenanceNotice
import kotlinx.coroutines.flow.StateFlow

/** Global availability with a single explicit recovery read; never replays a user mutation. */
interface TravelerSystemMaintenance {
    val maintenance: StateFlow<SystemMaintenanceNotice?>
    fun maintenanceRetrySecondsRemaining(): Long
    suspend fun recheckSystemMaintenance(): Boolean
}
