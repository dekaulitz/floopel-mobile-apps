package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The common public-create/owned-recovery subset excludes internal notes, snapshots and reporter resolution text. */
@Serializable
internal data class TripReportReceiptDto(val id: String, @SerialName("target_type") val targetType: String,
    @SerialName("target_id") val targetId: String, @SerialName("reason_code") val reasonCode: String, val status: String)
