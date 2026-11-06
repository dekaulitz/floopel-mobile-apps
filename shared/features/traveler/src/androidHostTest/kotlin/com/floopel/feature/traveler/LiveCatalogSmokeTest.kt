package com.floopel.feature.traveler

import com.floopel.feature.traveler.core.network.androidNativeApi
import com.floopel.feature.traveler.data.repository.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.RefreshCredentialStore
import com.floopel.feature.traveler.feature.auth.TravelerSession
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Opt-in public reads only. Run with -PfloopelLiveSmoke=true -PfloopelApiOrigin=https://... */
class LiveCatalogSmokeTest {
    private fun api() = androidNativeApi(requireNotNull(System.getProperty("floopel.test.origin")), "0.1.0")

    @Test fun spotDiscoveryDetailAndSearchMapActualEnvelopes() = runBlocking {
        val api = api()
        try {
            val catalog = AppsTravelerCatalog(api.catalogTransport())
            val spots = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(catalog.discoverySpots("id")).value
            assertTrue(spots.isNotEmpty(), "This environment needs one public Spot to exercise detail")
            val detail = assertIs<CatalogResult.Ready<CatalogEntry>>(catalog.detail(spots.first(), "id")).value
            assertEquals(spots.first().id, detail.id); assertTrue(detail.detailLoaded)
            val results = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(catalog.search(spots.first().title.take(30), "id")).value
            assertTrue(results.any { it.id == detail.id })
            assertTrue(spots.all { it.aiReference?.score?.isFinite() != false })
        } finally { api.close() }
    }

    @Test fun locationUuidAndSlugReadersRetainStableIdentity() = runBlocking {
        val api = api()
        try {
            val catalog = AppsTravelerCatalog(api.catalogTransport())
            val locations = assertIs<CatalogResult.Ready<List<CatalogEntry>>>(catalog.regions("id")).value
            assertTrue(locations.isNotEmpty())
            val region = locations.first()
            val uuid = assertIs<CatalogResult.Ready<CatalogEntry>>(catalog.detail(region, "id")).value
            val slug = assertIs<CatalogResult.Ready<CatalogEntry>>(catalog.detail(region.copy(id = region.slug), "id")).value
            assertEquals(uuid.id, slug.id); assertEquals(uuid.title, slug.title)
        } finally { api.close() }
    }

    @Test fun publicQuestAndOpenTripReadersDoNotRequireAnIdentity() = runBlocking {
        val api = api()
        try {
            val catalog = AppsTravelerCatalog(api.catalogTransport())
            val quests = assertIs<CatalogResult.Ready<LocationQuestPage>>(catalog.activeQuests("id", 1)).value
            assertTrue(quests.items.isNotEmpty())
            val detail = api.quest(quests.items.first().slug, "id")
            assertEquals(quests.items.first().id, detail.id)
            val session = TravelerSession(api, object : RefreshCredentialStore {
                override fun read(): String? = null
                override fun write(credential: String) = error("Public reads must not issue credentials")
                override fun clear() = Unit
            }, "id")
            val openTrips = assertIs<CatalogResult.Ready<OpenTripPage>>(NativeTripApi(api.catalogTransport(), session).openTrips(OpenTripQuery())).value
            openTrips.items.forEach { assertEquals(5, it.participantLimit); assertEquals(1, it.organizerCount) }
            assertFalse(session.view.value.signedIn)
        } finally { api.close() }
    }

    @Test fun registrationReferencesUseNativeNationalitiesAndPublishedLegalVersions() = runBlocking {
        val api = api()
        try {
            val result = assertIs<RegistrationResult.Ready<RegistrationReferences>>(NativeRegistrationApi(api).references("id")).value
            assertTrue(result.nationalities.isNotEmpty())
            assertTrue(result.terms.hash.isNotBlank()); assertTrue(result.privacy.hash.isNotBlank())
            assertEquals(result.terms.locale, result.privacy.locale)
        } finally { api.close() }
    }
}
