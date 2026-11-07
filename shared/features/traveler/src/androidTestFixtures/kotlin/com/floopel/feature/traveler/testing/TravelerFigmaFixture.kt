package com.floopel.feature.traveler.testing

import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant

/** Synthetic domain data for layout/state regression tests. Never included in a production APK. */
class TravelerFigmaFixture(photoUrls: List<String>? = null) {
    val base = TravelerFixture()
    val profile = PersonalProfile("Traveler", "Simulasi", "traveler_simulasi", "ID")
    val location = CatalogEntry("00000000-0000-4000-8000-000000000101", "magelang-simulasi", "Magelang", null, CatalogKind.LOCATION, null,
        locationLevel = "city", ancestors = listOf(LocationAncestor("province", "Jawa Tengah", "region"), LocationAncestor("country", "Indonesia", "country")))
    val spot = base.spot.copy(category = "Warisan", aiReference = AiReferenceRating(4.8, false), commentCount = 1_200,
        locationId = location.id, coverUrl = if (photoUrls != null) photoUrls.firstOrNull() else "file:///floopel-synthetic-fixture/cover.jpg",
        photos = photoUrls?.map { CatalogPhoto(it, "Foto simulasi") } ?: listOf(CatalogPhoto("file:///floopel-synthetic-fixture/cover.jpg", "Foto simulasi"), CatalogPhoto("file:///floopel-synthetic-fixture/second.jpg", "Foto kedua simulasi")))
    val secondSpot = spot.copy(id = "00000000-0000-4000-8000-000000000102", slug = "spot-kedua", title = "Spot kedua simulasi", category = "Alam", commentCount = 1_000)
    val article = CatalogEntry("article", "artikel-simulasi", "Artikel simulasi", "Cerita untuk pengujian UI.", CatalogKind.STORY, null)
    val collection = CatalogEntry("collection", "collection-simulasi", "Collection simulasi", "Magelang", CatalogKind.COLLECTION, null)
    val quest = LocationQuest("quest", "quest-simulasi", "Jejak budaya simulasi", 200, locations = listOf(LocationReference(location.id, location.title, location.slug, "city")), activityCount = 4)
    val unread = TravelerNotification(base.inboxId, "trip.invitation_received", NotificationClass.ACTIVITY,
        "Undangan perjalanan simulasi", "Kamu menerima undangan untuk bergabung dalam perjalanan.", "/me/trip-notices/${base.noticeId}",
        Instant.parse("2026-11-07T02:10:00Z"), Instant.parse("2026-11-07T02:10:00Z"), null)
    val read = unread.copy(id = "00000000-0000-4000-8000-000000000103", kind = "system.update", classification = NotificationClass.SYSTEM,
        title = "Pembaruan sistem simulasi", readAt = Instant.parse("2026-11-07T03:00:00Z"), targetUrl = null)
    val comment = TravelerComment("comment", "arif_simulasi", "Komentar untuk pengujian UI.", CommentVisibility.VISIBLE, 3, false, "2026-11-07T02:00:00Z", null, 1, emptyList())
    val reply = comment.copy(id = "reply", username = profile.username, body = "Balasan simulasi.", parentId = comment.id, replyCount = 0)
    init {
        base.identityState.value = base.identityState.value.copy(passport = TravelerPassport(1250, 8, "Penjelajah",
            listOf("Badge simulasi" to null), emptyList(), 1000, 750))
    }

    @Volatile var notificationResult: CatalogResult<NotificationPage> = CatalogResult.Ready(NotificationPage(listOf(unread, read), 1, 20, 2, 1))
    @Volatile var nextNotificationResult: CatalogResult<NotificationPage> = CatalogResult.Ready(NotificationPage(emptyList(), 2, 20, 2, 2))
    @Volatile var lookupResult: CatalogResult<TravelerNotification> = CatalogResult.Ready(unread)
    @Volatile var profileResult: CatalogResult<PersonalProfile> = CatalogResult.Ready(profile)
    @Volatile var commentResult: CatalogResult<TravelerComment> = CatalogResult.Ready(comment.copy(id = "created"))
    @Volatile var preferences = TravelerSettings(true, false, true, true)
    @Volatile var commentThrows = false
    @Volatile var beforeLookup: suspend () -> Unit = {}
    @Volatile var beforeProfile: suspend () -> Unit = {}
    @Volatile var beforePublicProfile: suspend () -> Unit = {}
    @Volatile var beforePublicProgress: suspend () -> Unit = {}
    @Volatile var beforeBadge: suspend () -> Unit = {}
    @Volatile var beforeLocation: suspend () -> Unit = {}
    @Volatile var beforeCreateComment: suspend () -> Unit = {}
    val reads = CopyOnWriteArrayList<String>()
    val tripQueries = CopyOnWriteArrayList<OpenTripQuery>()
    val commentCreates = AtomicInteger()
    val savedWrites = AtomicInteger()
    val profileWrites = AtomicInteger()
    val interestWrites = AtomicInteger()
    val preferencesWrites = AtomicInteger()
    @Volatile var savedPreferenceInput: TravelerSettings? = null
    @Volatile var saved = true

    private val catalog = object : TravelerCatalog by base.runtime.catalog {
        override suspend fun spots(locale: String, locationId: String?, perPage: Int) = CatalogResult.Ready(listOf(spot, secondSpot))
        override suspend fun discoverySpots(locale: String, center: GeoPoint?, locationId: String?) = CatalogResult.Ready(listOf(spot, secondSpot))
        override suspend fun topLocations(locale: String, perPage: Int) = CatalogResult.Ready(listOf(location))
        override suspend fun detail(item: CatalogEntry, locale: String): CatalogResult<CatalogEntry> {
            if (item.kind == CatalogKind.LOCATION) { beforeLocation(); return CatalogResult.Ready(location.copy(id = item.id)) }
            return CatalogResult.Ready((if (item.id == secondSpot.id) secondSpot else spot).copy(related = listOf(secondSpot), collections = listOf(collection),
                locationName = "Magelang · Jawa Tengah · Indonesia"))
        }
        override suspend fun browse(kind: CatalogKind, locale: String, page: Int, storyType: StoryType?, locationId: String?, parentId: String?): CatalogResult<CatalogPage> {
            reads += "browse:$kind:$locationId:$page"
            return CatalogResult.Ready(CatalogPage(if (locationId == "empty-location") emptyList() else listOf(spot, secondSpot), page, 1, if (locationId == "empty-location") 0 else 2))
        }
        override suspend fun searchSpotPage(query: String, locationId: String?, locale: String, page: Int): CatalogResult<CatalogPage> {
            reads += "search:$query:$locationId:$page"
            return CatalogResult.Ready(CatalogPage(emptyList(), page, 0, 0))
        }
        override suspend fun spotArticles(spotId: String, locale: String, page: Int): CatalogResult<CatalogPage> { reads += "articles:$spotId"; return CatalogResult.Ready(CatalogPage(listOf(article), 1, 1)) }
        override suspend fun spotQuests(spotId: String, locale: String, page: Int): CatalogResult<LocationQuestPage> { reads += "quests:$spotId"; return CatalogResult.Ready(LocationQuestPage(listOf(quest), 1, 1)) }
    }
    private val notifications = object : TravelerNotificationRepository by base.notifications {
        override suspend fun notifications(page: Int, classification: NotificationClass?, locale: String): CatalogResult<NotificationPage> {
            reads += "inbox:$page:${classification?.wireValue}"
            val result = if (page > 1) nextNotificationResult else notificationResult
            return if (result is CatalogResult.Ready && classification != null) CatalogResult.Ready(result.value.copy(items = result.value.items.filter { it.classification == classification })) else result
        }
        override suspend fun notification(id: String, locale: String): CatalogResult<TravelerNotification> { beforeLookup(); return lookupResult }
    }
    private val account = object : TravelerAccountRepository by base.runtime.account {
        override suspend fun profile(): CatalogResult<PersonalProfile> { beforeProfile(); return profileResult }
        override suspend fun nationalities(locale: String) = CatalogResult.Ready(listOf(NationalityOption("ID", "Indonesia"), NationalityOption("MY", "Malaysia")))
        override suspend fun updateProfile(previous: PersonalProfile, changed: PersonalProfile): CatalogResult<PersonalProfile> { profileWrites.incrementAndGet(); profileResult = CatalogResult.Ready(changed); return CatalogResult.Ready(changed) }
        override suspend fun interests(locale: String) = CatalogResult.Ready(TravelerInterests((1..4).map { InterestOption("interest-$it", "Minat simulasi $it") }, setOf("interest-1", "interest-2", "interest-3")))
        override suspend fun saveInterests(selected: Set<String>): CatalogResult<Set<String>> { interestWrites.incrementAndGet(); return CatalogResult.Ready(selected) }
        override suspend fun emergencyContact() = CatalogResult.Ready(null)
        override suspend fun security() = CatalogResult.Ready(CredentialCapabilities(false, true))
    }
    private val publicContent = object : TravelerPublicRepository by base.runtime.publicContent {
        override suspend fun profile(username: String): CatalogResult<TravelerPublicProfile> { beforePublicProfile(); return CatalogResult.Ready(TravelerPublicProfile(username, if (username == profile.username) "Traveler Simulasi" else "Arif Simulasi", null, null, 2026, 1, null, null, null)) }
        override suspend fun progress(username: String): CatalogResult<PublicProfileProgress> { beforePublicProgress(); return CatalogResult.Ready(PublicProfileProgress(8, "Penjelajah", 1250)) }
        override suspend fun profileItineraries(username: String): CatalogResult<List<PublicItinerarySummary>> { reads += "public-plans:$username"; return CatalogResult.Ready(listOf(base.publicPlan)) }
        override suspend fun badges(username: String, locale: String, page: Int): CatalogResult<PublicAchievementPage<PublicProfileBadge>> {
            beforeBadge(); reads += "badges:$username:$page"
            return CatalogResult.Ready(PublicAchievementPage((1..3).map { PublicProfileBadge("badge-$it", "badge-$it", "Badge simulasi $it", CatalogPhoto("https://fixture.invalid/badge.png", "Badge")) }, 1, 3, 1))
        }
    }
    private val itineraries = object : TravelerItineraryRepository by base.runtime.itineraries {
        override suspend fun mine(): CatalogResult<List<OwnedItinerary>> { reads += "private-plans"; return CatalogResult.Ready(listOf(base.plan.itinerary, base.plan.itinerary.copy(id = "public", visibility = "public"))) }
    }
    private val savedSpots = object : TravelerSavedSpotRepository {
        override suspend fun savedSpots(page: Int): CatalogResult<SavedSpotPage> { reads += "saved-spots:$page"; return CatalogResult.Ready(SavedSpotPage(if (saved) listOf(spot) else emptyList(), page, 1)) }
        override suspend fun isSaved(spotId: String) = CatalogResult.Ready(saved)
        override suspend fun setSaved(spotId: String, saved: Boolean): CatalogResult<Boolean> { savedWrites.incrementAndGet(); this@TravelerFigmaFixture.saved = saved; return CatalogResult.Ready(saved) }
    }
    private val comments = object : TravelerCommentRepository {
        override suspend fun page(target: CommentTarget, id: String, cursor: String?, parentId: String?): CatalogResult<CommentPage> { reads += "comments:$id:$parentId"; return CatalogResult.Ready(CommentPage(listOf(if (parentId == null) comment else reply), null)) }
        override suspend fun total(target: CommentTarget, id: String) = CatalogResult.Ready(1201L)
        override suspend fun create(target: CommentTarget, id: String, body: String, parentId: String?): CatalogResult<TravelerComment> {
            commentCreates.incrementAndGet(); beforeCreateComment(); if (commentThrows) error("Synthetic network interruption")
            return commentResult
        }
        override suspend fun like(id: String, liked: Boolean, key: String) = CatalogResult.Ready(CommentLike(id, 4, liked))
    }
    private val trips = object : TravelerTripRepository by base.runtime.trips {
        override suspend fun openTrips(query: OpenTripQuery): CatalogResult<OpenTripPage> { tripQueries += query; return CatalogResult.Ready(OpenTripPage(listOf(base.openTrip), null)) }
    }
    private val settings = object : TravelerSettingsRepository {
        override suspend fun load() = CatalogResult.Ready(preferences)
        override suspend fun save(settings: TravelerSettings): CatalogResult<TravelerSettings> { preferencesWrites.incrementAndGet(); savedPreferenceInput = settings; preferences = settings; return CatalogResult.Ready(settings) }
    }
    val runtime = base.runtime.let { r -> TravelerRuntime(r.identity, catalog, itineraries, r.distribution, trips, notifications, account, r.legal,
        r.registration, publicContent, savedSpots, r.questGroups, r.newOperationKey, r.startup, comments = comments, settings = settings) }
}
