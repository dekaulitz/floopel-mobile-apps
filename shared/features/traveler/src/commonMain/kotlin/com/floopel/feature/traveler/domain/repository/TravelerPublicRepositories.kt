package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.*

/** Immutable public projections remain separate from the owner's working itinerary. */
interface TravelerPublicRepository {
    suspend fun questActivities(point: GeoPoint?, locale: String, page: Int, perPage: Int = 20, radiusMeters: Int = TravelerExploreRadius.DEFAULT_METERS): CatalogResult<PublicAchievementPage<PublicQuestActivity>> = CatalogResult.InvalidResponse
    suspend fun progress(username: String): CatalogResult<PublicProfileProgress>
    suspend fun profile(username: String): CatalogResult<TravelerPublicProfile>
    suspend fun profileItineraries(username: String): CatalogResult<List<PublicItinerarySummary>>
    suspend fun completedQuests(username: String, locale: String, page: Int): CatalogResult<PublicAchievementPage<PublicCompletedQuest>>
    suspend fun badges(username: String, locale: String, page: Int): CatalogResult<PublicAchievementPage<PublicProfileBadge>>
    suspend fun itineraries(page: Int): CatalogResult<List<PublicItinerarySummary>>
    /** Searches the complete public publication catalog before pagination. */
    suspend fun searchItineraries(query: String, locationId: String?, page: Int): CatalogResult<List<PublicItinerarySummary>> =
        if (query.isBlank() && locationId == null) itineraries(page) else CatalogResult.InvalidResponse
    suspend fun itinerary(slug: String): CatalogResult<PublicItinerarySnapshot>
}
