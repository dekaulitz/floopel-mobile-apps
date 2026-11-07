package com.floopel.feature.traveler.testing

import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic repository boundary for Quest state/geometry tests; never packaged in a production app. */
class TravelerQuestDesignFixture {
    val base = TravelerFixture()
    val location = CatalogEntry("00000000-0000-4000-8000-000000000301", "magelang", "Magelang", null, CatalogKind.LOCATION, null,
        locationLevel = "city", ancestors = listOf(LocationAncestor("region", "Jawa Tengah", "region")))
    val coverage = listOf(LocationReference(location.id, "Magelang · Jawa Tengah", "magelang", "city"))
    val spot = base.spot.copy(title = "Candi simulasi", locationId = location.id, locationName = "Magelang", coverUrl = "file:///floopel-synthetic-fixture/spot.jpg",
        aiReference = AiReferenceRating(4.8, false), commentCount = 1_200)
    val card = QuestCard("00000000-0000-4000-8000-000000000401", "jejak-budaya-simulasi", "Jejak budaya simulasi", 200,
        "file:///floopel-synthetic-fixture/badge.svg", 4, coverage, "personal", "Jejak Budaya")
    val activeCard = card.copy(id = "00000000-0000-4000-8000-000000000402", slug = "warisan-simulasi", title = "Jelajah warisan simulasi", badgeName = "Penjelajah Budaya")
    val running = QuestRunning("00000000-0000-4000-8000-000000000501", activeCard.title, "active", 2, 4,
        (1..4).associate { "activity-$it" to if (it <= 2) "completed" else "available" }, "personal")
    val steps = (1..4).map { QuestStep("activity-$it", listOf("Kunjungi Candi simulasi", "Kenali cerita tempat", "Cerita perjalananmu", "Tutup penjelajahan")[it - 1],
        if (it == 1) "gps" else "question", it, spot.id.takeIf { _ -> it == 1 }) }
    @Volatile var readyReader = QuestReader(card, "Jelajahi jejak budaya dan selesaikan aktivitasnya.",
        "Selesaikan aktivitas di tiap lokasi. Progres dan hadiah diperbarui setelah terverifikasi.", card.badgeName, 12, 20, "active", "available", steps,
        listOf("Arif Simulasi", "Sinta Simulasi", "Dimas Simulasi"), listOf(QuestTravelerPreview("arif_simulasi", "Arif Simulasi", null),
            QuestTravelerPreview("sinta_simulasi", "Sinta Simulasi", null), QuestTravelerPreview("dimas_simulasi", "Dimas Simulasi", null)),
        QuestReaderOwner(card.id, "not_completed", null))
    @Volatile var activeReader = readyReader.copy(card = activeCard, badgeName = activeCard.badgeName,
        owner = QuestReaderOwner(activeCard.id, "active", running))
    @Volatile var catalogFailure: CatalogResult<Nothing>? = null
    @Volatile var readerFailure: CatalogResult<Nothing>? = null
    @Volatile var spotFailure: CatalogResult<Nothing>? = null
    @Volatile var takenFailure: CatalogResult<Nothing>? = null
    @Volatile var totalPages = 1
    @Volatile var takenRows = listOf(running)
    @Volatile var extraCards: List<QuestCatalogItem> = emptyList()
    var beforeCatalog: suspend (QuestDiscoveryFilter, Int) -> Unit = { _, _ -> }
    var beforeReader: suspend (String) -> Unit = {}
    var beforeSpot: suspend () -> Unit = {}
    var beforeTake: suspend () -> Unit = {}
    val catalogCalls = CopyOnWriteArrayList<Pair<QuestDiscoveryFilter, Int>>()
    val readerCalls = CopyOnWriteArrayList<String>()
    val detailCalls = CopyOnWriteArrayList<String>()
    val takenModes = CopyOnWriteArrayList<String?>()
    val startCalls = AtomicInteger()
    val continueCalls = AtomicInteger()
    val activityCalls = AtomicInteger()
    private var opened: QuestReader? = null

    val identity = object : TravelerIdentity by base.identity {
        override suspend fun questCatalog(filter: QuestDiscoveryFilter, page: Int): CatalogResult<QuestCatalogPage> {
            catalogCalls += filter to page; beforeCatalog(filter, page)
            catalogFailure?.let { return it }
            val rows = if (filter.participationMode == "group") emptyList() else listOf(
                QuestCatalogItem(card, "active", "available", false), QuestCatalogItem(activeCard, "active", "available", true)) + extraCards
            return CatalogResult.Ready(QuestCatalogPage(rows, page, totalPages))
        }
        override suspend fun takenQuests(mode: String?): CatalogResult<List<QuestRunning>> {
            takenModes += mode; takenFailure?.let { return it }
            return CatalogResult.Ready(takenRows.filter { mode == null || it.mode == mode })
        }
        override suspend fun quest(slug: String): CatalogResult<QuestReader> {
            readerCalls += slug; beforeReader(slug); readerFailure?.let { return it }
            return CatalogResult.Ready((if (slug == activeCard.slug) activeReader else readyReader).also { opened = it })
        }
        override suspend fun takeQuest() {
            val epoch = base.identityState.value.generation
            startCalls.incrementAndGet(); beforeTake()
            if (base.identityState.value.generation != epoch) return
            val current = requireNotNull(opened)
            base.identityState.value = base.identityState.value.copy(running = running.copy(title = current.card.title, completed = 0), ownerStatus = "active")
        }
        override suspend fun continueQuest() { continueCalls.incrementAndGet(); base.identityState.value = base.identityState.value.copy(running = opened?.owner?.participation, ownerStatus = "active") }
        override suspend fun continueQuest(id: String) { continueCalls.incrementAndGet(); base.identityState.value = base.identityState.value.copy(running = takenRows.firstOrNull { it.id == id }, ownerStatus = "active") }
        override suspend fun activity(id: String) { activityCalls.incrementAndGet() }
    }
    val catalog = object : TravelerCatalog by base.catalog {
        override suspend fun detail(item: CatalogEntry, locale: String): CatalogResult<CatalogEntry> {
            detailCalls += item.id
            if (item.kind == CatalogKind.LOCATION) return CatalogResult.Ready(location.copy(id = item.id))
            beforeSpot(); spotFailure?.let { return it }
            return CatalogResult.Ready(spot.copy(id = item.id))
        }
        override suspend fun topLocations(locale: String, perPage: Int) = CatalogResult.Ready(listOf(location))
        override suspend fun searchLocations(query: String, locale: String) = CatalogResult.Ready(listOf(location).filter { it.title.contains(query, true) })
    }
    val runtime = base.runtime.let { r -> TravelerRuntime(identity, catalog, r.itineraries, r.distribution, r.trips, r.notifications,
        r.account, r.legal, r.registration, r.publicContent, r.savedSpots, r.questGroups, r.newOperationKey, r.startup) }
}
