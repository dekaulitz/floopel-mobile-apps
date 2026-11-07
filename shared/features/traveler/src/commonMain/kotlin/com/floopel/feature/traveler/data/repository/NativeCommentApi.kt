package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.feature.auth.*

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.TravelerSession
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.*

/** Cursor-scoped comment engine; UGC is always plain text and replies have one level only. */
internal class NativeCommentApi(private val rest: FloopelRestClient, private val session: TravelerSession, private val consent: TravelerConsentGate) : TravelerCommentRepository {
    private val http = NativeAuthorizedRequests(rest, session)
    private val json = Json { ignoreUnknownKeys = true }
    /** A page and its replies share one optional level lookup and cannot survive an account change. */
    override suspend fun page(target: CommentTarget, id: String, cursor: String?, parentId: String?): CatalogResult<CommentPage> {
        if (id.isBlank() || (cursor?.length ?: 0) > 1024) return CatalogResult.HttpError(422)
        val generation = session.identityGeneration
        val query = "?limit=10" + (cursor?.let { "&cursor=${it.encodeURLParameter()}" } ?: "") + (parentId?.let { "&parent_comment_id=${it.encodeURLParameter()}" } ?: "")
        val result = request(RestMethod.GET, path(target, id) + query) { element ->
            val value = element.jsonObject
            val next = value.getValue("next_cursor").let { if (it is JsonNull) null else it.jsonPrimitive.also { primitive -> require(primitive.isString) }.content }
            CommentPage(value["items"]!!.jsonArray.map { comment(it, if (parentId == null) 0 else 1).also { entry -> require(entry.parentId == parentId) } }, next)
        }
        if (result !is CatalogResult.Ready) return result
        val comments = result.value.items.flatMap { listOf(it) + it.replies }
        val identities = TravelerAvatarIdentities({ http.execute(RestMethod.GET, it) }, rest::resolveMediaUrl).read(comments.map { it.username })
        if (generation != session.identityGeneration) return CatalogResult.HttpError(401)
        return CatalogResult.Ready(result.value.copy(items = result.value.items.map {
            it.copy(level = identities[it.username]?.level,
                replies = it.replies.map { reply -> reply.copy(level = identities[reply.username]?.level) })
        }))
    }
    override suspend fun total(target: CommentTarget, id: String) = request(RestMethod.GET, path(target, id) + "/total") {
        it.jsonObject.getValue("total_comments").jsonPrimitive.also { value -> require(!value.isString) }.long.also { count -> require(count >= 0) }
    }
    /** A confirmed comment reuses the current owner's Passport level without another write or metadata request. */
    override suspend fun create(target: CommentTarget, id: String, body: String, parentId: String?): CatalogResult<TravelerComment> {
        if (visibleCharacterCount(body) !in 1..100 || id.isBlank()) return CatalogResult.HttpError(422)
        val epoch = session.identityGeneration
        val decision = consent.ensure()
        if (decision !is CatalogResult.Ready) return decision.failure()
        val payload = buildJsonObject { put("body", body.trim()); parentId?.let { put("parent_comment_id", it) } }
        return request(RestMethod.POST, path(target, id), payload.toString(), epoch = epoch) {
            comment(it, if (parentId == null) 0 else 1).also { entry -> require(entry.parentId == parentId) }
                .copy(level = session.view.value.progress?.level?.level.takeIf { epoch == session.identityGeneration })
        }
    }
    override suspend fun like(id: String, liked: Boolean, key: String): CatalogResult<CommentLike> {
        if (id.isBlank() || key.isBlank()) return CatalogResult.HttpError(422)
        val epoch = session.identityGeneration
        val decision = consent.ensure()
        if (decision !is CatalogResult.Ready) return decision.failure()
        return request(if (liked) RestMethod.POST else RestMethod.DELETE, "/comments/${id.encodeURLPathPart()}/like", key = key, epoch = epoch) {
            val value = it.jsonObject
            CommentLike(value.text("comment_id").also { commentId -> require(commentId == id) }, value.integer("like_count").also { count -> require(count >= 0) }, value.flag("liked"))
        }
    }
    private fun path(target: CommentTarget, id: String) = "/comment-threads/${target.wire}/${id.encodeURLPathPart()}"
    private suspend fun <T> request(method: RestMethod, path: String, body: String? = null, key: String? = null, epoch: Long? = null, decode: (JsonElement) -> T): CatalogResult<T> {
        return when (val result = http.execute(method, "/api/apps/v1$path", body, key?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap(), expectedGeneration = epoch)) {
            is ApiOutcome.Success -> runCatching {
                val envelope = json.parseToJsonElement(result.responseBody).jsonObject
                require(envelope["error"] is JsonNull && envelope["meta"]!!.jsonObject.text("code").isNotBlank())
                CatalogResult.Ready(decode(envelope["data"] ?: error("Missing data")))
            }.getOrElse { CatalogResult.InvalidResponse }
            is ApiOutcome.HttpFailure -> CatalogResult.HttpError(result.status, result.retryAfterSeconds)
            is ApiOutcome.Unavailable -> CatalogResult.Maintenance(result.retryAfterSeconds)
            ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
        }
    }
    private fun comment(element: JsonElement, depth: Int): TravelerComment {
        require(depth <= 1)
        val value = element.jsonObject
        val id = value.text("id")
        val status = CommentVisibility.entries.single { it.wire == value.text("status") }
        val parent = value["parent_comment_id"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.also { require(it.isString) }?.content
        if (depth == 1) require(!parent.isNullOrBlank())
        val replies = (value["replies"] as? JsonArray)?.map { comment(it, depth + 1).also { reply -> require(reply.parentId == id) } } ?: emptyList()
        return TravelerComment(id, value["author"]!!.jsonObject.text("username"), if (status == CommentVisibility.VISIBLE) value.text("body") else "", status,
            value.integer("like_count").also { require(it >= 0) }, value.flag("liked"), value.text("created_at"), parent,
            value["reply_count"]?.jsonPrimitive?.also { require(!it.isString) }?.int?.also { require(it >= 0) } ?: 0, replies,
            rest.resolveMediaUrl(value["author"]!!.jsonObject["avatar_url"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.also { require(it.isString) }?.content))
    }
}
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.also { require(it.isString) }.content
private fun JsonObject.integer(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }.int
private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }.boolean
private fun CatalogResult<*>.failure(): CatalogResult<Nothing> = when (this) {
    is CatalogResult.HttpError -> CatalogResult.HttpError(status, retryAfterSeconds)
    is CatalogResult.Maintenance -> this
    CatalogResult.NetworkError -> CatalogResult.NetworkError
    else -> CatalogResult.InvalidResponse
}
