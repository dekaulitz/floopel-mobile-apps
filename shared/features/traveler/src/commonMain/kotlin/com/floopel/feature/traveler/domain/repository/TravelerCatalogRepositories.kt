package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.*

/** Consumer-owned boundary: Android presentation must not import Ktor. */
interface TravelerCatalog {
    /** Personalized discovery; implementations without an identity owner must fail closed. */
    suspend fun untakenLocationQuests(locationId: String, locale: String, page: Int): CatalogResult<LocationQuestPage> = CatalogResult.InvalidResponse
    /** Country picker reads the geographic catalog, independently of the account nationality list. */
    suspend fun countryPage(locale: String, page: Int): CatalogResult<CatalogPage> = CatalogResult.InvalidResponse
    /** Ranked Spot search applies the optional region before paginating. */
    suspend fun searchSpotPage(query: String, locationId: String?, locale: String, page: Int): CatalogResult<CatalogPage> = CatalogResult.InvalidResponse
    suspend fun curatedItineraries(query: String, locationId: String?, locale: String, page: Int): CatalogResult<CatalogPage> = CatalogResult.InvalidResponse
    /** Server proximity inclusion/count precede pagination at the owner-selected Explore radius. */
    suspend fun nearbySpots(point: GeoPoint, locale: String, page: Int, radiusMeters: Int = TravelerExploreRadius.DEFAULT_METERS): CatalogResult<CatalogPage> = CatalogResult.InvalidResponse
    /** Nearest catalog centroid is a discovery context, never administrative containment. */
    suspend fun nearestLocation(point: GeoPoint): CatalogResult<CatalogEntry?> = CatalogResult.InvalidResponse
    /** Region picker uses the server kind facet before ranking/limiting search hits. */
    suspend fun searchLocations(query: String, locale: String): CatalogResult<List<CatalogEntry>> =
        when (val result = search(query, locale)) {
            is CatalogResult.Ready -> CatalogResult.Ready(result.value.filter { it.kind == CatalogKind.LOCATION })
            else -> result
        }
    /** Server-ranked discovery; proximity filtering happens before pagination. */
    suspend fun discoverySpots(locale: String, center: GeoPoint? = null, locationId: String? = null): CatalogResult<List<CatalogEntry>> =
        if (center == null) spots(locale, locationId, 15) else CatalogResult.InvalidResponse
    suspend fun mapMarkers(bbox: String): CatalogResult<CatalogMapPage>
    suspend fun activeQuests(locale: String, page: Int): CatalogResult<LocationQuestPage> = CatalogResult.InvalidResponse
    /** Discovery is scoped to a checkpoint Spot before pagination. */
    suspend fun spotArticles(spotId: String, locale: String, page: Int): CatalogResult<CatalogPage> = CatalogResult.InvalidResponse
    suspend fun spotQuests(spotId: String, locale: String, page: Int): CatalogResult<LocationQuestPage> = CatalogResult.InvalidResponse
    suspend fun locationQuests(locationId: String, locale: String, page: Int): CatalogResult<LocationQuestPage>
    suspend fun browse(kind: CatalogKind, locale: String, page: Int, storyType: StoryType? = null, locationId: String? = null, parentId: String? = null): CatalogResult<CatalogPage>
    suspend fun regions(locale: String): CatalogResult<List<CatalogEntry>>
    /** Global approved locations ranked by available AI rating before pagination. */
    suspend fun topLocations(locale: String, perPage: Int = TravelerDiscoveryLimits.LOCATIONS): CatalogResult<List<CatalogEntry>> = CatalogResult.InvalidResponse
    suspend fun spots(locale: String, locationId: String? = null, perPage: Int = 20): CatalogResult<List<CatalogEntry>>
    suspend fun collections(locale: String, perPage: Int = 48): CatalogResult<List<CatalogEntry>>
    suspend fun stories(locale: String, type: StoryType? = null, perPage: Int = 12): CatalogResult<List<CatalogEntry>>
    suspend fun curatedItineraries(locale: String): CatalogResult<List<CatalogEntry>>
    suspend fun search(
        query: String,
        locale: String,
        sort: SearchSort = SearchSort.RELEVANCE,
        category: String? = null,
    ): CatalogResult<List<CatalogEntry>>
    suspend fun detail(item: CatalogEntry, locale: String): CatalogResult<CatalogEntry>
    suspend fun childRegions(parentId: String, locale: String): CatalogResult<List<CatalogEntry>>
}

interface TravelerRecommendationRepository {
    suspend fun load(locale: String): CatalogResult<TravelerRecommendation>
}
