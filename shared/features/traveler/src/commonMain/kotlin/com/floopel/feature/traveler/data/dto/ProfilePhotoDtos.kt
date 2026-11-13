package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonElement

/** The protocol exposes no staging key, provider URL, expiry or Media asset identity. */
@Serializable
internal data class ProfilePhotoUploadDto(val id: String, val status: ProfilePhotoStatusDto)

/** Wire values are explicit and independent from Domain enum names. */
@Serializable
internal enum class ProfilePhotoStatusDto {
    @SerialName("processing") PROCESSING,
    @SerialName("ready") READY,
    @SerialName("failed") FAILED,
}

/** Success must carry all three canonical envelope members. */
@Serializable
internal data class ProfilePhotoEnvelopeDto(
    val meta: AppsMetaDto,
    val error: JsonElement,
    val data: ProfilePhotoUploadDto,
)
