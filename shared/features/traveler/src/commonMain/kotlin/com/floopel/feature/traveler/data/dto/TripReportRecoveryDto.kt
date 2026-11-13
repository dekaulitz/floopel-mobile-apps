package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.Serializable

/** Encrypted, actor-bound recovery record keeps the exact JSON body separately from local presentation context. */
@Serializable
internal data class TripReportRecoveryDto(val profileId: String, val title: String, val body: String, val key: String, val confirmedReportId: String? = null)
