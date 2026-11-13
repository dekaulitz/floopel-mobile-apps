package com.floopel.traveler.platform.storage

import android.content.Context
import com.floopel.feature.traveler.domain.repository.TripRecoveryStore

/** Separate encrypted Trip intent namespace; shares the existing AES-GCM storage implementation. */
class AndroidTripRecoveryStore(context: Context, storageName: String = "trip_recovery") : TripRecoveryStore {
    private val encrypted = AndroidItineraryRecoveryStore(context, storageName)
    override fun read() = encrypted.read()
    override fun write(record: String) = encrypted.write(record)
    override fun clear() = encrypted.clear()
}
