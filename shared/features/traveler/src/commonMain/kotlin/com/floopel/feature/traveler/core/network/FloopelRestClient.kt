package com.floopel.feature.traveler.core.network

import com.floopel.feature.traveler.data.remote.*

import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Shared REST transport for Traveler consumers.
 *
 * Approved OpenAPI operation adapters own paths, request/response DTOs, auth and
 * canonical meta/error/data decoding; this transport never invents endpoints.
 * The application owner must close the instance when its lifecycle ends.
 */
internal class FloopelRestClient internal constructor(
    private val baseUrl: String,
    private val client: HttpClient,
    private val availability: SystemAvailability = SystemAvailability(),
    private val readCache: TravelerReadCache? = null,
    private val metadata: AppRequestMetadata? = null,
) {
    /** Public iOS/Android constructor does not expose Ktor implementation types. */
    constructor(baseUrl: String) : this(baseUrl, HttpClient { expectSuccess = false })
    /** Native hosts supply their actual build version and platform, including future Swift consumers. */
    constructor(baseUrl: String, platform: AppPlatform, appVersion: String) :
        this(baseUrl, HttpClient { expectSuccess = false }, metadata = AppRequestMetadata(platform, appVersion))
    init {
        require(baseUrl.startsWith("https://") || Regex("http://(localhost|127\\.0\\.0\\.1|10\\.0\\.2\\.2)(:[0-9]+)?").matches(baseUrl)) { "HTTPS base URL required" }
    }

    /**
     * Makes one request; does not silently retry, parse HTML or branch on error text.
     * jsonBody is already serialized by its approved contract adapter.
     */
    suspend fun execute(
        method: RestMethod,
        path: String,
        jsonBody: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ApiOutcome = executeRequest(method, path, jsonBody, headers, false)

    internal suspend fun maintenanceProbe(): ApiOutcome = executeRequest(RestMethod.GET,
        "/api/apps/v1/locations?level=region&per_page=1", null, emptyMap(), true)

    private suspend fun executeRequest(method: RestMethod, path: String, jsonBody: String?,
        headers: Map<String, String>, probe: Boolean): ApiOutcome {
        if (!probe && availability.maintenance.value != null) return ApiOutcome.Unavailable(availability.retrySecondsRemaining())
        require(path.startsWith("/") && !path.startsWith("//") && !path.contains("://")) {
            "Use an absolute path defined by OpenAPI"
        }
        val mutation = method != RestMethod.GET
        if (mutation) readCache?.clear()
        val cacheKey = if (!probe && !mutation && readCache?.eligible(path) == true)
            TravelerReadCache.Key(path, headers.entries.firstOrNull { it.key.equals("Authorization", true) }?.value) else null
        val requestControl = headers.entries.firstOrNull { it.key.equals(HttpHeaders.CacheControl, true) }?.value
        if (cacheKey != null && !requiresFreshRead(requestControl)) readCache?.get(cacheKey)?.let { return it }
        val cacheGeneration = readCache?.generation() ?: 0
        return try {
            val response = client.request(baseUrl.trimEnd('/') + path) {
                this.method = HttpMethod.parse(method.name)
                headers.forEach { (key, value) -> this.headers.append(key, value) }
                metadata?.applyTo(this.headers)
                if (jsonBody != null) {
                    contentType(ContentType.Application.Json)
                    setBody(jsonBody)
                }
            }
            availability.observe(response.status.value, response.headers["X-Floopel-Maintenance"], parseRetryAfter(response.headers[HttpHeaders.RetryAfter]))
            val outcome = when (response.status.value) {
                in 200..299 -> ApiOutcome.Success(response.status.value, response.bodyAsText())
                503 -> ApiOutcome.Unavailable(parseRetryAfter(response.headers[HttpHeaders.RetryAfter]))
                else -> ApiOutcome.HttpFailure(
                    response.status.value,
                    response.headers[HttpHeaders.ContentType]
                        ?.takeIf { it.startsWith("application/json") }
                        ?.let { response.bodyAsText() },
                    if (response.status.value == 429) parseRetryAfter(response.headers[HttpHeaders.RetryAfter]) else null,
                )
            }
            if (cacheKey != null) {
                if (outcome is ApiOutcome.Success && !requiresFreshRead(response.headers[HttpHeaders.CacheControl]) &&
                    requestControl?.split(',')?.none { it.trim().equals("no-store", true) } != false) {
                    readCache?.put(cacheKey, outcome, cacheGeneration)
                } else readCache?.remove(cacheKey)
            }
            outcome
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Do not expose provider exceptions, tokens or proxy HTML to the UI.
            ApiOutcome.NetworkFailure
        } finally {
            if (mutation) withContext(NonCancellable) { readCache?.clear() }
        }
    }

    /** Fixed-name multipart; an existing parent idempotency key preserves identical bytes, while photo admission has no replay contract. */
    internal suspend fun upload(path: String, bytes: ByteArray, mimeType: String, headers: Map<String, String>): ApiOutcome {
        require(path.startsWith("/") && !path.startsWith("//") && !path.contains("://"))
        if (availability.maintenance.value != null) return ApiOutcome.Unavailable(availability.retrySecondsRemaining())
        readCache?.clear()
        return try {
            val response = client.request(baseUrl.trimEnd('/') + path) {
                method = HttpMethod.Post
                headers.forEach { (key, value) -> this.headers.append(key, value) }
                metadata?.applyTo(this.headers)
                val uploadKey = headers.entries.firstOrNull { it.key.equals("Idempotency-Key", true) }?.value
                require(uploadKey == null || uploadKey.matches(Regex("[A-Za-z0-9_-]{1,128}")))
                val parts = io.ktor.client.request.forms.formData {
                    append("file", bytes, io.ktor.http.Headers.build {
                        append(HttpHeaders.ContentType, mimeType)
                        val extension = when (mimeType) { "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
                        append(HttpHeaders.ContentDisposition, "filename=cover.$extension")
                    })
                }
                setBody(if (uploadKey == null) io.ktor.client.request.forms.MultiPartFormDataContent(parts)
                    else io.ktor.client.request.forms.MultiPartFormDataContent(parts, boundary = "FloopelUpload-${uploadKey.take(48)}"))
            }
            availability.observe(response.status.value, response.headers["X-Floopel-Maintenance"], parseRetryAfter(response.headers[HttpHeaders.RetryAfter]))
            when (response.status.value) {
                in 200..299 -> ApiOutcome.Success(response.status.value, response.bodyAsText())
                503 -> ApiOutcome.Unavailable(parseRetryAfter(response.headers[HttpHeaders.RetryAfter]))
                else -> ApiOutcome.HttpFailure(response.status.value, response.headers[HttpHeaders.ContentType]?.takeIf { it.startsWith("application/json") }?.let { response.bodyAsText() }, if (response.status.value == 429) parseRetryAfter(response.headers[HttpHeaders.RetryAfter]) else null)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ApiOutcome.NetworkFailure }
        finally { withContext(NonCancellable) { readCache?.clear() } }
    }

    /** Debug loopback media follows the emulator API host; production never downgrades HTTPS. */
    internal fun resolveMediaUrl(raw: String?): String? {
        if (raw.isNullOrBlank() || raw.any { it.isWhitespace() || it.code < 32 || it in "\"'< >" }) return null
        return runCatching {
            val target = io.ktor.http.Url(raw)
            require(target.user == null && target.password == null && target.host.isNotBlank())
            if (target.protocol.name == "https") raw else {
                val base = io.ktor.http.Url(baseUrl)
                val loopback = setOf("localhost", "127.0.0.1", "10.0.2.2")
                require(base.protocol.name == "http" && target.protocol.name == "http" && base.host in loopback && target.host in loopback && base.port == target.port && target.encodedPath.startsWith("/media/"))
                io.ktor.http.URLBuilder(target).apply { host = base.host }.buildString()
            }
        }.getOrNull()
    }
    /** Async photo installation invalidates retained readers after readiness, independently of the earlier upload. */
    internal suspend fun invalidatePageReads() { readCache?.clear() }
    fun close() = client.close()
}

/** Explicit server/client revalidation directives override the short application snapshot. */
private fun requiresFreshRead(control: String?): Boolean = control?.split(',')?.any { directive ->
    val value = directive.trim().lowercase()
    value == "no-store" || value == "no-cache" || value.startsWith("no-cache=") ||
        value.substringBefore('=').trim() == "max-age" && value.substringAfter('=', "").trim().trim('"').toLongOrNull() == 0L
} == true

/** Share native Retry-After parsing for seconds/HTTP dates, bounded to the existing one-hour policy. */
internal fun parseRetryAfter(header: String?, nowMillis: Long = kotlin.time.Clock.System.now().toEpochMilliseconds()): Long? =
    retryAfterSeconds(header, nowMillis)?.takeIf { it in 1..3600 }
