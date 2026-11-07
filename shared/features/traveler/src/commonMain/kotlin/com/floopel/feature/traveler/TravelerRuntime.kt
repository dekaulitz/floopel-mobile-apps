package com.floopel.feature.traveler

import com.floopel.feature.traveler.data.remote.NativeApi
import com.floopel.feature.traveler.data.repository.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.domain.model.CatalogResult
import com.floopel.feature.traveler.feature.auth.TravelerSession

/** Explicit composition root. Screens consume domain contracts, never HTTP clients or token storage. */
class TravelerRuntime(
    val identity: TravelerIdentity,
    val catalog: TravelerCatalog,
    val itineraries: TravelerItineraryRepository,
    val distribution: TravelerItineraryDistributionRepository,
    val trips: TravelerTripRepository,
    val notifications: TravelerNotificationRepository,
    val account: TravelerAccountRepository,
    val legal: TravelerLegalRepository,
    val registration: TravelerRegistrationRepository,
    val publicContent: TravelerPublicRepository,
    val savedSpots: TravelerSavedSpotRepository,
    val questGroups: TravelerQuestGroupRepository,
    val newOperationKey: () -> String,
    val startup: TravelerStartupRepository,
    val close: () -> Unit = {},
    val comments: TravelerCommentRepository? = null,
    val settings: TravelerSettingsRepository? = null,
)

/** Hosts own platform services and close the runtime once their retained owner is cleared. */
internal fun travelerRuntime(api: NativeApi, refreshStore: RefreshCredentialStore,
    itineraryStore: ItineraryRecoveryStore, tripStore: TripRecoveryStore, distributionStore: TripRecoveryStore, questGroupStore: TripRecoveryStore,
    newOperationKey: () -> String, attestation: QuestAttestation? = null,
    location: QuestLocationSource? = null, mediaStore: TripRecoveryStore, creationRouteStore: TripRecoveryStore,
    startup: TravelerStartupRepository): TravelerRuntime {
    val session = TravelerSession(api, refreshStore, "id", attestation = attestation, locationSource = location,
        onLogout = { itineraryStore.clear(); tripStore.clear(); distributionStore.clear(); questGroupStore.clear(); mediaStore.clear(); creationRouteStore.clear() })
    val rest = api.catalogTransport()
    val legal = NativeLegalApi(rest, session)
    val catalog = AppsTravelerCatalog(rest)
    val media = NativeMediaUploads(rest, session, mediaStore)
    // The screen preflights consent and opens Legal when needed. Recheck immediately before
    // the POST so a newly published document fails closed instead of waiting on an unwired dialog.
    val commentConsent = object : TravelerConsentGate {
        override suspend fun ensure(): CatalogResult<Unit> {
            return when (val result = legal.pending("id")) {
                is CatalogResult.Ready -> if (result.value.isEmpty()) CatalogResult.Ready(Unit) else CatalogResult.HttpError(409, code = "LEGAL_CONSENT_REQUIRED")
                is CatalogResult.HttpError -> result
                is CatalogResult.Maintenance -> result
                CatalogResult.NetworkError -> CatalogResult.NetworkError
                CatalogResult.InvalidResponse -> CatalogResult.InvalidResponse
            }
        }
    }
    return TravelerRuntime(NativeTravelerIdentity(session, api), NativeTravelerCatalog(catalog, rest, session),
        NativeItineraryApi(rest, session, itineraryStore, newOperationKey),
        NativeItineraryDistributionApi(rest, session, distributionStore, mediaUploads = media),
        NativeTripApi(rest, session, tripStore, mediaUploads = media, creationRoutes = creationRouteStore),
        NativeNotificationApi(rest, session), NativeAccountApi(rest, session), legal,
        NativeRegistrationApi(api), NativePublicTravelerApi(rest, session), NativeSavedSpotApi(rest, session, catalog),
        NativeQuestGroupApi(rest, session, questGroupStore), newOperationKey, startup, api::close,
        comments = NativeCommentApi(rest, session, commentConsent), settings = NativeSettingsApi(rest, session))
}
