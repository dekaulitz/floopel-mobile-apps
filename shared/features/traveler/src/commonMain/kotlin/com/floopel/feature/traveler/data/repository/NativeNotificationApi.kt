package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.ApiOutcome
import com.floopel.feature.traveler.core.network.FloopelRestClient
import com.floopel.feature.traveler.core.network.NativeAuthorizedRequests
import com.floopel.feature.traveler.core.network.RestMethod
import com.floopel.feature.traveler.data.dto.PushDeviceRegistrationDto
import com.floopel.feature.traveler.data.dto.AppsMetaDto
import com.floopel.feature.traveler.data.remote.canonicalAppsFailureCode
import com.floopel.feature.traveler.data.remote.requireCanonical
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerNotificationRepository
import com.floopel.feature.traveler.domain.repository.TravelerPushRegistration
import com.floopel.feature.traveler.feature.auth.TravelerSession
import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Uses the existing session/refresh owner and rejects malformed success envelopes. */
internal class NativeNotificationApi(rest: FloopelRestClient, session: TravelerSession) :
    TravelerNotificationRepository, TravelerPushRegistration {
    private val requests = NativeAuthorizedRequests(rest, session)
    private val json = Json { ignoreUnknownKeys = true }

    /** Carries page/per_page solely as internal batch transport for infinite scrolling. */
    override suspend fun notifications(page: Int, classification: NotificationClass?, locale: String): CatalogResult<NotificationPage> {
        if (page !in 1..100000 || !validLocale(locale)) return CatalogResult.HttpError(422)
        val filter = classification?.let { "&class=${it.wireValue}" }.orEmpty()
        return request(RestMethod.GET, "/notifications?page=$page&per_page=$BatchSize&locale=$locale$filter") { envelope ->
            val pagination = decodeNotificationPagination(envelope.getValue("meta").jsonObject.getValue("pagination").jsonObject)
            val items = envelope.getValue("data").jsonArray.map { decodeNotification(it.jsonObject) }
            pagination.toDomainPage(items, page, BatchSize)
        }
    }

    /** GET never changes read state; the screen acknowledges only after displaying this result. */
    override suspend fun notification(id: String, locale: String): CatalogResult<TravelerNotification> {
        if (!validNotificationId(id) || !validLocale(locale)) return CatalogResult.HttpError(422)
        return request(RestMethod.GET, "/notifications/$id?locale=$locale") { decodeNotification(it.getValue("data").jsonObject).toDomain().also { value -> require(value.id == id) } }
    }

    /** The mutation executes once through NativeAuthorizedRequests without credential replay. */
    override suspend fun markNotificationRead(id: String): CatalogResult<Instant> {
        if (!validNotificationId(id)) return CatalogResult.HttpError(422)
        return request(RestMethod.PUT, "/notifications/$id/read") { envelope ->
            decodeNotificationRead(envelope.getValue("data").jsonObject).toDomainTimestamp(id)
        }
    }

    /** The count remains server-owned when only part of the list has been loaded. */
    override suspend fun unreadNotifications(): CatalogResult<Int> = request(RestMethod.GET, "/notifications/unread-count") {
        decodeNotificationCount(it.getValue("data").jsonObject).toDomainCount()
    }

    /** Token values are submitted only as write input and are never retained by this adapter. */
    override suspend fun registerPushDevice(installationId: String, platform: String, token: String): CatalogResult<PushDeviceBinding> {
        if (!validNotificationId(installationId) || platform !in setOf("android", "ios") || token.isBlank()) return CatalogResult.HttpError(422)
        val body = PushDeviceRegistrationDto(platform, token).toWireJson()
        return request(RestMethod.PUT, "/mobile/installations/$installationId/push-device", body) { envelope ->
            decodePushDevice(envelope.getValue("data").jsonObject).toDomain(installationId, platform)
        }
    }

    /** Disable accepts only the binding ID returned by registration and expects no response body. */
    override suspend fun disablePushDevice(bindingId: String): CatalogResult<Unit> {
        if (!validNotificationId(bindingId)) return CatalogResult.HttpError(422)
        return when (val result = requests.execute(RestMethod.DELETE, "/api/apps/v1/mobile/push-devices/$bindingId")) {
            is ApiOutcome.Success -> if (result.status == 204 && result.responseBody.isEmpty()) CatalogResult.Ready(Unit) else CatalogResult.InvalidResponse
            else -> result.failure()
        }
    }

    /** Parsing is pure; cancellation from transport/session ownership is not swallowed. */
    private suspend fun <T> request(method: RestMethod, path: String, body: String? = null, decode: (JsonObject) -> T): CatalogResult<T> =
        when (val result = requests.execute(method, "/api/apps/v1$path", body)) {
            is ApiOutcome.Success -> runCatching {
                require(result.status == 200)
                val envelope = json.parseToJsonElement(result.responseBody).jsonObject
                val meta = json.decodeFromJsonElement<AppsMetaDto>(envelope.getValue("meta"))
                meta.requireCanonical()
                require(envelope.getValue("error") is JsonNull && meta.code == "OK")
                CatalogResult.Ready(decode(envelope))
            }.getOrElse { CatalogResult.InvalidResponse }
            else -> result.failure()
        }

    companion object {
        private const val BatchSize = 20
    }
}

/** Only canonical locales are interpolated into request URLs. */
private fun validLocale(value: String): Boolean = value == "id" || value == "en"

/** Existing transport failures retain maintenance and retry metadata. */
private fun ApiOutcome.failure(): CatalogResult<Nothing> = when (this) {
    is ApiOutcome.Unavailable -> CatalogResult.Maintenance(retryAfterSeconds)
    is ApiOutcome.HttpFailure -> CatalogResult.HttpError(status, retryAfterSeconds, code ?: canonicalAppsFailureCode(jsonErrorBody))
    is ApiOutcome.Success -> CatalogResult.InvalidResponse
    ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
}
