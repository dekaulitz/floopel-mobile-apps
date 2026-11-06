package com.floopel.feature.traveler

import com.floopel.feature.traveler.core.network.androidNativeApi
import com.floopel.feature.traveler.domain.repository.*

/** The host supplies native services without receiving a transport client, DTO or credential. */
fun androidTravelerRuntime(origin: String, appVersion: String, refreshStore: RefreshCredentialStore,
    itineraryStore: ItineraryRecoveryStore, tripStore: TripRecoveryStore, distributionStore: TripRecoveryStore, questGroupStore: TripRecoveryStore,
    newOperationKey: () -> String, attestation: QuestAttestation? = null, location: QuestLocationSource? = null,
    mediaStore: TripRecoveryStore): TravelerRuntime =
    travelerRuntime(androidNativeApi(origin, appVersion), refreshStore, itineraryStore, tripStore,
        distributionStore, questGroupStore, newOperationKey, attestation, location, mediaStore)
