package com.floopel.feature.traveler.domain.model

/** One active system-wide maintenance notice, unrelated to profile or session identity. */
data class SystemMaintenanceNotice(val revision: Long, internal val retryAtMillis: Long)
