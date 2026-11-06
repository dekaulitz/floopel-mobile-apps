package com.floopel.feature.traveler.domain.model

/** Canonical content type facet defined in Apps OpenAPI. */
enum class StoryType(val wireValue: String) {
    ARTICLE("article"),
    NEWS("news"),
    EDITORIAL("editorial"),
}

/** Existing public Web Traveler families, not a new backend taxonomy. */
enum class CatalogKind { LOCATION, SPOT, COLLECTION, STORY, ITINERARY }

/** Global ranked previews; Spot archive pages use the same approved limit. */
object TravelerDiscoveryLimits {
    const val SPOTS = 10
    const val LOCATIONS = 5
}

/** Owner-selected Explore radius; unrelated Home discovery retains its existing policy. */
object TravelerExploreRadius {
    const val DEFAULT_METERS = 15_000
    val OPTIONS_METERS: List<Int> = listOf(5_000, DEFAULT_METERS, 20_000)
}

/** Provisional AI reference, never a human-review rating. Suppressed values are not displayed. */
data class AiReferenceRating(val score: Double?, val suppressed: Boolean)

/** Public-only projection; wire transport and serialization stay in the data adapter. */
data class GeoPoint(val latitude: Double, val longitude: Double)

data class CatalogPhotoVariant(val url: String, val width: Int, val height: Int)
data class CatalogPhoto(val url: String, val alt: String, val variants: List<CatalogPhotoVariant> = emptyList())

/** Select against physical slot pixels; invalid/missing metadata never creates guessed URLs. */
fun CatalogPhoto.adaptiveUrl(widthPx: Int, heightPx: Int, crop: Boolean): String {
    val valid = variants.filter { it.width > 0 && it.height > 0 }.sortedBy { it.width.toLong() * it.height }
    if (valid.isEmpty() || widthPx <= 0) return url
    val ratio = valid.last().width.toDouble() / valid.last().height
    val targetHeight = if (heightPx > 0) heightPx.toDouble() else widthPx / ratio
    val factor = if (crop) maxOf(widthPx / ratio, targetHeight) else minOf(widthPx / ratio, targetHeight)
    return valid.firstOrNull { it.height >= factor }?.url ?: valid.last().url
}

/** Traveler identity level is optional authoritative metadata; absent never implies level one. */
data class StoryMetadata(val authorUsername: String?, val publishedAt: String?, val readingMinutes: Int?, val tags: List<String>, val authorLevel: Int? = null,
    val authorAvatarUrl: String? = null)

/** Public catalog projection retaining full entity coverage separately from Spot assignment and breadcrumbs. */
data class CatalogEntry(
    val id: String,
    val slug: String,
    val title: String,
    val subtitle: String?,
    val kind: CatalogKind,
    val coverUrl: String?,
    val category: String? = null,
    val bodyHtml: String? = null,
    val geo: GeoPoint? = null,
    val related: List<CatalogEntry> = emptyList(),
    val aiReference: AiReferenceRating? = null,
    val detailLoaded: Boolean = false,
    val story: StoryMetadata? = null,
    val photos: List<CatalogPhoto> = emptyList(),
    val visitMinutes: Int? = null,
    val tags: List<String> = emptyList(),
    val visitNotes: String? = null,
    val collections: List<CatalogEntry> = emptyList(),
    val categorySlug: String? = null,
    val locationId: String? = null,
    val timezone: String? = null,
    val locationName: String? = null,
    val commentCount: Long? = null,
    val itineraryDays: Int? = null,
    val spotCount: Int? = null,
    val previewUrls: List<String> = emptyList(),
    val previewPhotos: List<CatalogPhoto> = emptyList(),
    val locationLevel: String? = null,
    val parentLocationId: String? = null,
    val locations: List<LocationReference> = emptyList(),
) {
    val coverPhoto: CatalogPhoto? get() = coverUrl?.let { url -> photos.firstOrNull { it.url == url } ?: CatalogPhoto(url, title) }
}

data class CatalogPage(val items: List<CatalogEntry>, val page: Int, val totalPages: Int)

/** Public discovery only; Hidden Quest eligibility is never inferred from a Location. */
data class LocationQuest(val id: String, val slug: String, val title: String, val xp: Long, val badgeImage: CatalogPhoto? = null,
    val locations: List<LocationReference> = emptyList(), val activityCount: Int? = null)
data class LocationQuestPage(val items: List<LocationQuest>, val page: Int, val totalPages: Int)
/** Lean map locators; details are fetched only for the selected object. */
data class CatalogMapPage(val markers: List<CatalogEntry>, val truncated: Boolean)

/** Server-owned page boundaries for a traveler's Saved Spots. */
data class SavedSpotPage(val spots: List<CatalogEntry>, val page: Int, val totalPages: Int)

sealed interface CatalogResult<out T> {
    data class Ready<T>(val value: T) : CatalogResult<T>
    /** Preserves canonical business identity separately from HTTP and retry timing. */
    data class HttpError(val status: Int, val retryAfterSeconds: Long? = null, val code: String? = null,
        val fieldIssues: List<CatalogFieldIssue> = emptyList()) : CatalogResult<Nothing>
    data class Maintenance(val retryAfterSeconds: Long? = null) : CatalogResult<Nothing>
    data object NetworkError : CatalogResult<Nothing>
    data object InvalidResponse : CatalogResult<Nothing>
}

/** SearchSort values are the exact OpenAPI /search values. */
enum class SearchSort(val wireValue: String) {
    RELEVANCE("relevance"),
    TOP_RATED("top_rated"),
}



sealed interface TravelerRecommendation {
    data object NeedsInterests : TravelerRecommendation
    data object NoMatch : TravelerRecommendation
    data class Matched(val collection: CatalogEntry, val interests: List<String>) : TravelerRecommendation
}
