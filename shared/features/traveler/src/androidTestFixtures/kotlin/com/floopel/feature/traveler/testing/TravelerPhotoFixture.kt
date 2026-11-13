package com.floopel.feature.traveler.testing

import com.floopel.feature.traveler.TravelerRuntime
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import java.util.concurrent.atomic.AtomicInteger

/** Explicit test-only photo boundary; no provider, credential or local account is changed. */
class TravelerPhotoFixture {
    val base = TravelerFixture()
    val uploadId = "00000000-0000-4000-8000-000000000901"
    val uploadCalls = AtomicInteger()
    val statusCalls = AtomicInteger()
    val profileCalls = AtomicInteger()
    val metadataCalls = AtomicInteger()
    @Volatile var profile = PersonalProfile("Traveler", "Simulasi", "traveler_simulasi", "ID", profileId = "fixture-person")
    @Volatile var nextAvatar: String? = null
    @Volatile var profileFailure: CatalogResult<PersonalProfile>? = null
    @Volatile var uploadResult: CatalogResult<ProfilePhotoUpload> = CatalogResult.Ready(ProfilePhotoUpload(uploadId, ProfilePhotoStatus.PROCESSING))
    @Volatile var statusResult: CatalogResult<ProfilePhotoUpload> = CatalogResult.Ready(ProfilePhotoUpload(uploadId, ProfilePhotoStatus.PROCESSING))
    @Volatile var pendingDocuments: List<LegalDocument> = emptyList()
    @Volatile var legalFailure: CatalogResult<List<LegalDocument>>? = null
    var beforeUpload: suspend () -> Unit = {}
    var beforeStatus: suspend () -> Unit = {}
    var beforeProfile: suspend () -> Unit = {}
    var beforeLegal: suspend () -> Unit = {}
    @Volatile var received: ProfilePhotoImage? = null
    val photos = object : TravelerProfilePhotoRepository {
        override suspend fun uploadProfilePhoto(image: ProfilePhotoImage): CatalogResult<ProfilePhotoUpload> {
            uploadCalls.incrementAndGet(); received = image; beforeUpload(); return uploadResult
        }
        override suspend fun profilePhotoUpload(id: String): CatalogResult<ProfilePhotoUpload> {
            check(id == uploadId); statusCalls.incrementAndGet(); beforeStatus()
            val result = statusResult
            if (result is CatalogResult.Ready && result.value.status == ProfilePhotoStatus.READY)
                profile = profile.copy(avatarUrl = nextAvatar)
            return result
        }
    }
    val account = object : TravelerAccountRepository by base.runtime.account {
        override suspend fun profile(): CatalogResult<PersonalProfile> {
            profileCalls.incrementAndGet(); beforeProfile(); return profileFailure ?: CatalogResult.Ready(profile)
        }
        override suspend fun updateProfile(previous: PersonalProfile, changed: PersonalProfile): CatalogResult<PersonalProfile> {
            metadataCalls.incrementAndGet(); profile = changed.copy(avatarUrl = profile.avatarUrl); return CatalogResult.Ready(profile)
        }
    }
    val legal = object : TravelerLegalRepository by base.runtime.legal {
        override suspend fun pending(locale: String): CatalogResult<List<LegalDocument>> {
            beforeLegal(); return legalFailure ?: CatalogResult.Ready(pendingDocuments)
        }
    }
    val runtime = base.runtime.let { r -> TravelerRuntime(r.identity, r.catalog, r.itineraries, r.distribution, r.trips,
        r.notifications, account, legal, r.registration, r.publicContent, r.savedSpots, r.questGroups, r.newOperationKey,
        r.startup, profilePhotos = photos) }
}
