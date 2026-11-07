package com.floopel.feature.traveler.domain.model

/** Optional settings remain unknown when the server omits them; never invent consent. */
data class TravelerSettings(
    val tripUpdates: Boolean?,
    val marketing: Boolean?,
    val product: Boolean?,
    val profileDiscoverable: Boolean?,
)
