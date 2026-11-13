package com.floopel.feature.traveler.domain.model

/** Public ongoing activity with all locations from its bound Quest revision, never traveler GPS. */
data class PublicQuestActivity(val username: String, val displayName: String, val questId: String, val questSlug: String,
    val questTitle: String, val locations: List<LocationReference>, val startedAt: String, val level: Int? = null,
    val avatarUrl: String? = null)
