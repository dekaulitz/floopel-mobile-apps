package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.CatalogResult
import com.floopel.feature.traveler.domain.model.NotificationClass
import com.floopel.feature.traveler.domain.model.NotificationPage
import com.floopel.feature.traveler.domain.model.PushDeviceBinding
import com.floopel.feature.traveler.domain.model.TravelerNotification
import kotlin.time.Instant

/** One authenticated inbox shared across devices; all reads are side-effect free. */
interface TravelerNotificationRepository {
    /** Reads one bounded batch in the active UI locale without acknowledging items. */
    suspend fun notifications(page: Int, classification: NotificationClass?, locale: String): CatalogResult<NotificationPage>
    /** Loads only a notice owned by the active session, including before push navigation. */
    suspend fun notification(id: String, locale: String): CatalogResult<TravelerNotification>
    /** Acknowledges a successfully loaded/opened notice; retries retain the first timestamp. */
    suspend fun markNotificationRead(id: String): CatalogResult<Instant>
    /** Reads the shared unread count without inferring it from loaded pages. */
    suspend fun unreadNotifications(): CatalogResult<Int>
}

/** Platform lifecycle owns registration/rotation, without exposing tokens to presentation. */
interface TravelerPushRegistration {
    /** Registers a provider token under the current signed native session. */
    suspend fun registerPushDevice(installationId: String, platform: String, token: String): CatalogResult<PushDeviceBinding>
    /** Disables the returned binding ID before disposing the current session. */
    suspend fun disablePushDevice(bindingId: String): CatalogResult<Unit>
}
