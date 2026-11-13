package com.floopel.traveler

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.floopel.feature.traveler.domain.repository.QuestLocationSource
import com.floopel.feature.traveler.domain.model.QuestLocation
import com.floopel.feature.traveler.domain.model.QuestLocationUnavailable
import com.floopel.feature.traveler.androidTravelerRuntime
import com.floopel.traveler.platform.attestation.AndroidQuestAttestation
import com.floopel.traveler.platform.storage.*
import java.security.MessageDigest

/** Rebinds permission/lifecycle adapters after configuration changes without retaining an Activity. */
class QuestLocationBridge : QuestLocationSource {
    var delegate: QuestLocationSource? = null
    override suspend fun current(): QuestLocation = delegate?.current() ?: throw QuestLocationUnavailable()
}

class TravelerRuntimeOwner(context: Context, runtimeScope: String? = null, startupReadDelayMillis: Long = 0) : ViewModel() {
    init { require(runtimeScope == null || BuildConfig.DEBUG && Regex("qa-[a-z0-9-]{8,64}").matches(runtimeScope)) }
    val location = QuestLocationBridge()
    // The default digest stays byte-compatible with existing encrypted sessions and journals.
    private val storageIdentity = BuildConfig.API_ORIGIN + runtimeScope?.let { "|$it" }.orEmpty()
    private val scope = MessageDigest.getInstance("SHA-256").digest(storageIdentity.toByteArray())
        .take(8).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    val runtime = androidTravelerRuntime(BuildConfig.API_ORIGIN, BuildConfig.VERSION_NAME,
        AndroidRefreshCredentialStore(context.applicationContext, scope),
        AndroidItineraryRecoveryStore(context.applicationContext, "itinerary_$scope"),
        AndroidTripRecoveryStore(context.applicationContext, "trip_$scope"),
        AndroidTripRecoveryStore(context.applicationContext, "distribution_$scope"),
        AndroidTripRecoveryStore(context.applicationContext, "quest_group_$scope"),
        ::newItineraryOperationKey,
        AndroidQuestAttestation(context.applicationContext, BuildConfig.PLAY_CLOUD_PROJECT_NUMBER), location,
        AndroidTripRecoveryStore(context.applicationContext, "media_$scope"),
        AndroidTripRecoveryStore(context.applicationContext, "trip_creation_routes_$scope"),
        AndroidTravelerStartupPreferences(context.applicationContext, runtimeScope, startupReadDelayMillis))
    override fun onCleared() { location.delegate = null; runtime.close() }

    class Factory(private val context: Context, private val runtimeScope: String? = null,
        private val startupReadDelayMillis: Long = 0) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == TravelerRuntimeOwner::class.java)
            return modelClass.cast(TravelerRuntimeOwner(context, runtimeScope, startupReadDelayMillis))
        }
    }
}
