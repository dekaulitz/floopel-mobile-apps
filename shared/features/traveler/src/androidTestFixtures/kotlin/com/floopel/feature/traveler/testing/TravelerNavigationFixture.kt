package com.floopel.feature.traveler.testing

import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant

/** Isolated navigation fixtures. No request reaches the local or production backend. */
class TravelerNavigationFixture {
    val base = TravelerFixture()
    val reads = CopyOnWriteArrayList<String>()
    val unexpectedWrites = AtomicInteger()
    val spot = base.spot.copy(title = "Museum simulasi")
    val location = CatalogEntry("00000000-0000-4000-8000-000000000201", "lokasi-simulasi", "Lokasi simulasi", null, CatalogKind.LOCATION, null,
        locationLevel = "city")
    @Volatile var searchFails = false
    var beforeSearch: suspend (String) -> Unit = {}
    val catalog = object : TravelerCatalog by base.catalog {
        override suspend fun topLocations(locale: String, perPage: Int) = CatalogResult.Ready(listOf(location))
        override suspend fun search(query: String, locale: String, sort: SearchSort, category: String?): CatalogResult<List<CatalogEntry>> {
            reads += "explorer:$query"; beforeSearch(query)
            return if (searchFails) CatalogResult.NetworkError else CatalogResult.Ready(listOf(spot))
        }
        override suspend fun searchSpotPage(query: String, locationId: String?, locale: String, page: Int): CatalogResult<CatalogPage> {
            reads += "search:$query:${locationId.orEmpty()}:$page"; beforeSearch(query)
            return if (searchFails) CatalogResult.NetworkError else CatalogResult.Ready(CatalogPage(listOf(spot), page, 1))
        }
        override suspend fun browse(kind: CatalogKind, locale: String, page: Int, storyType: StoryType?, locationId: String?, parentId: String?): CatalogResult<CatalogPage> {
            reads += "browse:$kind:${locationId.orEmpty()}:$page"
            return CatalogResult.Ready(CatalogPage(listOf(if (kind == CatalogKind.LOCATION) location else spot), page, 1))
        }
        override suspend fun detail(item: CatalogEntry, locale: String): CatalogResult<CatalogEntry> {
            reads += "detail:${item.kind}:${item.id}"
            return CatalogResult.Ready(if (item.kind == CatalogKind.LOCATION) location.copy(id = item.id, detailLoaded = true) else spot)
        }
    }
    val actionId = "00000000-0000-4000-8000-000000000202"
    val action = OwnerModerationAction(actionId, base.planId, "Rencana budaya simulasi", ModerationActionKind.HIDE,
        "privacy", "2026-11-08T00:00:00Z", false, null)
    val notice = TravelerNotification(base.inboxId, "itinerary.moderation_decided", NotificationClass.SYSTEM,
        "Pembaruan moderasi simulasi", "Lihat hasil moderasi rencana.", "/me/itinerary-moderation",
        Instant.parse("2026-11-08T00:00:00Z"), Instant.parse("2026-11-08T00:00:00Z"), null)
    @Volatile var notification = notice
    @Volatile var lookupResult: CatalogResult<TravelerNotification>? = null
    @Volatile var actionsResult: CatalogResult<List<OwnerModerationAction>> = CatalogResult.Ready(listOf(action))
    var beforeActions: suspend () -> Unit = {}
    val notifications = object : TravelerNotificationRepository by base.notifications {
        override suspend fun notifications(page: Int, classification: NotificationClass?, locale: String) =
            CatalogResult.Ready(NotificationPage(listOf(notification), 1, 20, 1, 1))
        override suspend fun notification(id: String, locale: String) = lookupResult ?: CatalogResult.Ready(notification)
    }
    val moderation = object : TravelerModerationRepository {
        override suspend fun actions(): CatalogResult<List<OwnerModerationAction>> { reads += "owner-actions"; beforeActions(); return actionsResult }
        private fun rejected(): CatalogResult<Nothing> { unexpectedWrites.incrementAndGet(); return CatalogResult.InvalidResponse }
        override suspend fun catalogReport(target: CatalogReportTarget, id: String, reason: CatalogReportReason, details: String, key: String) = rejected()
        override suspend fun itineraryPreview(id: String) = CatalogResult.InvalidResponse
        override suspend fun itineraryReport(id: String, reason: ItineraryReportReason, details: String, key: String) = rejected()
        override suspend fun appeal(actionId: String) = CatalogResult.InvalidResponse
        override suspend fun submitAppeal(actionId: String, reason: String, key: String) = rejected()
    }
    val runtime = base.runtime.let { r -> TravelerRuntime(r.identity, catalog, r.itineraries, r.distribution, r.trips,
        notifications, r.account, r.legal, r.registration, r.publicContent, r.savedSpots, r.questGroups, r.newOperationKey, r.startup,
        moderation = moderation) }
}
