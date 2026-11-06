package com.floopel.feature.traveler.domain.model

data class TravelerPublicProfile(val username: String, val displayName: String?, val avatarUrl: String?, val bio: String?, val joinedYear: Int?,
    val itineraries: Int?, val regions: Int?, val stories: Int?, val savedSpots: Int?)

data class PublicCompletedQuest(val questId: String, val slug: String, val title: String, val completedAt: String)
data class PublicProfileBadge(val id: String, val code: String, val name: String, val image: CatalogPhoto)
data class PublicAchievementPage<T>(val items: List<T>, val page: Int, val total: Int, val totalPages: Int)
/** Publication coverage remains snapshot-owned; absent identity level never implies level one. */
data class PublicItinerarySummary(val id: String, val slug: String, val title: String, val description: String, val coverUrl: String?,
    val publicationId: String, val version: Int, val stopCount: Int, val ownerUsername: String, val commentCount: Long? = null, val previewUrls: List<String> = emptyList(), val coverPhoto: CatalogPhoto? = null, val previewPhotos: List<CatalogPhoto> = emptyList(), val ownerLevel: Int? = null,
    val ownerAvatarUrl: String? = null, val locations: List<LocationReference> = emptyList(),
    val ownerDisplayName: String? = null, val dayCount: Int? = null, val publishedAt: kotlin.time.Instant? = null,
    val allowClone: Boolean? = null)
data class PublicItineraryStop(val id: String, val spot: CatalogEntry, val day: Int, val position: Int, val note: String?, val tips: String?, val durationMinutes: Int?, val arriveTime: String?, val travelNote: String?)
/** Public snapshot retains full coverage independently from private working itinerary changes. */
data class PublicItinerarySnapshot(val id: String, val slug: String, val title: String, val description: String, val coverUrl: String?,
    val allowClone: Boolean, val publicationId: String, val version: Int, val ownerUsername: String, val ownerDisplayName: String, val stops: List<PublicItineraryStop>, val commentCount: Long? = null, val coverPhoto: CatalogPhoto? = null, val ownerLevel: Int? = null,
    val ownerAvatarUrl: String? = null, val locations: List<LocationReference> = emptyList())



/** Public aggregate, without owner progress or evidence. */
data class PublicProfileProgress(val level: Int, val levelName: String, val totalXP: Long,
    val xpFloor: Long? = null, val xpToNextLevel: Long? = null)
