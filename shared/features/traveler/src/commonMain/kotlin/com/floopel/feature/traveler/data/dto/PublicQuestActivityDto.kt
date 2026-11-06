package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Public feed coverage belongs to the admitted revision; no scalar geography fallback is accepted. */
@Serializable
internal data class PublicQuestActivityDto(
    val username: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("quest_id") val questId: String,
    @SerialName("quest_slug") val questSlug: String,
    @SerialName("quest_title") val questTitle: String,
    val locations: List<LocationReferenceDto>,
    @SerialName("started_at") val startedAt: String,
)
