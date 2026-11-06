package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.PublicQuestActivityDto
import com.floopel.feature.traveler.feature.auth.*

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.TravelerSession
import io.ktor.http.encodeURLPathPart
import io.ktor.http.encodeURLQueryComponent
import kotlinx.serialization.json.*

/** Public-safe reads use optional native identity as declared by the API, with no anonymous retry of private reads. */
internal class NativePublicTravelerApi(private val rest: FloopelRestClient, private val session: TravelerSession, private val locale: String = "id") : TravelerPublicRepository {
    private val http = NativeAuthorizedRequests(rest, session)
    private val avatarIdentities = TravelerAvatarIdentities({ http.execute(RestMethod.GET, it) }, rest::resolveMediaUrl)
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun profile(username: String) = read("/profiles/" + username.encodeURLPathPart()) { element ->
        val value = element.jsonObject
        val counts = value["counts"]?.takeUnless { it is JsonNull }?.jsonObject
        fun count(key: String) = counts?.number(key)?.also { require(it >= 0) }
        TravelerPublicProfile(value.text("username"), value.optional("display_name")?.takeIf(String::isNotBlank) ?: value.optional("first_name"), rest.resolveMediaUrl(value.optional("avatar_url")),
            value.optional("bio"), value.number("joined_year")?.also { require(it in 1..9999) }, count("itineraries"), count("regions"), count("published_stories"), count("saved_spots"))
    }
    override suspend fun progress(username: String) = read("/profiles/${username.encodeURLPathPart()}/progress") {
        val value = it.jsonObject
        val xp = value.getValue("xp_total").jsonPrimitive.also { require(!it.isString) }.long
        val level = requireNotNull(value.number("level"))
        require(xp >= 0 && level in 1..10)
        fun optionalXP(key: String): Long? = value[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.let { amount ->
            require(!amount.isString)
            amount.long.also { require(it >= 0) }
        }
        val floor = optionalXP("xp_floor")
        val remaining = optionalXP("xp_to_next_level")
        require(value.containsKey("xp_floor") == value.containsKey("xp_to_next_level"))
        require(!value.containsKey("xp_floor") || floor != null)
        require(floor == null || floor <= xp)
        require(remaining == null || floor != null && remaining <= Long.MAX_VALUE - (xp - floor))
        PublicProfileProgress(level, value.text("level_name"), xp, floor, remaining)
    }
    override suspend fun profileItineraries(username: String) = summaries("/profiles/${username.encodeURLPathPart()}/itineraries")
    /** Public activity identities share one batch lookup; late results are discarded after account changes. */
    override suspend fun questActivities(point: GeoPoint?, locale: String, page: Int, perPage: Int, radiusMeters: Int): CatalogResult<PublicAchievementPage<PublicQuestActivity>> {
        if (page < 1 || perPage !in 1..100 || locale !in listOf("id", "en") || radiusMeters !in TravelerExploreRadius.OPTIONS_METERS ||
            point != null && (!point.latitude.isFinite() || !point.longitude.isFinite() || point.latitude !in -90.0..90.0 || point.longitude !in -180.0..180.0)) return CatalogResult.HttpError(422)
        val generation = session.identityGeneration
        val nearby = point?.let { "&lat=${it.latitude}&lng=${it.longitude}&radius_m=$radiusMeters" }.orEmpty()
        val result = readEnvelope("/gamification/quest-activities?locale=$locale&page=$page&per_page=$perPage$nearby") { envelope ->
            val pagination = envelope.getValue("meta").jsonObject.getValue("pagination").jsonObject
            val current = pagination.number("page")!!; val total = pagination.number("total")!!; val pages = pagination.number("total_pages")!!
            require(current == page && total >= 0 && pages >= 0)
            val activities = json.decodeFromJsonElement<List<PublicQuestActivityDto>>(envelope.getValue("data"))
                .map { it.toDomain() }
            PublicAchievementPage(activities, current, total, pages)
        }
        if (result !is CatalogResult.Ready) return result
        val identities = avatarIdentities.read(result.value.items.map { it.username })
        if (generation != session.identityGeneration) return CatalogResult.HttpError(401)
        return CatalogResult.Ready(result.value.copy(items = result.value.items.map { it.copy(level = identities[it.username]?.level,
            avatarUrl = identities[it.username]?.avatarUrl) }))
    }
    override suspend fun completedQuests(username: String, locale: String, page: Int) = achievementPage(username, "completed-quests", locale, page) {
        val value = it.jsonObject
        PublicCompletedQuest(value.text("quest_id"), value.text("slug"), value.text("title"),
            value.text("completed_at").also { date -> kotlin.time.Instant.parse(date) })
    }
    override suspend fun badges(username: String, locale: String, page: Int) = achievementPage(username, "badges", locale, page) {
        val value = it.jsonObject
        val image = value.getValue("image").jsonObject
        PublicProfileBadge(value.text("id"), value.text("code"), value.text("name"),
            CatalogPhoto(requireNotNull(rest.resolveMediaUrl(image.text("url"))), value.text("name")))
    }
    private suspend fun <T> achievementPage(username: String, resource: String, locale: String, page: Int,
        project: (JsonElement) -> T): CatalogResult<PublicAchievementPage<T>> {
        if (page < 1 || locale !in listOf("id", "en")) return CatalogResult.HttpError(422)
        return readEnvelope("/profiles/${username.encodeURLPathPart()}/$resource?locale=$locale&page=$page&per_page=20") { envelope ->
            val pagination = envelope.getValue("meta").jsonObject.getValue("pagination").jsonObject
            val current = pagination.number("page")!!; val total = pagination.number("total")!!
            val pages = pagination.number("total_pages")!!
            require(current == page && total >= 0 && pages >= 0)
            PublicAchievementPage(envelope.getValue("data").jsonArray.map(project), current, total, pages)
        }
    }
    override suspend fun itineraries(page: Int): CatalogResult<List<PublicItinerarySummary>> =
        if (page < 1) CatalogResult.HttpError(422) else summaries("/public-itineraries?page=$page&per_page=20")
    /** Keeps query and region in the server request, including every appended page. */
    override suspend fun searchItineraries(query: String, locationId: String?, page: Int): CatalogResult<List<PublicItinerarySummary>> {
        val text = query.trim()
        if (page < 1 || text.length > 160 || locationId != null && !Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(locationId)) return CatalogResult.HttpError(422)
        val criteria = buildString {
            if (text.isNotEmpty()) append("&q=${text.encodeURLQueryComponent(encodeFull = true)}")
            locationId?.let { append("&location_id=$it") }
        }
        return summaries("/public-itineraries?page=$page&per_page=20$criteria")
    }
    /** Public authors share one level batch and cannot outlive the session that read their page. */
    private suspend fun summaries(path: String): CatalogResult<List<PublicItinerarySummary>> {
        val generation = session.identityGeneration
        val result = read(path) { it.jsonArray.map(::summary) }
        if (result !is CatalogResult.Ready) return result
        val totals = AppsCommentTotals(rest).read("itinerary",result.value.map { it.id })
        val identities = avatarIdentities.read(result.value.map { it.ownerUsername })
        if (generation != session.identityGeneration) return CatalogResult.HttpError(401)
        return CatalogResult.Ready(result.value.map { it.copy(commentCount = totals[it.id], ownerLevel = identities[it.ownerUsername]?.level,
            ownerAvatarUrl = identities[it.ownerUsername]?.avatarUrl) })
    }
    /** Snapshot authors use the target identity, while account changes discard the entire projection. */
    override suspend fun itinerary(slug: String): CatalogResult<PublicItinerarySnapshot> {
        val generation = session.identityGeneration
        if (!Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$").matches(slug)) return CatalogResult.HttpError(422)
        val result = read("/public-itineraries/" + slug.encodeURLPathPart()) { element ->
            val value = element.jsonObject
            require(value.text("visibility") == "public" && value.text("distribution_status") == "active")
            val owner = value.getValue("owner_public_profile").jsonObject
            val stops = value.getValue("stops").jsonArray.map { stopElement ->
                val stop = stopElement.jsonObject; val spot = stop.getValue("spot").jsonObject
                require(spot.text("id") == stop.text("spot_id"))
                val geo = (spot["geo"] as? JsonObject)?.let { point ->
                    val lat = point["lat"]?.jsonPrimitive?.doubleOrNull; val lng = point["lng"]?.jsonPrimitive?.doubleOrNull
                    if (lat != null && lng != null && lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0) GeoPoint(lat, lng) else null
                }
                PublicItineraryStop(stop.text("id"), CatalogEntry(spot.text("id"), spot.text("slug"), spot.text("name"), null, CatalogKind.SPOT, null, geo = geo),
                    stop.number("day")!!.also { require(it >= 1) }, stop.number("position")!!.also { require(it >= 0) }, stop.optional("public_note"), stop.optional("public_tips"),
                    stop.number("duration_minutes")?.also { require(it >= 1) }, stop.optional("arrive_time"), stop.optional("public_travel_note"))
            }.sortedWith(compareBy(PublicItineraryStop::day, PublicItineraryStop::position))
            require(stops.isNotEmpty())
            PublicItinerarySnapshot(value.text("id"), value.text("slug").also { require(it == slug) }, value.text("title"), value.text("description"), media(value["cover_image"]),
                value.flag("allow_clone"), value.text("publication_id"), value.number("publication_version")!!.also { require(it >= 1) }, owner.text("username"), owner.text("display_name"), stops, coverPhoto = catalogMediaPhoto(value["cover_image"], rest, value.text("title")), locations = value.readLocations())
        }
        if (result !is CatalogResult.Ready) return result
        val enriched = AppsTravelerCatalog(rest).publicSpotCards(result.value.stops.map { it.spot },locale).associateBy { it.id }
        val count = AppsCommentTotals(rest).read("itinerary",listOf(result.value.id))[result.value.id]
        val identity = avatarIdentities.read(listOf(result.value.ownerUsername))[result.value.ownerUsername]
        if (generation != session.identityGeneration) return CatalogResult.HttpError(401)
        return CatalogResult.Ready(result.value.copy(commentCount = count, ownerLevel = identity?.level, ownerAvatarUrl = identity?.avatarUrl,
            stops = result.value.stops.map { it.copy(spot = enriched[it.spot.id] ?: it.spot) }))

    }
    /** Publication coverage is read from this snapshot rather than a mutable plan. */
    private fun summary(element: JsonElement) = element.jsonObject.let {
        require(it.text("visibility") == "public")
        PublicItinerarySummary(it.text("id"), it.text("slug"), it.text("title"), it.text("description"), media(it["cover_image"]), it.text("publication_id"),
            it.number("publication_version")!!.also { require(it >= 1) }, it.number("stop_count")!!.also { require(it >= 1) }, it.text("owner_username"), coverPhoto = catalogMediaPhoto(it["cover_image"], rest, it.text("title")), locations = it.readLocations(),
            previewPhotos = (it["preview_images"] as? JsonArray)?.mapNotNull { asset -> catalogMediaPhoto(asset, rest, it.text("title")) }?.distinctBy { it.url }?.take(4).orEmpty(), previewUrls = (it["preview_images"] as? JsonArray)?.mapNotNull(::media)?.distinct()?.take(4).orEmpty(),
            ownerDisplayName = it.optional("owner_display_name")?.takeIf(String::isNotBlank),
            dayCount = it.number("day_count")?.also { days -> require(days >= 0) },
            publishedAt = it.optional("published_at")?.let(kotlin.time.Instant::parse), allowClone = it["allow_clone"]?.takeUnless { value -> value is JsonNull }?.let { _ -> it.flag("allow_clone") })
    }
    private fun media(element: JsonElement?): String? {
        return catalogMediaPhoto(element, rest, "")?.url
    }
    private suspend fun <T> read(path: String, project: (JsonElement) -> T): CatalogResult<T> = readEnvelope(path) { project(it.getValue("data")) }
    private suspend fun <T> readEnvelope(path: String, project: (JsonObject) -> T): CatalogResult<T> {
        val epoch = session.identityGeneration
        val signedIn = session.view.value.signedIn
        val result = if (signedIn) http.execute(RestMethod.GET, "/api/apps/v1$path") else rest.execute(RestMethod.GET, "/api/apps/v1$path")
        if (epoch != session.identityGeneration || signedIn != session.view.value.signedIn) return CatalogResult.HttpError(401)
        return when (result) {
        is ApiOutcome.Success -> runCatching {
            val value = json.parseToJsonElement(result.responseBody).jsonObject
            require(value["error"] is JsonNull && value["meta"]!!.jsonObject.text("code").isNotBlank())
            CatalogResult.Ready(project(value))
        }.getOrElse { CatalogResult.InvalidResponse }
        is ApiOutcome.HttpFailure -> CatalogResult.HttpError(result.status, result.retryAfterSeconds)
        is ApiOutcome.Unavailable -> CatalogResult.Maintenance(result.retryAfterSeconds)
        ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
        }
    }
}
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.also { require(it.isString) }.content
private fun JsonObject.optional(key: String) = get(key)?.takeUnless { it is JsonNull }?.let { text(key) }
private fun JsonObject.number(key: String) = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.also { require(!it.isString) }?.int
private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }.boolean
