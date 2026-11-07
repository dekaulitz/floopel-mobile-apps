package com.floopel.feature.traveler.testing

import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import java.util.concurrent.CopyOnWriteArrayList

/** Domain doubles for location/rating UI and VM tests, never included in the production runtime. */
class TravelerLocationFixture(score: Double? = 4.8, suppressed: Boolean = false, comments: Long? = 1_200) {
    val base = TravelerFixture()
    val reads = CopyOnWriteArrayList<String>()
    @Volatile var failCountries = false
    @Volatile var failChildren = false
    @Volatile var emptyChildren = false
    @Volatile var failSpots = false
    @Volatile var failQuests = false
    @Volatile var failTrips = false
    @Volatile var failArticles = false
    @Volatile var questPages = 1
    @Volatile var tripNextCursor: String? = null
    val tripQueries = CopyOnWriteArrayList<OpenTripQuery>()
    var beforeBrowse: suspend (String?) -> Unit = {}
    val indonesia = CatalogEntry("country-id", "indonesia", "Indonesia", null, CatalogKind.LOCATION, null, locationLevel = "country")
    val malaysia = indonesia.copy(id = "country-my", slug = "malaysia", title = "Malaysia")
    val centralJava = indonesia.copy(id = "region-jt", slug = "jawa-tengah", title = "Jawa Tengah", locationLevel = "region", parentLocationId = indonesia.id,
        ancestors = listOf(LocationAncestor(indonesia.id, indonesia.title, "country")))
    val yogyakarta = centralJava.copy(id = "region-diy", slug = "yogyakarta", title = "Daerah Istimewa Yogyakarta")
    val selangor = centralJava.copy(id = "region-my", slug = "selangor", title = "Selangor", parentLocationId = malaysia.id,
        ancestors = listOf(LocationAncestor(malaysia.id, malaysia.title, "country")))
    val magelang = centralJava.copy(id = "city-magelang", slug = "magelang", title = "Magelang", locationLevel = "city", parentLocationId = centralJava.id,
        ancestors = listOf(LocationAncestor(centralJava.id, centralJava.title, "region"), LocationAncestor(indonesia.id, indonesia.title, "country")))
    val cityMagelang = magelang.copy(id = "city-magelang-2", slug = "kota-magelang", title = "Kota Magelang")
    val spot = base.spot.copy(title = "Candi Borobudur", locationId = magelang.id, locationName = "Magelang", aiReference = AiReferenceRating(score, suppressed), commentCount = comments)
    val article = CatalogEntry("article", "cerita-stupa", "Cerita di balik stupa", null, CatalogKind.STORY, null, category = "article",
        story = StoryMetadata("floopel", null, 5, emptyList()), commentCount = 0,
        locations = listOf(LocationReference(magelang.id, magelang.title, magelang.slug, "city")))
    val catalog = object : TravelerCatalog by base.catalog {
        override suspend fun countryPage(locale: String, page: Int): CatalogResult<CatalogPage> {
            reads += "countries:$page"
            return if (failCountries) CatalogResult.HttpError(503, 2) else CatalogResult.Ready(CatalogPage(listOf(if (page == 1) indonesia else malaysia), page, 2))
        }
        override suspend fun topLocations(locale: String, perPage: Int) = CatalogResult.Ready(listOf(magelang, centralJava, yogyakarta))
        override suspend fun discoverySpots(locale: String, center: GeoPoint?, locationId: String?) = CatalogResult.Ready(listOf(spot))
        override suspend fun browse(kind: CatalogKind, locale: String, page: Int, storyType: StoryType?, locationId: String?, parentId: String?): CatalogResult<CatalogPage> {
            reads += "browse:$kind:${locationId ?: parentId}:$page:${storyType?.wireValue.orEmpty()}"
            beforeBrowse(parentId)
            val failure = when (kind) { CatalogKind.LOCATION -> failChildren; CatalogKind.SPOT -> failSpots; CatalogKind.STORY -> failArticles; else -> false }
            if (failure) return CatalogResult.NetworkError
            val items = when (kind) {
                CatalogKind.LOCATION -> if (emptyChildren) emptyList() else when (parentId) {
                    indonesia.id -> listOf(centralJava, yogyakarta); malaysia.id -> listOf(selangor); centralJava.id -> listOf(magelang, cityMagelang); else -> emptyList()
                }
                CatalogKind.SPOT -> listOf(spot)
                CatalogKind.STORY -> listOf(article)
                else -> emptyList()
            }
            return CatalogResult.Ready(CatalogPage(items, 1, 1))
        }
        override suspend fun detail(item: CatalogEntry, locale: String): CatalogResult<CatalogEntry> {
            reads += "detail:${item.id}"
            return CatalogResult.Ready((listOf(indonesia, malaysia, centralJava, yogyakarta, selangor, magelang, cityMagelang, spot, article)
                .firstOrNull { it.id == item.id } ?: spot).copy(detailLoaded = true))
        }
        override suspend fun searchLocations(query: String, locale: String): CatalogResult<List<CatalogEntry>> {
            reads += "search:$query"
            return CatalogResult.Ready(listOf(indonesia, malaysia, centralJava, yogyakarta, selangor, magelang, cityMagelang).filter { it.title.contains(query, true) })
        }
        override suspend fun untakenLocationQuests(locationId: String, locale: String, page: Int): CatalogResult<LocationQuestPage> {
            reads += "untaken:$locationId:$page"
            return if (failQuests) CatalogResult.HttpError(503, 2) else CatalogResult.Ready(LocationQuestPage(
                listOf(LocationQuest("quest-$page", "quest-simulasi", "Jejak budaya Magelang", 200, activityCount = 4)), page, questPages))
        }
    }
    val trips = object : TravelerTripRepository by base.trips {
        override suspend fun openTrips(query: OpenTripQuery): CatalogResult<OpenTripPage> {
            reads += "trips:${query.locationId.orEmpty()}"
            tripQueries += query
            return if (failTrips) CatalogResult.NetworkError else CatalogResult.Ready(OpenTripPage(listOf(base.openTrip), if (query.cursor == null) tripNextCursor else null))
        }
    }
    val runtime = base.runtime.let { r -> TravelerRuntime(r.identity, catalog, r.itineraries, r.distribution, trips,
        r.notifications, r.account, r.legal, r.registration, r.publicContent, r.savedSpots, r.questGroups, r.newOperationKey, r.startup) }
}
