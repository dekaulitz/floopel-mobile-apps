package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.data.dto.LocationReferenceDto
import com.floopel.feature.traveler.domain.model.LocationReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Copies each authoritative identity in its original order without truncation or enrichment. */
internal fun LocationReferenceDto.toDomain() = LocationReference(id, name, slug, level)

/** Requires the canonical array, including on entities whose coverage is empty. */
internal fun JsonObject.readLocations(): List<LocationReference> =
    Json.decodeFromJsonElement<List<LocationReferenceDto>>(getValue("locations")).map { it.toDomain() }
