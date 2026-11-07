package com.floopel.feature.traveler.testing

import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import java.util.concurrent.atomic.AtomicInteger

/** Mutation metadata deliberately omits owner-read facts; this is a test-only domain boundary. */
class TravelerPublicationFixture {
    val base = TravelerFixture()
    val ownerReads = AtomicInteger()
    val planReads = AtomicInteger()
    val unpublishCalls = AtomicInteger()
    @Volatile var published = false
    @Volatile var failOwnerRead = false
    @Volatile var failPlanRead = false
    var beforeOwnerRead: suspend () -> Unit = {}
    var beforePlanRead: suspend () -> Unit = {}
    var onPublished: () -> Unit = {}
    fun owner(id: String = base.planId) = ItineraryDistribution(id, base.plan.itinerary.title, base.plan.itinerary.description,
        if (published) "public" else "private", true, "active", false, null,
        if (base.publicationCalls.get() + unpublishCalls.get() == 0) "revision-1" else "revision-2",
        emptyList(), base.plan.stops.size)
    val itineraries = object : TravelerItineraryRepository by base.itineraries {
        override suspend fun detail(id: String): CatalogResult<OwnedItineraryDetail> {
            planReads.incrementAndGet(); beforePlanRead()
            if (failPlanRead) return CatalogResult.NetworkError
            return CatalogResult.Ready(base.plan.copy(itinerary = base.plan.itinerary.copy(id = id)))
        }
    }
    val distribution = object : TravelerItineraryDistributionRepository by base.distribution {
        override suspend fun read(id: String): CatalogResult<ItineraryDistribution> {
            ownerReads.incrementAndGet(); beforeOwnerRead()
            return if (failOwnerRead) CatalogResult.NetworkError else CatalogResult.Ready(owner(id))
        }
        override suspend fun publish(id: String, review: ItineraryPublicationReview, key: String): CatalogResult<ItineraryDistribution> {
            base.publicationCalls.incrementAndGet(); published = true
            onPublished()
            return CatalogResult.Ready(owner(id).copy(updatedAt = null, stopCount = null))
        }
        override suspend fun unpublish(id: String, key: String): CatalogResult<ItineraryDistribution> {
            unpublishCalls.incrementAndGet(); published = false
            return CatalogResult.Ready(owner(id).copy(updatedAt = null, stopCount = null))
        }
    }
    val runtime = base.runtime.let { r -> TravelerRuntime(r.identity, r.catalog, itineraries, distribution, r.trips,
        r.notifications, r.account, r.legal, r.registration, r.publicContent, r.savedSpots, r.questGroups, r.newOperationKey, r.startup) }
}
