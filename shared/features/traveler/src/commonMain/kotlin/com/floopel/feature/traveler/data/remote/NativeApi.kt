package com.floopel.feature.traveler.data.remote

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerSystemMaintenance
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.fromHttpToGmtDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.Instant

/** One host-owned HTTP client carries platform/build metadata; legacy fixtures may omit the version. */
internal class NativeApi(private val client: HttpClient, baseUrl: String, private val platform: AppPlatform, val systemAvailability: SystemAvailability = SystemAvailability(), private val readCache: TravelerReadCache? = null, appVersion: String? = null) : TravelerSystemMaintenance {
    private val metadata = AppRequestMetadata(platform, appVersion)
    override val maintenance = systemAvailability.maintenance
    override fun maintenanceRetrySecondsRemaining() = systemAvailability.retrySecondsRemaining()
    private val origin = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    init { require(origin.startsWith("https://") || origin.startsWith("http://")) }

    /** Catalog and Quest share the same platform-owned Ktor client. */
    fun catalogTransport() = com.floopel.feature.traveler.core.network.FloopelRestClient(origin, client, systemAvailability, readCache, metadata)
    val pageReadRevision: Long get() = readCache?.revision ?: 0L
    suspend fun clearPageReads() { readCache?.clear() }
    suspend fun withFreshPageReads(block: suspend () -> Unit) {
        if (readCache == null) block() else readCache.withFreshReads(block)
    }
    /** A single explicit recovery read traverses the same API gateway, never the liveness bypass. */
    override suspend fun recheckSystemMaintenance(): Boolean {
        val notice = systemAvailability.maintenance.value ?: return true
        if (systemAvailability.retrySecondsRemaining() > 0) return false
        val result = catalogTransport().maintenanceProbe()
        if (result !is ApiOutcome.Success) return false
        val envelope = runCatching { json.parseToJsonElement(result.responseBody).jsonObject }.getOrNull() ?: return false
        if (envelope["error"] != JsonNull || envelope["data"] !is JsonArray ||
            envelope["meta"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull != "OK") return false
        systemAvailability.recover(notice.revision)
        return systemAvailability.maintenance.value == null
    }
    fun close() = client.close()

    internal suspend fun login(email: String, password: String): NativeAuthentication {
        val data = call("/api/mobile/v1/auth/login", HttpMethod.Post, buildJsonObject { put("email", email); put("password", password) })
        return readAuthentication(data.jsonObject).also { require(it !is NativeAuthentication.NeedsProfile) }
    }

    /** Consumes verification once; uncertain issuance is recovered through explicit sign-in. */
    suspend fun verifyEmail(email: String, code: String): NativeTokens {
        val data = call("/api/mobile/v1/auth/verify-email", HttpMethod.Post,
            buildJsonObject { put("email", email); put("code", code) })
        return decodeTokens((data as? JsonObject)?.get("tokens") ?: throw ApiFailure(502, null, null))
    }

    /** Shared auth adapter only; no browser credentials or generic response replay. */
    internal suspend fun registrationRequest(path: String, method: HttpMethod = HttpMethod.Get, body: JsonObject? = null, key: String? = null, onboardingProof: String? = null): JsonElement =
        call(path, method, body, idempotencyKey = key, accessToken = onboardingProof)

    internal fun sessionTokens(data: JsonObject): NativeTokens = decodeTokens(data["tokens"] ?: throw ApiFailure(502, null, null))

    suspend fun refresh(refreshToken: String): NativeTokens = decodeTokens(call(
        "/api/mobile/v1/auth/refresh", HttpMethod.Post,
        buildJsonObject { put("refresh_token", refreshToken) },
    ))

    suspend fun logout(refreshToken: String) {
        call("/api/mobile/v1/auth/logout", HttpMethod.Post, buildJsonObject { put("refresh_token", refreshToken) })
    }

    suspend fun passport(accessToken: String, locale: String): PassportProgress {
        require(locale == "id" || locale == "en")
        return decode<PassportProgress>(call("/api/apps/v1/gamification/me/progress?locale=$locale", accessToken = accessToken)).let { it.copy(badges = it.badges.map(::resolveBadge)) }
    }

    suspend fun quests(page: Int, locale: String, filter: QuestDiscoveryFilter = QuestDiscoveryFilter(), identity: String? = null): QuestPage {
        require(page > 0 && locale in listOf("id", "en"))
        require(!filter.excludeTaken || identity != null)
        val path = buildString {
            append(if (identity == null) "/api/apps/v1/gamification/quests" else "/api/apps/v1/gamification/me/quests")
            append("?locale=$locale&page=$page&per_page=20")
            if (filter.excludeTaken) append("&exclude_taken=true")
            filter.availability?.let { append("&status=$it") }
            filter.minXP?.let { require(it >= 0); append("&min_xp=$it") }
            filter.participationMode?.let { append("&participation_mode=$it") }
            filter.maxXP?.let { require(it >= 0 && (filter.minXP == null || it >= filter.minXP)); append("&max_xp=$it") }
            filter.locationId?.let { require(it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))); append("&location_id=$it") }
        }
        val envelope = call(path, accessToken = identity, includeMeta = true).jsonObject
        val pagination = (envelope["meta"] as? JsonObject)?.get("pagination") ?: throw ApiFailure(502, null, null)
        val data = envelope["data"] ?: throw ApiFailure(502, null, null)
        if (filter.excludeTaken && (data !is JsonArray || data.any {
            val taken = (it as? JsonObject)?.get("is_taken") as? JsonPrimitive
            taken == null || taken.isString || taken.booleanOrNull != false
        })) throw ApiFailure(502, null, null)
        val summaries = decode<List<QuestSummary>>(data)
        if (filter.excludeTaken && summaries.any { it.isTaken != false }) throw ApiFailure(502, null, null)
        return QuestPage(summaries.map { it.copy(reward = resolveReward(it.reward)) }, decode(pagination))
    }

    /** Quest content retains its historical admission count; visible traveler levels use one public batch. */
    suspend fun quest(slug: String, locale: String): QuestDetail {
        validateQuestPath(slug, locale)
        val payload = call("/api/apps/v1/gamification/quests/$slug?locale=$locale&traveler_limit=$QuestTravelerPreviewLimit")
        val count = (payload as? JsonObject)?.get("traveler_count")
        if (count != null && count != JsonNull && (count !is JsonPrimitive || count.isString || count.longOrNull == null))
            throw ApiFailure(502, null, null)
        val detail = decode<QuestDetail>(payload)
        val identities = com.floopel.feature.traveler.data.repository.TravelerAvatarIdentities(catalogTransport()).read(detail.travelers.map { it.username })
        return detail.copy(reward = resolveReward(detail.reward), travelers = detail.travelers.map { it.copy(level = identities[it.username]?.level,
            avatarUrl = identities[it.username]?.avatarUrl) })
    }

    suspend fun questOwner(slug: String, locale: String, accessToken: String): QuestOwnerState {
        validateQuestPath(slug, locale)
        return decode(call("/api/apps/v1/gamification/quests/$slug/me?locale=$locale", accessToken = accessToken))
    }

    suspend fun executionChallenge(identity: String): ExecutionChallenge = decode(call(
        "/api/apps/v1/mobile/gamification/auth/challenges", HttpMethod.Post,
        buildJsonObject { put("platform", platform.name.lowercase()) }, accessToken = identity,
    ))

    suspend fun androidExecutionCredential(identity: String, challenge: ExecutionChallenge, proof: String): ExecutionCredential {
        require(platform == AppPlatform.ANDROID && challenge.platform == "android")
        return decode<ExecutionCredential>(call("/api/apps/v1/mobile/gamification/auth/credentials", HttpMethod.Post,
            buildJsonObject { put("platform", "android"); put("challenge_id", challenge.id); put("integrity_token", proof) }, accessToken = identity,
        )).also { if (it.type != "Bearer" || it.token.isBlank() || it.expiresIn != 600) throw ApiFailure(502, null, null) }
    }

    suspend fun startQuest(questId: String, execution: ExecutionCredential, key: String, locale: String, location: QuestLocation? = null): QuestParticipation {
        require(Regex("^[0-9a-f-]{36}$").matches(questId) && locale in listOf("id", "en"))
        return decode(call("/api/apps/v1/mobile/gamification/quests/$questId/start?locale=$locale", HttpMethod.Post,
            buildJsonObject { if (location != null) put("location", locationBody(location)) }, accessToken = execution.token, idempotencyKey = key))
    }

    suspend fun participation(id: String, identity: String, locale: String): QuestParticipation {
        validateResourceId(id)
        return decode(call("/api/apps/v1/gamification/participations/$id?locale=$locale", accessToken = identity))
    }

    suspend fun activity(participation: String, activity: String, identity: String, locale: String): QuestActivityContent {
        validateResourceId(participation); validateResourceId(activity)
        return decode(call("/api/apps/v1/gamification/participations/$participation/activities/$activity?locale=$locale", accessToken = identity))
    }

    /** A final answer preserves the committed reward receipt and resolves its optional public Badge artwork. */
    suspend fun answer(participation: String, activity: String, type: String, answer: String, execution: ExecutionCredential, key: String, locale: String): QuestAnswerResult {
        validateResourceId(participation); validateResourceId(activity)
        require(type in listOf("text", "multiple_choice") && answer.isNotBlank())
        val body = buildJsonObject { put("answer_type", type); put(if (type == "text") "text" else "selected_option_id", answer) }
        return decode<QuestAnswerResult>(call("/api/apps/v1/mobile/gamification/participations/$participation/activities/$activity/answers?locale=$locale", HttpMethod.Post,
            body, accessToken = execution.token, idempotencyKey = key)).let { it.copy(completion = resolveCompletion(it.completion)) }
    }

    private fun validateResourceId(id: String) { require(Regex("^[0-9a-f-]{36}$").matches(id)) }

    suspend fun startProof(participation: String, activity: String, execution: ExecutionCredential, key: String): QuestProofSession {
        validateResourceId(participation); validateResourceId(activity)
        return decode(call("/api/apps/v1/mobile/gamification/participations/$participation/proof-sessions", HttpMethod.Post,
            buildJsonObject { put("activity_id", activity) }, accessToken = execution.token, idempotencyKey = key))
    }

    suspend fun observeProof(session: String, observation: String, location: QuestLocation, execution: ExecutionCredential, key: String): QuestProofSession {
        validateResourceId(session); validateResourceId(observation)
        require(location.latitude.isFinite() && location.latitude in -90.0..90.0)
        require(location.longitude.isFinite() && location.longitude in -180.0..180.0)
        require(location.accuracyMeters.isFinite() && location.accuracyMeters in 0.0..10000.0)
        Instant.parse(location.capturedAt)
        return decode(call("/api/apps/v1/mobile/gamification/proof-sessions/$session/observations", HttpMethod.Post,
            buildJsonObject {
                put("observation_id", observation); put("lat", location.latitude); put("lng", location.longitude)
                put("accuracy_m", location.accuracyMeters); put("captured_at", location.capturedAt)
            }, accessToken = execution.token, idempotencyKey = key))
    }

    /** GPS completion carries the same authoritative reward receipt as question completion. */
    suspend fun submitProof(session: String, execution: ExecutionCredential, key: String): QuestProofResult {
        validateResourceId(session)
        return decode<QuestProofResult>(call("/api/apps/v1/mobile/gamification/proof-sessions/$session/submit", HttpMethod.Post,
            accessToken = execution.token, idempotencyKey = key)).let { it.copy(completion = resolveCompletion(it.completion)) }
    }

    /** Optional exact slug narrows a foreground reveal; mixed or cross-target responses fail closed. */
    suspend fun revealQuests(location: QuestLocation, execution: ExecutionCredential, page: Int, locale: String, questSlug: String? = null): Pair<List<QuestDetail>, QuestPageInfo> {
        require(page > 0 && locale in listOf("id", "en"))
        if (questSlug != null) validateQuestPath(questSlug, locale)
        val targetQuery = questSlug?.let { "&quest_slug=$it" }.orEmpty()
        val envelope = call("/api/apps/v1/mobile/gamification/quests/reveal?page=$page&per_page=20&locale=$locale$targetQuery", HttpMethod.Post,
            buildJsonObject { put("location", locationBody(location)) }, accessToken = execution.token, includeMeta = true).jsonObject
        val pagination = (envelope["meta"] as? JsonObject)?.get("pagination") ?: throw ApiFailure(502, null, null)
        val items = decode<List<QuestDetail>>(envelope["data"] ?: throw ApiFailure(502, null, null))
        if (questSlug != null && (items.size > 1 || items.any { it.slug != questSlug })) throw ApiFailure(502, null, null)
        return items.map { it.copy(reward = resolveReward(it.reward)) } to decode(pagination)
    }

    private fun resolveReward(reward: QuestReward) = reward.copy(badge = reward.badge?.let(::resolveBadge))
    /** Completion artwork obeys the same public-media URL boundary as Passport and Quest previews. */
    private fun resolveCompletion(receipt: QuestCompletionReceipt?) = receipt?.copy(badge = receipt.badge?.let(::resolveBadge))
    private fun resolveBadge(badge: PassportBadge): PassportBadge = badge.copy(image = badge.image?.url?.let { catalogTransport().resolveMediaUrl(it) }?.let(::PassportBadgeImage))

    private fun locationBody(location: QuestLocation) = buildJsonObject { put("lat", location.latitude); put("lng", location.longitude) }

    private fun validateQuestPath(slug: String, locale: String) {
        require(Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$").matches(slug) && locale in listOf("id", "en"))
    }

    private fun decodeTokens(data: JsonElement): NativeTokens = decode<NativeTokens>(data).also {
        if (it.tokenType != "Bearer" || it.accessToken.isBlank() || it.refreshToken.isBlank() ||
            runCatching { Instant.parse(it.accessExpiresAt); Instant.parse(it.refreshExpiresAt) }.isFailure) throw ApiFailure(502, null, null)
    }

    private inline fun <reified T> decode(data: JsonElement): T = try { json.decodeFromJsonElement<T>(data) }
    catch (_: IllegalArgumentException) { throw ApiFailure(502, null, null) }

    private suspend fun call(path: String, method: HttpMethod = HttpMethod.Get, body: JsonObject? = null, accessToken: String? = null, includeMeta: Boolean = false, idempotencyKey: String? = null): JsonElement {
        val mutation = method != HttpMethod.Get
        if (mutation) readCache?.clear()
        try {
            if (systemAvailability.maintenance.value != null) throw ApiFailure(503, null, systemAvailability.retrySecondsRemaining())
            val response = client.request(origin + path) {
                this.method = method
                if (idempotencyKey != null) header("Idempotency-Key", idempotencyKey)
                metadata.applyTo(headers)
                if (accessToken != null) header("Authorization", "Bearer $accessToken")
                if (body != null) { contentType(ContentType.Application.Json); setBody(body.toString()) }
            }
            val status = response.status.value
            val retry = retryAfterSeconds(response.headers["Retry-After"], Clock.System.now().toEpochMilliseconds())
            systemAvailability.observe(status, response.headers["X-Floopel-Maintenance"], retry)
            if (status == 204) return JsonNull
            val responseBody = response.bodyAsText()
            val envelope = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull()
            val code = ((envelope?.get("meta") as? JsonObject)?.get("code") as? JsonPrimitive)?.contentOrNull
            if (status !in 200..299) throw ApiFailure(status, code, retry ?: when (status) { 429 -> 30; 503 -> 60; else -> null })
            if (envelope == null || envelope["error"] != JsonNull) throw ApiFailure(502, null, null)
            if (includeMeta) return envelope
            return envelope["data"] ?: throw ApiFailure(502, null, null)
        } catch (cause: CancellationException) { throw cause }
        catch (cause: ApiFailure) { throw cause }
        catch (_: Exception) { throw ApiFailure(0, null, null) }
        finally { if (mutation) withContext(NonCancellable) { readCache?.clear() } }
    }
}

internal fun retryAfterSeconds(value: String?, nowMillis: Long): Long? {
    val hint = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    hint.toLongOrNull()?.let { return it.takeIf { seconds -> seconds >= 0 } }
    return runCatching { ((hint.fromHttpToGmtDate().timestamp - nowMillis + 999) / 1000).coerceAtLeast(0) }.getOrNull()
}
