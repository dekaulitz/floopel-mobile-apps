package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.data.dto.NotificationPaginationDto
import com.floopel.feature.traveler.data.dto.NotificationReadDto
import com.floopel.feature.traveler.data.dto.NotificationUnreadCountDto
import com.floopel.feature.traveler.data.dto.PushDeviceBindingDto
import com.floopel.feature.traveler.data.dto.PushDeviceRegistrationDto
import com.floopel.feature.traveler.data.dto.TravelerNotificationDto
import com.floopel.feature.traveler.domain.model.NotificationClass
import com.floopel.feature.traveler.domain.model.NotificationPage
import com.floopel.feature.traveler.domain.model.PushDeviceBinding
import com.floopel.feature.traveler.domain.model.TravelerNotification
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Instant

/** Reads required wire fields without coercing JSON numbers into strings. */
internal fun decodeNotification(data: JsonObject): TravelerNotificationDto = TravelerNotificationDto(
    id = data.wireText("id"),
    kind = data.wireText("kind"),
    classification = data.wireText("class"),
    title = data.wireText("title"),
    body = data.wireText("body"),
    targetUrl = data.wireNullableText("target_url"),
    occurredAt = data.wireText("occurred_at"),
    createdAt = data.wireText("created_at"),
    readAt = data.wireNullableText("read_at"),
)

/** Maps Data strings into domain values; transport DTOs never become UI/domain aliases. */
internal fun TravelerNotificationDto.toDomain(): TravelerNotification = TravelerNotification(
    id = id.also { require(validNotificationId(it)) },
    kind = kind.also { require(it.isNotBlank()) },
    classification = NotificationClass.entries.single { it.wireValue == classification },
    title = title,
    body = body,
    targetUrl = targetUrl,
    occurredAt = Instant.parse(occurredAt),
    createdAt = Instant.parse(createdAt),
    readAt = readAt?.let(Instant::parse),
)

/** Reads canonical pagination metadata into its Data-owned definition. */
internal fun decodeNotificationPagination(data: JsonObject): NotificationPaginationDto = NotificationPaginationDto(
    page = data.wireNumber("page"),
    perPage = data.wireNumber("per_page"),
    total = data.wireNumber("total"),
    totalPages = data.wireNumber("total_pages"),
)

/** Enforces the requested batch and canonical total_pages arithmetic before exposing domain data. */
internal fun NotificationPaginationDto.toDomainPage(
    items: List<TravelerNotificationDto>,
    requestedPage: Int,
    batchSize: Int,
): NotificationPage {
    require(page == requestedPage && perPage == batchSize)
    require(total >= 0 && totalPages >= 0)
    require(totalPages.toLong() == (total.toLong() + perPage - 1) / perPage)
    require(items.size <= perPage)
    return NotificationPage(items.map { it.toDomain() }, page, perPage, total, totalPages)
}

/** Reads only the mutation response; fetching a notice does not call this operation. */
internal fun decodeNotificationRead(data: JsonObject): NotificationReadDto = NotificationReadDto(
    notificationId = data.wireText("notification_id"),
    readAt = data.wireText("read_at"),
)

/** Converts the first-read timestamp after checking the response belongs to the requested notice. */
internal fun NotificationReadDto.toDomainTimestamp(requestedId: String): Instant {
    require(notificationId == requestedId)
    return Instant.parse(readAt)
}

/** Keeps the count wire contract separate from domain/presentation state. */
internal fun decodeNotificationCount(data: JsonObject): NotificationUnreadCountDto =
    NotificationUnreadCountDto(data.wireNumber("count"))

/** Rejects negative authoritative counts rather than inferring one from loaded rows. */
internal fun NotificationUnreadCountDto.toDomainCount(): Int = count.also { require(it >= 0) }

/** Parses public binding fields without retaining any registration token. */
internal fun decodePushDevice(data: JsonObject): PushDeviceBindingDto = PushDeviceBindingDto(
    id = data.wireText("id"),
    installationId = data.wireText("installation_id"),
    platform = data.wireText("platform"),
    disabledAt = data.wireNullableText("disabled_at"),
)

/** Binds the returned device to the exact submitted installation and signed-platform context. */
internal fun PushDeviceBindingDto.toDomain(expectedInstallation: String, expectedPlatform: String): PushDeviceBinding {
    require(validNotificationId(id))
    require(installationId == expectedInstallation && platform == expectedPlatform && disabledAt == null)
    return PushDeviceBinding(id, installationId, platform)
}

/** Serializes write input only, using the existing canonical request field names. */
internal fun PushDeviceRegistrationDto.toWireJson(): String = buildJsonObject {
    put("platform", platform)
    put("token", token)
}.toString()

/** Matches the backend's lowercase canonical UUID check and excludes the nil identifier. */
internal fun validNotificationId(value: String): Boolean =
    value != "00000000-0000-0000-0000-000000000000" &&
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(value)

/** Requires a JSON string and keeps contract mismatches visible to the adapter. */
internal fun JsonObject.wireText(key: String): String = getValue(key).jsonPrimitive.also { require(it.isString) }.content

/** Requires an unquoted integral JSON value. */
private fun JsonObject.wireNumber(key: String): Int = getValue(key).jsonPrimitive.also { require(!it.isString) }.int

/** Required nullable fields must be present; omission does not silently become null. */
private fun JsonObject.wireNullableText(key: String): String? =
    if (getValue(key) is JsonNull) null else wireText(key)
