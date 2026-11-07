package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.data.remote.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TripRecoveryStore
import com.floopel.feature.traveler.feature.auth.TravelerSession
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Per-Trip encrypted intents survive process loss and use the existing session and durable server receipt. */
internal class NativeTripReportRequests(rest: FloopelRestClient, private val session: TravelerSession, private val recovery: TripRecoveryStore) {
    private val http = NativeAuthorizedRequests(rest, session)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutation = Mutex()

    /** Reads local input only after resolving the actual profile in the current identity generation. */
    suspend fun pending(tripId: String): CatalogResult<TripReportIntent?> = mutation.withLock {
        val epoch = session.identityGeneration
        val actor = actor(epoch)
        if (actor !is CatalogResult.Ready) return@withLock actor.failure()
        runCatching {
            require(validAppsId(tripId))
            val record = records()[tripId] ?: return@runCatching CatalogResult.Ready(null)
            require(record.profileId == actor.value)
            CatalogResult.Ready(record.intent(json).also { require(it.tripId == tripId) })
        }.getOrElse { CatalogResult.InvalidResponse }
    }

    /** Freezes the exact actor/key/body before POST; a different unresolved report can never overwrite it. */
    suspend fun submit(intent: TripReportIntent): CatalogResult<ReportReceipt> = mutation.withLock {
        val epoch = session.identityGeneration
        val actor = actor(epoch)
        if (actor !is CatalogResult.Ready) return@withLock actor.failure()
        val saved = runCatching {
            val body = intent.reportBody()
            records()[intent.tripId]?.also { require(it.profileId == actor.value && it.body == body && it.key == intent.key) }
                ?: TripReportRecoveryDto(actor.value, intent.title, body, intent.key).also { put(it) }
        }.getOrElse { return@withLock CatalogResult.InvalidResponse }
        val result = receipt(RestMethod.POST, "/reports", saved, epoch)
        settle(saved, result, epoch, rejectedWrite = true)
    }

    /** Checks the existing operation without POST, including when its original Trip is no longer public. */
    suspend fun check(intent: TripReportIntent): CatalogResult<ReportReceipt> = mutation.withLock {
        val epoch = session.identityGeneration
        val actor = actor(epoch)
        if (actor !is CatalogResult.Ready) return@withLock actor.failure()
        val saved = runCatching {
            records()[intent.tripId]?.also { require(it.profileId == actor.value && it.intent(json) == intent) }
                ?: TripReportRecoveryDto(actor.value, intent.title, intent.reportBody(), intent.key).also { put(it) }
        }.getOrElse { return@withLock CatalogResult.InvalidResponse }
        settle(saved, receipt(RestMethod.GET, "/me/report-creation-operations/${saved.key.encodeURLPathPart()}", saved, epoch), epoch)
    }

    /** Only a matching confirmed receipt can release the intent; uncertain input remains available after Back. */
    suspend fun complete(intent: TripReportIntent): CatalogResult<Unit> = mutation.withLock {
        val epoch = session.identityGeneration
        val actor = actor(epoch)
        if (actor !is CatalogResult.Ready) return@withLock actor.failure()
        runCatching {
            val saved = records()[intent.tripId] ?: return@runCatching CatalogResult.Ready(Unit)
            require(saved.profileId == actor.value && saved.intent(json) == intent && validAppsId(saved.confirmedReportId.orEmpty()))
            require(epoch == session.identityGeneration)
            remove(saved)
            CatalogResult.Ready(Unit)
        }.getOrElse { CatalogResult.InvalidResponse }
    }

    /** Confirmed writes/reads remain confirmed even when local cleanup fails; no uncertain failure releases input. */
    private fun settle(saved: TripReportRecoveryDto, result: CatalogResult<ReportReceipt>, epoch: Long, rejectedWrite: Boolean = false): CatalogResult<ReportReceipt> {
        if (session.identityGeneration != epoch) return CatalogResult.HttpError(401)
        runCatching {
            val current = records()[saved.intent(json).tripId]
            if (current?.key == saved.key && current.body == saved.body) {
                if (result is CatalogResult.Ready) put(saved.copy(confirmedReportId = result.value.id))
                else if (rejectedWrite && result is CatalogResult.HttpError && result.status == 422 && result.code == "VALIDATION") remove(saved)
            }
        }
        return result
    }

    /** The operation and target must match before an HTTP success becomes a receipt. */
    private suspend fun receipt(method: RestMethod, path: String, saved: TripReportRecoveryDto, epoch: Long): CatalogResult<ReportReceipt> {
        val outcome = http.execute(method, "/api/apps/v1$path", saved.body.takeIf { method == RestMethod.POST },
            if (method == RestMethod.POST) mapOf("Idempotency-Key" to saved.key) else emptyMap(), epoch)
        if (session.identityGeneration != epoch) return CatalogResult.HttpError(401)
        return when (outcome) {
            is ApiOutcome.Success -> runCatching {
                require(outcome.status == if (method == RestMethod.POST) 201 else 200)
                val envelope = json.parseToJsonElement(outcome.responseBody).jsonObject
                val meta = json.decodeFromJsonElement<AppsMetaDto>(envelope.getValue("meta")).also { it.requireCanonical() }
                require(meta.code == if (method == RestMethod.POST) "REPORT_CREATED" else "REPORT")
                require(envelope["error"] is JsonNull)
                val dto = json.decodeFromJsonElement<TripReportReceiptDto>(envelope.getValue("data"))
                require(validAppsId(dto.id) && dto.targetType == "trip" && dto.targetId == saved.intent(json).tripId)
                require(dto.reasonCode == saved.intent(json).reason.wire)
                require(dto.status in setOf("new", "in_review", "resolved", "dismissed"))
                ReportReceipt(dto.id, dto.status)
            }.fold({ CatalogResult.Ready(it) }, { CatalogResult.InvalidResponse })
            is ApiOutcome.HttpFailure -> CatalogResult.HttpError(outcome.status, outcome.retryAfterSeconds,
                outcome.code ?: canonicalAppsFailureCode(outcome.jsonErrorBody))
            is ApiOutcome.Unavailable -> CatalogResult.Maintenance(outcome.retryAfterSeconds)
            ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
        }
    }

    /** Me is resolved through the same native authorization owner and rejected after an account generation changes. */
    private suspend fun actor(epoch: Long): CatalogResult<String> {
        val outcome = http.execute(RestMethod.GET, "/api/apps/v1/me", expectedGeneration = epoch)
        if (session.identityGeneration != epoch) return CatalogResult.HttpError(401)
        return when (outcome) {
            is ApiOutcome.Success -> runCatching {
                val value = json.parseToJsonElement(outcome.responseBody).jsonObject
                require(value["error"] is JsonNull)
                value.getValue("data").jsonObject.getValue("profile").jsonObject.getValue("id").jsonPrimitive.also { require(it.isString && validAppsId(it.content)) }.content
            }.fold({ CatalogResult.Ready(it) }, { CatalogResult.InvalidResponse })
            is ApiOutcome.HttpFailure -> CatalogResult.HttpError(outcome.status, outcome.retryAfterSeconds, outcome.code ?: canonicalAppsFailureCode(outcome.jsonErrorBody))
            is ApiOutcome.Unavailable -> CatalogResult.Maintenance(outcome.retryAfterSeconds)
            ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
        }
    }

    /** Platform storage remains opaque outside this adapter; no response body or credential is retained. */
    private fun records(): Map<String, TripReportRecoveryDto> = recovery.read()?.let { json.decodeFromString<TripReportRecoveryIndexDto>(it).records } ?: emptyMap()

    /** Fresh merge retains other targets while refusing to mix identities in the encrypted index. */
    private fun put(record: TripReportRecoveryDto) {
        val current = records()
        require(current.values.all { it.profileId == record.profileId })
        recovery.write(json.encodeToString(TripReportRecoveryIndexDto(current + (record.intent(json).tripId to record))))
    }
    /** Clearing a receipt never deletes a different operation that replaced it or belongs to another Trip. */
    private fun remove(record: TripReportRecoveryDto) {
        val current = records()
        val id = record.intent(json).tripId
        require(current[id]?.key == record.key && current[id]?.body == record.body)
        val remaining = current - id
        if (remaining.isEmpty()) recovery.clear() else recovery.write(json.encodeToString(TripReportRecoveryIndexDto(remaining)))
    }
    /** Read failures keep their typed transport category when crossing the report port. */
    private fun <T> CatalogResult<*>.failure(): CatalogResult<T> = when (this) {
        is CatalogResult.HttpError -> this; is CatalogResult.Maintenance -> this
        CatalogResult.NetworkError -> CatalogResult.NetworkError; else -> CatalogResult.InvalidResponse
    }
}
