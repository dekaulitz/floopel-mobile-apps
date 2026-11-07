package com.floopel.feature.traveler.data.dto

/** Wire values remain strings until the Data mapper validates domain identifiers and timestamps. */
internal data class TravelerNotificationDto(
    val id: String,
    val kind: String,
    val classification: String,
    val title: String,
    val body: String,
    val targetUrl: String?,
    val occurredAt: String,
    val createdAt: String,
    val readAt: String?,
)

/** Pagination belongs to HTTP metadata and is mapped to internal infinite-scroll batches. */
internal data class NotificationPaginationDto(
    val page: Int,
    val perPage: Int,
    val total: Int,
    val totalPages: Int,
)

/** The explicit read mutation returns the owned notice identity and first read time. */
internal data class NotificationReadDto(val notificationId: String, val readAt: String)

/** A server-owned count is independent of the number of loaded notification rows. */
internal data class NotificationUnreadCountDto(val count: Int)

/** Device response values exclude the provider token and preserve nullable disabled state. */
internal data class PushDeviceBindingDto(
    val id: String,
    val installationId: String,
    val platform: String,
    val disabledAt: String?,
)

/** Write-only token input never acquires the default data-class string representation. */
internal class PushDeviceRegistrationDto(val platform: String, val token: String) {
    /** Prevents incidental debugging from exposing the provider credential. */
    override fun toString(): String = "PushDeviceRegistrationDto([REDACTED])"
}
