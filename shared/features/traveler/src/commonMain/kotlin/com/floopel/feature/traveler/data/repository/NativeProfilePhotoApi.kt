package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.ApiOutcome
import com.floopel.feature.traveler.core.network.FloopelRestClient
import com.floopel.feature.traveler.core.network.NativeAuthorizedRequests
import com.floopel.feature.traveler.core.network.RestMethod
import com.floopel.feature.traveler.data.dto.ProfilePhotoEnvelopeDto
import com.floopel.feature.traveler.data.remote.validAppsId
import com.floopel.feature.traveler.data.remote.requireCanonical
import com.floopel.feature.traveler.data.remote.canonicalAppsFailureCode
import com.floopel.feature.traveler.domain.model.CatalogResult
import com.floopel.feature.traveler.domain.model.ProfilePhotoImage
import com.floopel.feature.traveler.domain.model.ProfilePhotoPolicy
import com.floopel.feature.traveler.domain.model.ProfilePhotoCodes
import com.floopel.feature.traveler.domain.model.ProfilePhotoStatus
import com.floopel.feature.traveler.domain.model.ProfilePhotoUpload
import com.floopel.feature.traveler.domain.repository.TravelerProfilePhotoRepository
import com.floopel.feature.traveler.feature.auth.TravelerSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull

/** Native-only photo contract on the single existing session; multipart writes are never silently replayed. */
internal class NativeProfilePhotoApi(private val rest: FloopelRestClient, session: TravelerSession) : TravelerProfilePhotoRepository {
    private val requests = NativeAuthorizedRequests(rest, session)
    private val json = Json { ignoreUnknownKeys = true }

    /** Performs one admission; HTTP202 must still report processing. */
    override suspend fun uploadProfilePhoto(image: ProfilePhotoImage): CatalogResult<ProfilePhotoUpload> {
        val bytes = image.bytes
        if (bytes.size > ProfilePhotoPolicy.MaxBytes) return CatalogResult.HttpError(413, code = ProfilePhotoCodes.PayloadTooLarge)
        if (bytes.isEmpty() || image.mimeType !in ProfilePhotoPolicy.MimeTypes) return CatalogResult.HttpError(422, code = ProfilePhotoCodes.Validation)
        return decode(requests.upload(UploadsPath, bytes, image.mimeType), Accepted)
    }

    /** A status read has no upload side effects and accepts only the returned resource ID. */
    override suspend fun profilePhotoUpload(id: String): CatalogResult<ProfilePhotoUpload> {
        if (!validAppsId(id)) return CatalogResult.HttpError(422, code = ProfilePhotoCodes.Validation)
        val result = decode(requests.execute(RestMethod.GET, "$UploadsPath/$id"), ReadOK)
        if (result is CatalogResult.Ready) {
            if (result.value.id != id) return CatalogResult.InvalidResponse
            if (result.value.status == ProfilePhotoStatus.READY) rest.invalidatePageReads()
        }
        return result
    }

    /** Canonical success parsing is pure and cannot swallow coroutine cancellation from HTTP/session calls. */
    private fun decode(outcome: ApiOutcome, expectedStatus: Int): CatalogResult<ProfilePhotoUpload> = when (outcome) {
        is ApiOutcome.Success -> runCatching {
            require(outcome.status == expectedStatus)
            val envelope = json.decodeFromString<ProfilePhotoEnvelopeDto>(outcome.responseBody)
            envelope.meta.requireCanonical()
            require(envelope.meta.code == "OK" && envelope.error is JsonNull)
            val upload = envelope.data.toDomain()
            require(expectedStatus != Accepted || upload.status == ProfilePhotoStatus.PROCESSING)
            CatalogResult.Ready(upload)
        }.getOrElse { CatalogResult.InvalidResponse }
        is ApiOutcome.HttpFailure -> CatalogResult.HttpError(outcome.status, outcome.retryAfterSeconds,
            outcome.code ?: canonicalAppsFailureCode(outcome.jsonErrorBody))
        is ApiOutcome.Unavailable -> CatalogResult.Maintenance(outcome.retryAfterSeconds)
        ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
    }

    private companion object {
        const val UploadsPath = "/api/apps/v1/me/profile-photo-uploads"
        const val Accepted = 202
        const val ReadOK = 200
    }
}
