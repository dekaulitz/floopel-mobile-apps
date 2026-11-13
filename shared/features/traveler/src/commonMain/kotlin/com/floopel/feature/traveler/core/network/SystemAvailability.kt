package com.floopel.feature.traveler.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlin.time.TimeSource
import com.floopel.feature.traveler.domain.model.SystemMaintenanceNotice

/** Shared gateway availability; generic service errors never activate system mode. */
internal class SystemAvailability(private val nowMillis: () -> Long = monotonicClock()) {
    private val mutableMaintenance = MutableStateFlow<SystemMaintenanceNotice?>(null)
    val maintenance = mutableMaintenance.asStateFlow()
    private val revision = MutableStateFlow(0L)

    internal fun observe(status: Int, marker: String?, retryAfter: Long?) {
        if (!(status == 503 && marker == "system") && !(maintenance.value != null && status in listOf(429, 503))) return
        val delay = retryAfter?.takeIf { it in 1..3600 } ?: 60L
        val nextRevision = revision.updateAndGet { it + 1 }
        val deadline = nowMillis() + delay * 1000
        mutableMaintenance.update { previous -> SystemMaintenanceNotice(maxOf(previous?.revision ?: 0, nextRevision),
            maxOf(previous?.retryAtMillis ?: 0, deadline)) }
    }
    fun retrySecondsRemaining(): Long =
        maintenance.value?.let { ((it.retryAtMillis - nowMillis()).coerceAtLeast(0) + 999) / 1000 } ?: 0

    internal fun recover(expectedRevision: Long) {
        val observed = maintenance.value
        if (observed?.revision == expectedRevision) mutableMaintenance.compareAndSet(observed, null)
    }
}

private fun monotonicClock(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}
