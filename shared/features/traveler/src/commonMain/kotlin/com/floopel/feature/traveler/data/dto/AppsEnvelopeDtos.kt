package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/** Canonical Apps metadata projected only within the Data layer. */
@Serializable
internal data class AppsMetaDto(
    @SerialName("request_id") val requestId: String,
    val timestamp: Long,
    val code: String,
)

/** Error detail arrays and null are both valid runtime forms; messages are not business codes. */
@Serializable
internal data class AppsErrorBodyDto(val message: String, val details: JsonArray? = null)

/** Canonical failure data is required-nullable and validated before exposing meta.code. */
@Serializable
internal data class AppsFailureEnvelopeDto(
    val meta: AppsMetaDto,
    val error: AppsErrorBodyDto,
    val data: JsonElement,
)
