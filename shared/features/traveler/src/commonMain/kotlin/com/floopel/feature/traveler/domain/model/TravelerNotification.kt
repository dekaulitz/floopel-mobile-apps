package com.floopel.feature.traveler.domain.model

import kotlin.time.Instant

/** Product classification is supplied by the governed server catalog. */
enum class NotificationClass(val wireValue: String) { ACTIVITY("activity"), SYSTEM("system") }

/** A recipient-owned notice; provider delivery and list fetches do not mark it read. */
data class TravelerNotification(
    val id: String,
    val kind: String,
    val classification: NotificationClass,
    val title: String,
    val body: String,
    val targetUrl: String?,
    val occurredAt: Instant,
    val createdAt: Instant,
    val readAt: Instant?,
)

/** API pages are transport batches for automatic append, never UI page controls. */
data class NotificationPage(
    val items: List<TravelerNotification>,
    val page: Int,
    val perPage: Int,
    val total: Int,
    val totalPages: Int,
)

/** The server's binding ID is distinct from the installation ID supplied on registration. */
data class PushDeviceBinding(val id: String, val installationId: String, val platform: String)
