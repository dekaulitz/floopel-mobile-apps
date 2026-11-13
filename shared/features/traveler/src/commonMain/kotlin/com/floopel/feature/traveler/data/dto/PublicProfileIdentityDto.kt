package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Batch identity transport is separate from Domain and from the legacy integer level map. */
@Serializable
internal data class PublicProfileIdentityDto(val level: Int, @SerialName("avatar_url") val avatarUrl: String? = null)
