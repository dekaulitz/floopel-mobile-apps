package com.floopel.feature.traveler.domain.model

/** Server-ordered entity coverage; independent from GPS, proof and a selected discovery filter. */
data class LocationReference(val id: String, val name: String, val slug: String, val level: String)
