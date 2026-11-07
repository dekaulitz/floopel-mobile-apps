package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.Serializable

/** Independent target intents share encrypted platform storage without overwriting another unresolved Trip report. */
@Serializable
internal data class TripReportRecoveryIndexDto(val records: Map<String, TripReportRecoveryDto>)
