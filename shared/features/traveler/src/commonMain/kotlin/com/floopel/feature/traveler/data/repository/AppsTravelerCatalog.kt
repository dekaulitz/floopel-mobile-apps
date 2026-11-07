package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.remote.canonicalAppsFailureCode
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*

import com.floopel.feature.traveler.domain.model.AiReferenceRating
import com.floopel.feature.traveler.domain.model.CatalogPhoto
import com.floopel.feature.traveler.domain.model.TravelerDiscoveryLimits
import com.floopel.feature.traveler.domain.model.StoryMetadata
import com.floopel.feature.traveler.domain.model.CatalogPage
import com.floopel.feature.traveler.domain.model.CatalogEntry
import com.floopel.feature.traveler.domain.model.CatalogKind
import com.floopel.feature.traveler.domain.model.CatalogResult
import com.floopel.feature.traveler.domain.model.GeoPoint
import com.floopel.feature.traveler.domain.repository.TravelerCatalog
import com.floopel.feature.traveler.domain.model.StoryType
import com.floopel.feature.traveler.domain.model.SearchSort
import com.floopel.feature.traveler.domain.model.LocationQuest
import com.floopel.feature.traveler.domain.model.LocationQuestPage
import com.floopel.feature.traveler.domain.model.CatalogMapPage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.booleanOrNull

/**
 * Public read adapter for application/api-contract/openapi-apps.json.
 * Android/iOS use identical request paths, envelope and failure handling.
 */
internal class AppsTravelerCatalog(private val rest: FloopelRestClient) : TravelerCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun nearbySpots(point: GeoPoint, locale: String, page: Int, radiusMeters: Int): CatalogResult<CatalogPage> {
        if (radiusMeters !in TravelerExploreRadius.OPTIONS_METERS || page < 1 || !point.latitude.isFinite() || !point.longitude.isFinite() || point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) return CatalogResult.HttpError(422)
        return catalogPage("/spots?locale=${localeParam(locale)}&lat=${point.latitude}&lng=${point.longitude}&radius_m=$radiusMeters&page=$page&per_page=${TravelerDiscoveryLimits.SPOTS}", CatalogKind.SPOT, locale, page)
    }

    override suspend fun nearestLocation(point: GeoPoint): CatalogResult<CatalogEntry?> {
        if (!point.latitude.isFinite() || !point.longitude.isFinite() || point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0) return CatalogResult.HttpError(422)
        return when (val response = readEnvelope("/locations/nearest?lat=${point.latitude}&lng=${point.longitude}")) {
            is CatalogResult.Ready -> {
                val data = response.value.getValue("data")
                if (data is JsonNull) CatalogResult.Ready(null)
                else readEntry(data, CatalogKind.LOCATION)?.takeIf { it.geo != null }?.let { CatalogResult.Ready(it) } ?: CatalogResult.InvalidResponse
            }
            is CatalogResult.HttpError -> response
            is CatalogResult.Maintenance -> response
            CatalogResult.NetworkError -> CatalogResult.NetworkError
            CatalogResult.InvalidResponse -> CatalogResult.InvalidResponse
        }
    }

    override suspend fun discoverySpots(locale: String, center: GeoPoint?, locationId: String?): CatalogResult<List<CatalogEntry>> {
        if (center != null && (!center.latitude.isFinite() || !center.longitude.isFinite() || center.latitude !in -90.0..90.0 || center.longitude !in -180.0..180.0)) return CatalogResult.HttpError(422)
        val proximity = center?.let { "&lat=${it.latitude}&lng=${it.longitude}&radius_m=5000" }.orEmpty()
        val scope = locationId?.let { "&location_id=" + it.encodeURLParameter() }.orEmpty()
        return list("/spots?locale=" + localeParam(locale) + "&per_page=15" + proximity + scope, CatalogKind.SPOT, locale)
    }

    override suspend fun mapMarkers(bbox: String): CatalogResult<CatalogMapPage> =
        decode("/map/markers?kind=spot&bbox=" + bbox.encodeURLParameter()) { data ->
            runCatching {
                val value = requireNotNull(data.obj())
                val truncated = (value.getValue("truncated") as JsonPrimitive).also { require(!it.isString) }.booleanOrNull!!
                val markers = (value.getValue("markers") as JsonArray).map { item ->
                    val marker = requireNotNull(item.obj())
                    require(marker.string("kind") == "spot")
                    val id = requireNotNull(marker.string("id"))
                    CatalogEntry(id, id, requireNotNull(marker.string("name")), null, CatalogKind.SPOT, null,
                        geo = requireNotNull(readGeo(marker["geo"])), categorySlug = marker.string("category_slug")?.takeUnless { it == "null" })
                }.distinctBy { it.id }
                CatalogMapPage(markers, truncated)
            }.getOrNull()
        }

    override suspend fun curatedItineraries(query: String, locationId: String?, locale: String, page: Int): CatalogResult<CatalogPage> {
        if (page < 1 || query.length > 160) return CatalogResult.HttpError(422)
        val text = query.trim().takeIf(String::isNotEmpty)?.let { "&q=" + it.encodeURLParameter() }.orEmpty()
        val region = locationId?.takeIf(String::isNotBlank)?.let { "&location_id=" + it.encodeURLParameter() }.orEmpty()
        return catalogPage("/itineraries?source=curated&locale=${localeParam(locale)}&page=$page&per_page=20$text$region", CatalogKind.ITINERARY, locale, page)
    }

    override suspend fun countryPage(locale: String, page: Int): CatalogResult<CatalogPage> {
        if (page < 1) return CatalogResult.HttpError(422)
        return catalogPage("/locations?level=country&locale=" + localeParam(locale) + "&page=$page&per_page=20",
            CatalogKind.LOCATION, locale, page)
    }

    override suspend fun searchSpotPage(query: String, locationId: String?, locale: String, page: Int): CatalogResult<CatalogPage> {
        if (page < 1) return CatalogResult.HttpError(422)
        val scope = locationId?.let { "&location_id=" + it.encodeURLParameter() }.orEmpty()
        val path = "/spots?locale=" + localeParam(locale) + "&page=$page&per_page=" + TravelerDiscoveryLimits.SPOTS +
            "&q=" + query.trim().encodeURLParameter() + scope
        return catalogPage(path, CatalogKind.SPOT, locale, page)
    }

    override suspend fun browse(kind: CatalogKind, locale: String, page: Int, storyType: StoryType?, locationId: String?, parentId: String?): CatalogResult<CatalogPage> {
        if (page < 1) return CatalogResult.HttpError(422)
        val base = when (kind) { CatalogKind.LOCATION -> "/locations"; CatalogKind.SPOT -> "/spots"; CatalogKind.COLLECTION -> "/collections"; CatalogKind.STORY -> locationId?.let { "/locations/" + it.encodeURLParameter() + "/content" } ?: "/content"; CatalogKind.ITINERARY -> "/itineraries" }
        val filters = when (kind) {
            CatalogKind.LOCATION -> parentId?.let { "&parent_id=" + it.encodeURLParameter() } ?: "&level=region"
            CatalogKind.SPOT -> locationId?.let { "&location_id=" + it.encodeURLParameter() }.orEmpty()
            CatalogKind.COLLECTION -> locationId?.let { "&location_id=" + it.encodeURLParameter() }.orEmpty()
            CatalogKind.STORY -> storyType?.let { "&type=" + it.wireValue }.orEmpty()
            CatalogKind.ITINERARY -> "&source=curated" + locationId?.let { "&location_id=" + it.encodeURLParameter() }.orEmpty()
        }
        val perPage = if (kind == CatalogKind.SPOT) TravelerDiscoveryLimits.SPOTS else 20
        val result = catalogPage(base + "?locale=" + localeParam(locale) + "&page=$page&per_page=$perPage" + filters, kind, locale, page)
        return if (kind == CatalogKind.STORY && storyType != null && result is CatalogResult.Ready &&
            result.value.items.any { it.category != storyType.wireValue }) CatalogResult.InvalidResponse else result
    }

    private suspend fun catalogPage(path: String, kind: CatalogKind, locale: String, page: Int): CatalogResult<CatalogPage> {
        val result = when (val result = readEnvelope(path)) {
            is CatalogResult.Ready -> runCatching {
                val envelope = result.value
                val pagination = envelope.getValue("meta").obj()!!.getValue("pagination").obj()!!
                fun integer(key: String) = (pagination.getValue(key) as JsonPrimitive).also { require(!it.isString) }.content.toInt()
                val served = integer("page"); val total = integer("total_pages")
                require(served == page && total >= 0 && (served <= total || total == 0 && served == 1))
                val items = envelope.getValue("data").let { it as JsonArray }.map { requireNotNull(readEntry(it, kind)) }
                CatalogResult.Ready(CatalogPage(items, served, total))
            }.getOrElse { CatalogResult.InvalidResponse }
            is CatalogResult.HttpError -> result
            CatalogResult.InvalidResponse -> CatalogResult.InvalidResponse
            is CatalogResult.Maintenance -> result
            CatalogResult.NetworkError -> CatalogResult.NetworkError
        }
        return if (result is CatalogResult.Ready) CatalogResult.Ready(
            result.value.copy(items = withStoryLevels(withSpotLocations(result.value.items, locale)))) else result
    }

    override suspend fun activeQuests(locale: String, page: Int): CatalogResult<LocationQuestPage> = questPage(locale, page)

    override suspend fun locationQuests(locationId: String, locale: String, page: Int): CatalogResult<LocationQuestPage> {
        if (locationId.isBlank()) return CatalogResult.HttpError(422)
        return questPage(locale, page, locationId)
    }

    override suspend fun spotArticles(spotId: String, locale: String, page: Int): CatalogResult<CatalogPage> {
        if (spotId.isBlank() || page < 1) return CatalogResult.HttpError(422)
        val result = catalogPage("/spots/" + spotId.encodeURLParameter() + "/content?type=article&locale=" + localeParam(locale) +
            "&page=$page&per_page=8", CatalogKind.STORY, locale, page)
        return if (result is CatalogResult.Ready && result.value.items.any { it.category != "article" }) CatalogResult.InvalidResponse else result
    }

    override suspend fun spotQuests(spotId: String, locale: String, page: Int): CatalogResult<LocationQuestPage> {
        if (spotId.isBlank()) return CatalogResult.HttpError(422)
        return questPage(locale, page, spotId = spotId)
    }

    private suspend fun questPage(locale: String, page: Int, locationId: String? = null, spotId: String? = null): CatalogResult<LocationQuestPage> {
        if (page < 1) return CatalogResult.HttpError(422)
        val scope = locationId?.let { "&location_id=" + it.encodeURLParameter() }.orEmpty() +
            spotId?.let { "&spot_id=" + it.encodeURLParameter() }.orEmpty()
        return when (val result = readEnvelope("/gamification/quests?locale=" + localeParam(locale) +
            "&status=active$scope&page=$page&per_page=8")) {
            is CatalogResult.Ready -> runCatching {
                val envelope = result.value
                val pagination = envelope.getValue("meta").obj()!!.getValue("pagination").obj()!!
                fun integer(key: String) = (pagination.getValue(key) as JsonPrimitive).also { require(!it.isString) }.content.toInt()
                val served = integer("page"); val total = integer("total_pages")
                require(served == page && total >= 0 && (served <= total || total == 0 && served == 1))
                val quests = (envelope.getValue("data") as JsonArray).map { value ->
                    val obj = requireNotNull(value.obj())
                    val reward = obj.getValue("reward_preview").obj()!!
                    val xp = (reward.getValue("xp") as JsonPrimitive).also { require(!it.isString) }.content.toLong()
                    require(xp >= 0)
                    val badge = reward["badge"].obj()
                    val badgeImage = badge?.get("image").obj()?.string("url")?.let { rest.resolveMediaUrl(it) }
                        ?.let { CatalogPhoto(it, badge?.string("name").orEmpty()) }
                    LocationQuest(requireNotNull(obj.string("id")), requireNotNull(obj.string("slug")), requireNotNull(obj.string("title")), xp, badgeImage, obj.readLocations())
                }
                CatalogResult.Ready(LocationQuestPage(quests, served, total))
            }.getOrElse { CatalogResult.InvalidResponse }
            is CatalogResult.HttpError -> result
            CatalogResult.InvalidResponse -> CatalogResult.InvalidResponse
            is CatalogResult.Maintenance -> result
            CatalogResult.NetworkError -> CatalogResult.NetworkError
        }
    }

    override suspend fun regions(locale: String) =
        list("/locations?level=region&locale=" + localeParam(locale) + "&per_page=20", CatalogKind.LOCATION)

    override suspend fun topLocations(locale: String, perPage: Int): CatalogResult<List<CatalogEntry>> {
        require(perPage in 1..100) { "per_page must be within OpenAPI bounds" }
        return list("/locations?locale=" + localeParam(locale) + "&per_page=$perPage", CatalogKind.LOCATION)
    }

    override suspend fun childRegions(parentId: String, locale: String) =
        list("/locations?parent_id=" + parentId.encodeURLParameter() + "&locale=" + localeParam(locale), CatalogKind.LOCATION)

    override suspend fun spots(locale: String, locationId: String?, perPage: Int): CatalogResult<List<CatalogEntry>> {
        require(perPage in 1..100) { "per_page must be within OpenAPI bounds" }
        val filter = locationId?.let { "&location_id=" + it.encodeURLParameter() }.orEmpty()
        return list("/spots?locale=" + localeParam(locale) + "&per_page=" + perPage + filter, CatalogKind.SPOT, locale)
    }

    override suspend fun collections(locale: String, perPage: Int): CatalogResult<List<CatalogEntry>> {
        require(perPage in 1..100) { "per_page must be within OpenAPI bounds" }
        return list("/collections?locale=" + localeParam(locale) + "&per_page=" + perPage, CatalogKind.COLLECTION)
    }

    override suspend fun stories(locale: String, type: StoryType?, perPage: Int): CatalogResult<List<CatalogEntry>> {
        require(perPage in 1..100) { "per_page must be within OpenAPI bounds" }
        val filter = type?.let { "&type=" + it.wireValue }.orEmpty()
        return list("/content?locale=" + localeParam(locale) + "&per_page=" + perPage + filter, CatalogKind.STORY)
    }

    override suspend fun curatedItineraries(locale: String) =
        list("/itineraries?source=curated&featured=true&per_page=6", CatalogKind.ITINERARY)

    override suspend fun search(
        query: String,
        locale: String,
        sort: SearchSort,
        category: String?,
    ): CatalogResult<List<CatalogEntry>> = searchEntries(query, locale, sort, category, false)

    override suspend fun searchLocations(query: String, locale: String): CatalogResult<List<CatalogEntry>> =
        searchEntries(query, locale, SearchSort.RELEVANCE, null, true)

    private suspend fun searchEntries(query: String, locale: String, sort: SearchSort, category: String?, locationsOnly: Boolean): CatalogResult<List<CatalogEntry>> {
        val trimmed = query.trim()
        if (trimmed.length < 3) return CatalogResult.Ready(emptyList())
        val categoryParam = category?.takeIf { it.isNotBlank() }
            ?.let { "&category=" + it.encodeURLParameter() }.orEmpty()
        val result = decode(
            "/search?q=" + trimmed.encodeURLParameter() +
                "&locale=" + localeParam(locale) +
                "&sort=" + sort.wireValue +
                categoryParam + if (locationsOnly) "&kinds=location" else "",
        ) { data ->
            val groups = data.obj()?.get("groups") as? JsonArray ?: return@decode null
            groups.flatMap { group ->
                val hits = group.obj()?.get("items") as? JsonArray ?: return@flatMap emptyList()
                hits.mapNotNull { hit ->
                    val kind = when (hit.obj()?.string("kind")) {
                        "location" -> CatalogKind.LOCATION
                        "spot" -> CatalogKind.SPOT
                        "collection" -> CatalogKind.COLLECTION
                        else -> null
                    }
                    kind?.let { hitKind ->
                        readEntry(hit, hitKind, titleOverride = hit.obj()?.string("name"), requiresLocations = false)?.copy(
                            // SearchHit.type is a Spot's category_slug per OpenAPI.
                            category = if (hitKind == CatalogKind.SPOT) hit.obj()?.string("type")
                                else null,
                            locationName = (hit.obj()?.get("breadcrumb") as? JsonArray)
                                ?.mapNotNull { it.obj()?.string("name")?.takeIf(String::isNotBlank) }
                                ?.takeIf { it.isNotEmpty() }?.joinToString(" · "),
                            locationLevel = if (hitKind == CatalogKind.LOCATION) hit.obj()?.string("type") else null,
                        )
                    }
                }
            }
        }
        return if (result is CatalogResult.Ready) CatalogResult.Ready(withStoryLevels(withCommentTotals(result.value))) else result
    }

    override suspend fun detail(item: CatalogEntry, locale: String): CatalogResult<CatalogEntry> {
        val segment = when (item.kind) {
            CatalogKind.LOCATION, CatalogKind.ITINERARY -> item.id
            CatalogKind.SPOT, CatalogKind.COLLECTION, CatalogKind.STORY -> item.slug
        }
        val prefix = when (item.kind) {
            CatalogKind.LOCATION -> "/locations/"
            CatalogKind.SPOT -> "/spots/"
            CatalogKind.COLLECTION -> "/collections/"
            CatalogKind.STORY -> "/content/"
            CatalogKind.ITINERARY -> "/itineraries/"
        }
        val query = if (item.kind == CatalogKind.ITINERARY) "" else "?locale=" + localeParam(locale)
        val result = decode(prefix + segment.encodeURLParameter() + query) { data ->
            val obj = data.obj() ?: return@decode null
            val main = readEntry(data, item.kind) ?: return@decode null
            val related = when (item.kind) {
                CatalogKind.SPOT -> (obj["nearby"] as? JsonArray)?.mapNotNull { readEntry(it, CatalogKind.SPOT) }.orEmpty()
                CatalogKind.COLLECTION -> (obj["members"] as? JsonArray)?.mapNotNull { member ->
                    val nested = member.obj() ?: return@mapNotNull null
                    val nestedKind = when (nested.string("target_type")) {
                        "spot" -> CatalogKind.SPOT
                        "itinerary" -> CatalogKind.ITINERARY
                        else -> return@mapNotNull null
                    }
                    val target = nested["target"] ?: return@mapNotNull null
                    readEntry(target, nestedKind, titleOverride = target.obj()?.string("name"), requiresLocations = false)
                }.orEmpty()
                CatalogKind.STORY -> (obj["attachments"] as? JsonArray)?.mapNotNull { attachment ->
                    val value = attachment.obj() ?: return@mapNotNull null
                    val spotId = value.string("spot_id")?.takeIf { it.isNotBlank() && it != "null" }
                    val locationId = value.string("location_id")?.takeIf { it.isNotBlank() && it != "null" }
                    val id = spotId ?: locationId ?: return@mapNotNull null
                    val slug = value.string("slug")?.takeIf { it.isNotBlank() && it != "null" } ?: if (spotId == null) id else return@mapNotNull null
                    val name = value.string("label")?.takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
                    CatalogEntry(id, slug, name, null, if (spotId != null) CatalogKind.SPOT else CatalogKind.LOCATION, null)
                }.orEmpty()
                else -> emptyList()
            }
            main.copy(detailLoaded = true, related = related,
                locationName = main.locationName ?: item.locationName,
                visitNotes = if (item.kind == CatalogKind.SPOT) obj.string("visit_notes")?.takeUnless { it == "null" } else null,
                collections = if (item.kind == CatalogKind.SPOT) (obj["collections"] as? JsonArray)?.mapNotNull { appearance -> appearance.obj()?.get("collection")?.let { readEntry(it, CatalogKind.COLLECTION) } }.orEmpty() else emptyList(),
                bodyHtml = normalizeBodyImages(when (item.kind) {
                CatalogKind.STORY -> obj.string("body_html")
                CatalogKind.SPOT -> obj.string("story")
                CatalogKind.LOCATION -> obj.string("why_it_matters")
                CatalogKind.COLLECTION -> obj.string("intro")
                CatalogKind.ITINERARY -> null
            }))
        }
        if (result !is CatalogResult.Ready) return result
        // SpotCollectionSummary intentionally omits media. Resolve the cover from its public parent.
        val collections = result.value.collections.map { collection ->
            if (collection.coverUrl != null) collection else {
                when (val parent = decode("/collections/" + collection.slug.encodeURLParameter() +
                    "?locale=" + localeParam(locale)) { readEntry(it, CatalogKind.COLLECTION) }) {
                    is CatalogResult.Ready -> collection.copy(
                        coverUrl = parent.value.coverUrl,
                        photos = parent.value.photos,
                    )
                    else -> collection // An unavailable optional cover must not fail the Spot detail.
                }
            }
        }
        // Editorial attachments contain identity/label only, not a media projection.
        val related = if (item.kind == CatalogKind.STORY) result.value.related.map { attachment ->
            val attachmentPath = if (attachment.kind == CatalogKind.LOCATION) "/locations/" + attachment.id.encodeURLParameter()
                else "/spots/" + attachment.slug.encodeURLParameter()
            when (val parent = decode(attachmentPath + "?locale=" + localeParam(locale)) { readEntry(it, attachment.kind) }) {
                is CatalogResult.Ready -> parent.value
                else -> attachment // Optional related content must not make the article unreadable.
            }
        } else result.value.related
        val enriched = withStoryLevels(withSpotLocations(listOf(result.value) + related, locale))
        return CatalogResult.Ready(enriched.first().copy(collections = collections, related = enriched.drop(1)))
    }

    /** Editorial author badges share the same bounded lookup as other traveler identities. */
    private suspend fun withStoryLevels(entries: List<CatalogEntry>): List<CatalogEntry> {
        val identities = TravelerAvatarIdentities(rest).read(entries.mapNotNull { it.story?.authorUsername })
        return entries.map { entry -> entry.copy(story = entry.story?.let { it.copy(authorLevel = identities[it.authorUsername]?.level) }) }
    }

    private suspend fun list(path: String, kind: CatalogKind, locale: String = "id"): CatalogResult<List<CatalogEntry>> {
        val result = decode(path) { data -> (data as? JsonArray)?.let { values ->
            val entries = values.map { readEntry(it, kind) }
            if (entries.any { it == null }) null else entries.filterNotNull()
        } }
        return if (result is CatalogResult.Ready) CatalogResult.Ready(withStoryLevels(withSpotLocations(result.value, locale))) else result
    }

    /** Resolve each distinct public Location once per result, never render UUIDs as place labels. */
    internal suspend fun withSpotLocations(entries: List<CatalogEntry>, locale: String): List<CatalogEntry> {
        val resolved = mutableMapOf<String, JsonObject?>()
        val names = entries.filter { it.kind == CatalogKind.SPOT && (it.locationName == null || it.detailLoaded && it.timezone == null) }
            .mapNotNull { it.locationId }.distinct().associateWith { id ->
                val trail = mutableListOf<String>()
                val visited = mutableSetOf<String>()
                var current: String? = id
                while (current != null && visited.add(current)) {
                    val key = current
                    if (!resolved.containsKey(key)) {
                        resolved[key] = when (val location = decode("/locations/" + key.encodeURLParameter() +
                            "?locale=" + localeParam(locale)) { it.obj() }) {
                            is CatalogResult.Ready -> {
                                // The UUID aggregate omits timezone; the by-slug public detail owns that field.
                                val slug = location.value.string("slug")
                                if ("timezone" !in location.value && !slug.isNullOrBlank() && slug != key &&
                                    entries.any { it.kind == CatalogKind.SPOT && it.detailLoaded && it.locationId == key }) {
                                    when (val detail = decode("/locations/" + slug.encodeURLParameter() + "?locale=" + localeParam(locale)) { it.obj() }) {
                                        is CatalogResult.Ready -> if (detail.value.string("id") == key) detail.value else location.value
                                        else -> location.value
                                    }
                                } else location.value
                            }
                            else -> null
                        }
                    }
                    val location = resolved[key] ?: break
                    location.string("name")?.takeIf { it.isNotBlank() && it != "null" }?.let(trail::add)
                    if (location.string("level") == "country") break
                    current = location.string("parent_id")?.takeUnless { it.isBlank() || it == "null" }
                }
                trail.takeIf { it.isNotEmpty() }?.joinToString(" · ")
            }
        val hydrated = entries.map { entry ->
            val ancestors = mutableListOf<LocationAncestor>()
            if (entry.kind == CatalogKind.LOCATION) {
                val visited = mutableSetOf(entry.id)
                var parent = entry.parentLocationId
                while (parent != null && ancestors.size < 8 && visited.add(parent)) {
                    val key = parent
                    if (!resolved.containsKey(key)) resolved[key] = when (val result = decode(
                        "/locations/" + key.encodeURLParameter() + "?locale=" + localeParam(locale)) { it.obj() }) {
                        is CatalogResult.Ready -> result.value
                        else -> null
                    }
                    val value = resolved[key] ?: break
                    if (value.string("id") != key) break
                    val name = value.string("name")?.takeUnless { it.isBlank() || it == "null" } ?: break
                    val level = value.string("level")?.takeUnless { it.isBlank() || it == "null" } ?: break
                    ancestors += LocationAncestor(key, name, level)
                    if (level == "country") break
                    parent = value.string("parent_id")?.takeUnless { it.isBlank() || it == "null" }
                }
            }
            entry.copy(
            locationName = entry.locationName ?: names[entry.locationId],
            // Trip creation asserts the first Spot's own Location, never an ancestor or device zone.
            timezone = entry.timezone ?: if (entry.kind == CatalogKind.SPOT) resolved[entry.locationId]?.string("timezone")
                ?.takeUnless { it.isBlank() || it == "null" } else null,
            ancestors = if (entry.kind == CatalogKind.LOCATION) ancestors else entry.ancestors,
        ) }
        return withCommentTotals(hydrated)
    }

    /** Only distinct public stops are hydrated, with four requests at most in flight. */
    internal suspend fun publicSpotCards(entries: List<CatalogEntry>, locale: String): List<CatalogEntry> = coroutineScope {
        val limit = Semaphore(4)
        val cards = entries.distinctBy { it.id }.map { original -> async {
            limit.withPermit {
                val result = decode("/spots/${original.slug.encodeURLParameter()}?locale=${localeParam(locale)}") { readEntry(it,CatalogKind.SPOT) }
                when (result) {
                    is CatalogResult.Ready -> result.value.copy(title = original.title, geo = original.geo ?: result.value.geo)
                    else -> original
                }
            }
        } }.awaitAll()
        withSpotLocations(cards,locale)
    }

    /** One bounded counter read per public target family, without fetching comment threads. */
    internal suspend fun withCommentTotals(entries: List<CatalogEntry>): List<CatalogEntry> {
        val totals = mutableMapOf<Pair<CatalogKind,String>,Long>()
        val types = mapOf(CatalogKind.SPOT to "spot",CatalogKind.LOCATION to "location",CatalogKind.ITINERARY to "itinerary",CatalogKind.STORY to "content")
        val reader = AppsCommentTotals(rest)
        entries.filter { it.commentCount == null && it.kind in types }.groupBy { it.kind }.forEach { (kind,items) ->
            reader.read(types.getValue(kind),items.map { it.id }).forEach { (id,total) -> totals[kind to id] = total }
        }
        return entries.map { entry -> entry.copy(commentCount = entry.commentCount ?: totals[entry.kind to entry.id]) }
    }

    /** HTTP 200 with malformed envelope is an error, not an empty catalog. */
    private suspend fun <T> decode(path: String, project: (JsonElement) -> T?): CatalogResult<T> = when (val result = readEnvelope(path)) {
        is CatalogResult.Ready -> project(result.value.getValue("data"))?.let { CatalogResult.Ready(it) } ?: CatalogResult.InvalidResponse
        is CatalogResult.HttpError -> result
        CatalogResult.InvalidResponse -> CatalogResult.InvalidResponse
        is CatalogResult.Maintenance -> result
        CatalogResult.NetworkError -> CatalogResult.NetworkError
    }
    /** Preserves canonical failure metadata through catalog reads without exposing diagnostic messages. */
    private suspend fun readEnvelope(path: String): CatalogResult<JsonObject> = when (val result = rest.execute(RestMethod.GET, "/api/apps/v1$path")) {
        is ApiOutcome.Success -> {
            val envelope = runCatching { json.parseToJsonElement(result.responseBody).obj() }.getOrNull()
            if (envelope == null || envelope["meta"].obj()?.string("code").isNullOrBlank() || envelope["error"] !is JsonNull || !envelope.containsKey("data")) CatalogResult.InvalidResponse
            else CatalogResult.Ready(envelope)
        }
        is ApiOutcome.Unavailable -> CatalogResult.Maintenance(result.retryAfterSeconds)
        is ApiOutcome.HttpFailure -> CatalogResult.HttpError(result.status, result.retryAfterSeconds,
            result.code ?: canonicalAppsFailureCode(result.jsonErrorBody))
        ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
    }

    /** The already authorized For-You projection must not be refetched anonymously by its reserved slug. */
    internal fun readRecommendation(element: JsonElement): CatalogEntry? {
        val value = element.obj() ?: return null
        val entry = readEntry(element, CatalogKind.COLLECTION) ?: return null
        val members = value["members"] as? JsonArray ?: return null
        val related = members.map { member ->
            val item = member.obj() ?: return null
            val kind = when (item.string("target_type")) { "spot" -> CatalogKind.SPOT; "itinerary" -> CatalogKind.ITINERARY; else -> return null }
            val target = item["target"] ?: return null
            readEntry(target, kind, titleOverride = target.obj()?.string("name"), requiresLocations = false) ?: return null
        }
        return entry.copy(bodyHtml = normalizeBodyImages(value.string("intro")), related = related, detailLoaded = true)
    }

    /** Internal reuse for authed saved Spot projection. */
    internal fun readSavedSpot(element: JsonElement): CatalogEntry? = readEntry(element, CatalogKind.SPOT)

    /** Retains canonical entity coverage while Spot and Location keep their own scalar identities. */
    private fun readEntry(element: JsonElement, kind: CatalogKind, titleOverride: String? = null, requiresLocations: Boolean = true): CatalogEntry? {
        val obj = element.obj() ?: return null
        val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: return null
        val title = (titleOverride ?: obj.string(if (kind == CatalogKind.COLLECTION || kind == CatalogKind.STORY || kind == CatalogKind.ITINERARY) "title" else "name"))
            ?.takeIf { it.isNotBlank() } ?: return null
        val slug = obj.string("slug") ?: obj.string("public_slug") ?: id
        val locations = if (requiresLocations && kind in setOf(CatalogKind.COLLECTION, CatalogKind.STORY, CatalogKind.ITINERARY))
            runCatching { obj.readLocations() }.getOrNull() ?: return null else emptyList()
        val subtitle = when (kind) {
            CatalogKind.SPOT -> obj.string("why_snippet")
            CatalogKind.STORY -> obj.string("snippet")
            CatalogKind.COLLECTION -> null  // intro is sanitized HTML, not plain-text card copy
            CatalogKind.LOCATION -> obj.string("level")
            CatalogKind.ITINERARY -> obj.string("description")
        }
        val entry = CatalogEntry(id, slug, title, subtitle, kind,
            mediaUrl(obj["cover_hero_image"] ?: obj["cover_image"]), locations = locations)
        return readEntryMetadata(entry, obj)
    }

    /** Optional display metadata never supplies or replaces an entity's canonical coverage. */
    private fun readEntryMetadata(entry: CatalogEntry, obj: JsonObject): CatalogEntry {
        val kind = entry.kind
        val title = entry.title
        return entry.copy(
            allowClone = if (kind == CatalogKind.ITINERARY) (obj["allow_clone"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull else null,
            commentCount = (obj["comment_count"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()?.takeIf { it >= 0 },
            itineraryDays = (obj["day_count"] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= 0 },
            spotCount = (obj["stop_count"] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= 0 },
            previewUrls = (obj["preview_images"] as? JsonArray)?.mapNotNull(::mediaUrl)?.distinct()?.take(4).orEmpty(),
            previewPhotos = (obj["preview_images"] as? JsonArray)?.mapNotNull { catalogMediaPhoto(it, rest, title) }?.distinctBy { it.url }?.take(4).orEmpty(),
            locationId = obj.string("location_id")?.takeUnless { it.isBlank() || it == "null" },
            timezone = if (kind == CatalogKind.LOCATION) obj.string("timezone")?.takeUnless { it.isBlank() || it == "null" } else null,
            locationLevel = if (kind == CatalogKind.LOCATION) obj.string("level") else null,
            parentLocationId = if (kind == CatalogKind.LOCATION) obj.string("parent_id")?.takeUnless { it.isBlank() || it == "null" } else null,
            visitMinutes = (obj["typical_duration_min"] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toIntOrNull()?.takeIf { it > 0 },
            tags = if (kind == CatalogKind.SPOT) (obj["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { tag -> tag.isString }?.content?.takeIf(String::isNotBlank) }.orEmpty() else emptyList(),
            geo = readGeo(obj["geo"]),
            aiReference = readAiReference(obj["reference_rating"]),
            photos = (listOfNotNull(obj["cover_hero_image"] ?: obj["cover_image"]) + ((obj["media"] as? JsonArray)?.toList().orEmpty()))
                .filter { it.obj()?.string("kind") in listOf(null, "image") }.mapNotNull { asset -> catalogMediaPhoto(asset, rest, title) }.distinctBy { it.url },
            story = if (kind == CatalogKind.STORY) StoryMetadata(
                obj["author"].obj()?.string("username"), obj.string("published_at")?.takeUnless { it == "null" },
                (obj["reading_time_minutes"] as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it >= 1 },
                (obj["tags"] as? JsonArray)?.mapNotNull { tag -> tag.obj()?.string("label") ?: tag.obj()?.string("name") ?: tag.obj()?.string("slug") }.orEmpty(),
                authorAvatarUrl = rest.resolveMediaUrl(obj["author"].obj()?.string("avatar_url"))) else null,
            categorySlug = if (kind == CatalogKind.SPOT) obj.string("category_slug") else null,
            category = when (kind) {
                CatalogKind.SPOT -> obj.string("category_label") ?: obj.string("category_slug")
                CatalogKind.STORY -> obj.string("type")
                else -> null
            },
        )
    }

    private fun normalizeBodyImages(html: String?): String? = html?.let { body ->
        Regex("""(<img\b[^>]*?\bsrc\s*=\s*)(["'])(.*?)\2""", RegexOption.IGNORE_CASE).replace(body) { match ->
            val url = rest.resolveMediaUrl(match.groupValues[3]) ?: ""
            match.groupValues[1] + match.groupValues[2] + url + match.groupValues[2]
        }
    }

    /** Use authorized responsive descriptor URLs only; never build provider storage keys. */
    private fun mediaUrl(asset: JsonElement?): String? {
        return catalogMediaPhoto(asset, rest, "")?.url
    }

    /**
     * Read the public AI provisional reference only. Suppression never leaks a
     * score and a missing/unsupported origin must not appear as human rating.
     */
    private fun readAiReference(value: JsonElement?): AiReferenceRating? {
        val reference = value.obj() ?: return null
        if (reference.string("origin") != "ai") return null
        val suppressed = (reference["suppressed"] as? JsonPrimitive)?.booleanOrNull ?: true
        val score = (reference["score"] as? JsonPrimitive)?.doubleOrNull
            ?.takeIf { it.isFinite() && it in 0.0..5.0 }
        return AiReferenceRating(
            score = if (suppressed) null else score,
            suppressed = suppressed,
        )
    }

    /** Only server-issued geo can drive real markers; reject invalid coordinates. */
    private fun readGeo(value: JsonElement?): GeoPoint? {
        val data = value.obj() ?: return null
        val latitude = (data["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val longitude = (data["lng"] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return GeoPoint(latitude, longitude)
    }

    private fun localeParam(locale: String) = if (locale == "en") "en" else "id"
}

private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.content
