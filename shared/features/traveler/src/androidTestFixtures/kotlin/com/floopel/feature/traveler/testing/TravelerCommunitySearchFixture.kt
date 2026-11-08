package com.floopel.feature.traveler.testing

import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import java.util.concurrent.CopyOnWriteArrayList

/** Read-only discovery doubles; queries never reach a backend. */
class TravelerCommunitySearchFixture {
    val navigation = TravelerNavigationFixture()
    val base = navigation.base
    val location = navigation.location
    val tripQueries = CopyOnWriteArrayList<OpenTripQuery>()
    data class PublicQuery(val query: String, val locationId: String?, val page: Int)
    val publicQueries = CopyOnWriteArrayList<PublicQuery>()
    @Volatile var tripSearchFails = false
    @Volatile var publicSearchFails = false
    var beforeTripSearch: suspend (String) -> Unit = {}
    var beforePublicSearch: suspend (String) -> Unit = {}
    val trips = object : TravelerTripRepository by navigation.runtime.trips {
        override suspend fun openTrips(query: OpenTripQuery): CatalogResult<OpenTripPage> {
            tripQueries += query; beforeTripSearch(query.query)
            return if (tripSearchFails) CatalogResult.NetworkError else
                CatalogResult.Ready(OpenTripPage(listOf(base.openTrip.copy(name = "Trip hasil ${query.query}")), null))
        }
    }
    val publicContent = object : TravelerPublicRepository by navigation.runtime.publicContent {
        override suspend fun searchItineraries(query: String, locationId: String?, page: Int): CatalogResult<List<PublicItinerarySummary>> {
            publicQueries += PublicQuery(query, locationId, page); beforePublicSearch(query)
            return if (publicSearchFails) CatalogResult.NetworkError else
                CatalogResult.Ready(listOf(base.publicPlan.copy(title = "Rencana hasil $query")))
        }
    }
    val runtime = navigation.runtime.let { r -> TravelerRuntime(r.identity, r.catalog, r.itineraries, r.distribution, trips,
        r.notifications, r.account, r.legal, r.registration, publicContent, r.savedSpots, r.questGroups, r.newOperationKey, r.startup,
        moderation = r.moderation) }
}
