package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Private Me/PATCH wire projection; absent avatar remains compatible with the previous server. */
@Serializable
internal data class PersonalProfileDto(
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String = "",
    val username: String,
    @SerialName("nationality_country_code") val nationalityCode: String = "",
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val id: String? = null,
)
