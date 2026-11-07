package com.floopel.traveler.platform.location

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Instant

class AndroidQuestLocation(private val activity: ComponentActivity) : QuestLocationSource {
    private val manager = activity.getSystemService(LocationManager::class.java)
    private val mutex = Mutex()
    private var permission: CancellableContinuation<Boolean>? = null
    private var pendingFix: CancellableContinuation<QuestLocation>? = null
    // ComponentActivity 1.12.4 owns this launcher. Resolved Fragment is 1.5.7 (>1.3.0);
    // the Activity lint detector incorrectly flags this indirect receiver.
    @android.annotation.SuppressLint("InvalidFragmentVersionForActivityResult")
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        permission?.let { if (it.isActive) it.resume(grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) }
        permission = null
    }
    init {
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) pendingFix?.cancel()
        })
    }
    override suspend fun current(): QuestLocation = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) throw QuestLocationUnavailable()
            if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                val granted = suspendCancellableCoroutine { continuation ->
                    permission = continuation
                    continuation.invokeOnCancellation { permission = null }
                    launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
                if (!granted) throw QuestLocationUnavailable()
            }
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) throw QuestLocationUnavailable()
            try { withTimeout(30_000) { freshFix() } }
            catch (_: TimeoutCancellationException) { throw QuestLocationUnavailable() }
        }
    }
    @Suppress("DEPRECATION", "MissingPermission")
    private suspend fun freshFix(): QuestLocation = suspendCancellableCoroutine { continuation ->
        val started = SystemClock.elapsedRealtimeNanos()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!continuation.isActive || location.elapsedRealtimeNanos < started) return
                manager.removeUpdates(this)
                pendingFix = null
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || location.isFromMockProvider || !location.hasAccuracy()) {
                    continuation.resumeWithException(QuestLocationUnavailable()); return
                }
                continuation.resume(QuestLocation(location.latitude, location.longitude, location.accuracy.toDouble(), Instant.fromEpochMilliseconds(location.time).toString()))
            }
            override fun onProviderDisabled(provider: String) { manager.removeUpdates(this); if (continuation.isActive) continuation.resumeWithException(QuestLocationUnavailable()) }
            override fun onProviderEnabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        pendingFix = continuation
        continuation.invokeOnCancellation { manager.removeUpdates(listener); pendingFix = null }
        try { manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper()) }
        catch (_: Exception) { manager.removeUpdates(listener); pendingFix = null; if (continuation.isActive) continuation.resumeWithException(QuestLocationUnavailable()) }
    }
}
