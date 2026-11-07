package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.domain.model.CatalogResult
import com.floopel.feature.traveler.domain.repository.TripRecoveryStore
import com.floopel.feature.traveler.feature.auth.TravelerSession
import io.ktor.util.Digest
import io.ktor.util.hex
import kotlinx.serialization.json.*

/** Freezes a digest and key, never image bytes. A user retry must select the same content. */
internal class NativeMediaUploads(rest: FloopelRestClient, session: TravelerSession, store: TripRecoveryStore) {
    private val requests = NativeAuthorizedRequests(rest, session)
    private val journal = NativeCommandJournal(requests, session, store)
    suspend fun <T> upload(path: String, bytes: ByteArray, mime: String, key: String,
        decode: (ApiOutcome) -> CatalogResult<T>): CatalogResult<T> {
        val digest = Digest("SHA-256").apply { this += bytes }.build()
        val intent = buildJsonObject { put("sha256", hex(digest)); put("mime", mime); put("size", bytes.size) }.toString()
        return journal.execute(RestMethod.POST, path, intent, key) { frozen, epoch ->
            decode(requests.upload("/api/apps/v1$path", bytes, mime, frozen, epoch))
        }
    }
}
