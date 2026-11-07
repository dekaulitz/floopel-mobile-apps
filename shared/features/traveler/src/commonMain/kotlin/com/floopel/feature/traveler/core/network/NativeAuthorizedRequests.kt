package com.floopel.feature.traveler.core.network

import com.floopel.feature.traveler.data.remote.*
import com.floopel.feature.traveler.feature.auth.*

import com.floopel.feature.traveler.feature.auth.TravelerSession
import com.floopel.feature.traveler.core.network.ApiFailure

/** One identity owner for Quest, Saved Spots and My Itineraries. */
internal class NativeAuthorizedRequests(
    private val rest: FloopelRestClient,
    private val session: TravelerSession,
) {
    /** One native mutation; only parent contracts that already support replay supply an idempotency key. */
    suspend fun upload(path: String, bytes: ByteArray, mimeType: String, key: String? = null, expectedGeneration: Long? = null): ApiOutcome = try {
        session.withIdentity(readOnly = false, expectedGeneration = expectedGeneration) { token ->
            val headers = mapOf("Authorization" to "Bearer $token") +
                (key?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap())
            rest.upload(path, bytes, mimeType, headers).verifyIdentityOutcome()
        }
    } catch (failure: ApiFailure) {
        failure.toOutcome()
    }
    /** Runs once through the identity owner and preserves canonical throttle/expiry metadata. */
    suspend fun execute(method: RestMethod, appsPath: String, jsonBody: String? = null, headers: Map<String, String> = emptyMap(), expectedGeneration: Long? = null): ApiOutcome = try {
        session.withIdentity(readOnly = method == RestMethod.GET, expectedGeneration = expectedGeneration) { token ->
            rest.execute(method, appsPath, jsonBody, headers = headers + mapOf("Authorization" to "Bearer $token"))
                .verifyIdentityOutcome()
        }
    } catch (failure: ApiFailure) {
        failure.toOutcome()
    }
}

/** Keep canonical codes through the single session owner's throttle/expiry decision. */
private fun ApiOutcome.verifyIdentityOutcome(): ApiOutcome {
    when (this) {
        is ApiOutcome.HttpFailure -> if (status == 401 || status == 429)
            throw ApiFailure(status, code ?: canonicalAppsFailureCode(jsonErrorBody), retryAfterSeconds)
        is ApiOutcome.Unavailable -> throw ApiFailure(503, null, retryAfterSeconds)
        else -> Unit
    }
    return this
}

/** No raw provider or credential-bearing body is synthesized when session policy returns a failure. */
private fun ApiFailure.toOutcome(): ApiOutcome = if (status == 503) ApiOutcome.Unavailable(retryAfterSeconds)
    else ApiOutcome.HttpFailure(status, retryAfterSeconds = retryAfterSeconds, code = code)
