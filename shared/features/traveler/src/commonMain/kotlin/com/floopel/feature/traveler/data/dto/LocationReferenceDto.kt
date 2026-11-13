package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.Serializable

/** Required Apps location identity; an empty array is valid but missing fields are not. */
@Serializable
internal data class LocationReferenceDto(val id: String, val name: String, val slug: String, val level: String) {
    init {
        require(id.matches(Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")))
        require(level in setOf("country", "region", "city", "district", "village"))
    }
}
