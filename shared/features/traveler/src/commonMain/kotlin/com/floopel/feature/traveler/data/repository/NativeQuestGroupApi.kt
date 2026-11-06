package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.data.remote.canonicalAppsFailureCode
import com.floopel.feature.traveler.data.remote.requireCanonical
import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.TravelerQuestGroupRepository
import com.floopel.feature.traveler.domain.repository.TripRecoveryStore
import com.floopel.feature.traveler.feature.auth.TravelerSession
import kotlinx.serialization.json.*

/** Private native operations retain session generation, Retry-After and explicit mutation idempotency. */
internal class NativeQuestGroupApi(rest: FloopelRestClient, session: TravelerSession, recovery: TripRecoveryStore? = null) : TravelerQuestGroupRepository {
    private val requests = NativeAuthorizedRequests(rest, session)
    private val json = Json { ignoreUnknownKeys = true }
    private val journal = recovery?.let { NativeCommandJournal(requests, session, it) }

    override suspend fun groups(page: Int, locale: String, questId: String?): CatalogResult<QuestGroupBatch<QuestGroup>> =
        if (!validPage(page) || !validLocale(locale) || (questId != null && !questObjectId(questId))) invalid()
        else request(RestMethod.GET, "/quest-groups?page=$page&per_page=$QuestGroupBatchSize&locale=$locale" + (questId?.let { "&quest_id=$it" } ?: "")) { envelope ->
            val items = json.decodeFromJsonElement<List<QuestGroupDto>>(envelope.getValue("data")).map { it.toDomain() }
            pagination(envelope).toBatch(items, page, items.map { it.id })
        }

    override suspend fun group(id: String, locale: String): CatalogResult<QuestGroup> =
        if (!questObjectId(id) || !validLocale(locale)) invalid()
        else request(RestMethod.GET, "/quest-groups/$id?locale=$locale") { json.decodeFromJsonElement<QuestGroupDto>(it.getValue("data")).toDomain().also { group -> require(group.id.equals(id, ignoreCase = true)) } }

    override suspend fun createGroup(questId: String, locale: String, key: String): CatalogResult<QuestGroup> =
        if (!questObjectId(questId) || !validLocale(locale) || !validKey(key)) invalid()
        else request(RestMethod.POST, "/quest-groups?locale=$locale", body("quest_id", questId), key, 201) { json.decodeFromJsonElement<QuestGroupDto>(it.getValue("data")).toDomain().also { group -> require(group.questId.equals(questId, ignoreCase = true)) } }

    override suspend fun members(groupId: String, page: Int): CatalogResult<QuestGroupBatch<QuestGroupMember>> =
        if (!questObjectId(groupId) || !validPage(page)) invalid()
        else request(RestMethod.GET, "/quest-groups/$groupId/members?page=$page&per_page=$QuestGroupBatchSize") { envelope ->
            val items = json.decodeFromJsonElement<List<QuestGroupMemberDto>>(envelope.getValue("data")).map { it.toDomain() }
            pagination(envelope).toBatch(items, page, items.map { it.id })
        }

    override suspend fun invitations(page: Int, locale: String, groupId: String?): CatalogResult<QuestGroupBatch<QuestGroupInvitation>> {
        if (!validPage(page) || !validLocale(locale) || (groupId != null && !questObjectId(groupId))) return invalid()
        val path = groupId?.let { "/quest-groups/$it/invitations" } ?: "/quest-group-invitations"
        return request(RestMethod.GET, "$path?page=$page&per_page=$QuestGroupBatchSize&locale=$locale") { envelope ->
            val items = json.decodeFromJsonElement<List<QuestGroupInvitationDto>>(envelope.getValue("data")).map { it.toDomain() }
            pagination(envelope).toBatch(items, page, items.map { it.id })
        }
    }

    override suspend fun invitation(id: String, locale: String): CatalogResult<QuestGroupInvitation> =
        if (!questObjectId(id) || !validLocale(locale)) invalid()
        else request(RestMethod.GET, "/quest-group-invitations/$id?locale=$locale") { json.decodeFromJsonElement<QuestGroupInvitationDto>(it.getValue("data")).toDomain().also { value -> require(value.id.equals(id, ignoreCase = true)) } }

    override suspend fun invite(groupId: String, username: String, locale: String, key: String): CatalogResult<QuestGroupInvitation> =
        if (!questObjectId(groupId) || !username.matches(Regex("[a-zA-Z0-9_]{3,20}")) || !validLocale(locale) || !validKey(key)) invalid()
        else request(RestMethod.POST, "/quest-groups/$groupId/invitations?locale=$locale", body("username", username), key, 201) { json.decodeFromJsonElement<QuestGroupInvitationDto>(it.getValue("data")).toDomain().also { value -> require(value.groupId.equals(groupId, ignoreCase = true)) } }

    override suspend fun decideInvitation(id: String, accept: Boolean, locale: String, key: String): CatalogResult<QuestGroupInvitation> {
        if (!questObjectId(id) || !validLocale(locale) || !validKey(key)) return invalid()
        val status = if (accept) "accepted" else "declined"
        return request(RestMethod.PUT, "/quest-group-invitations/$id?locale=$locale", body("status", status), key) {
            json.decodeFromJsonElement<QuestGroupInvitationDto>(it.getValue("data")).toDomain().also { value -> require(value.id.equals(id, ignoreCase = true) && value.status == status) }
        }
    }

    override suspend fun cancelInvitation(groupId: String, invitationId: String, key: String): CatalogResult<Unit> =
        if (!questObjectId(groupId) || !questObjectId(invitationId)) invalid() else delete("/quest-groups/$groupId/invitations/$invitationId", key)
    override suspend fun leaveGroup(groupId: String, key: String): CatalogResult<Unit> =
        if (!questObjectId(groupId)) invalid() else delete("/quest-groups/$groupId/members/me", key)
    override suspend fun disbandGroup(groupId: String, key: String): CatalogResult<Unit> =
        if (!questObjectId(groupId)) invalid() else delete("/quest-groups/$groupId", key)

    override suspend fun transferManager(groupId: String, profileId: String, locale: String, key: String): CatalogResult<QuestGroup> =
        if (!questObjectId(groupId) || profileId.isBlank() || !validLocale(locale) || !validKey(key)) invalid()
        else request(RestMethod.PUT, "/quest-groups/$groupId/manager?locale=$locale", body("profile_id", profileId), key) { json.decodeFromJsonElement<QuestGroupDto>(it.getValue("data")).toDomain().also { value -> require(value.id.equals(groupId, ignoreCase = true) && value.managerProfileId == profileId) } }

    private suspend fun delete(path: String, key: String): CatalogResult<Unit> {
        if (!validKey(key)) return invalid()
        suspend fun send(frozen: String, epoch: Long?): CatalogResult<Unit> = when (val reply = requests.execute(RestMethod.DELETE, Base + path,
            headers = mapOf("Idempotency-Key" to frozen), expectedGeneration = epoch)) {
            is ApiOutcome.Success -> if (reply.status == 204 && reply.responseBody.isEmpty()) CatalogResult.Ready(Unit) else CatalogResult.InvalidResponse
            else -> reply.failure()
        }
        return if (journal == null) send(key, null) else journal.execute(RestMethod.DELETE, Base + path, null, key) { frozen, epoch -> send(frozen, epoch) }
    }

    private suspend fun <T> request(method: RestMethod, path: String, body: String? = null, key: String? = null, status: Int = 200, decode: (JsonObject) -> T): CatalogResult<T> {
        suspend fun send(frozen: String?, epoch: Long?): CatalogResult<T> =
            when (val reply = requests.execute(method, Base + path, body, frozen?.let { mapOf("Idempotency-Key" to it) } ?: emptyMap(), epoch)) {
                is ApiOutcome.Success -> runCatching {
                    require(reply.status == status)
                    val envelope = json.parseToJsonElement(reply.responseBody).jsonObject
                    val meta = json.decodeFromJsonElement<AppsMetaDto>(envelope.getValue("meta"))
                    meta.requireCanonical()
                    require(envelope.getValue("error") == JsonNull && meta.code == "OK")
                    CatalogResult.Ready(decode(envelope))
                }.getOrElse { CatalogResult.InvalidResponse }
                else -> reply.failure()
            }
        return if (method == RestMethod.GET || journal == null) send(key, null)
            else journal.execute(method, Base + path, body, key.orEmpty()) { frozen, epoch -> send(frozen, epoch) }
    }

    private fun pagination(envelope: JsonObject) = json.decodeFromJsonElement<QuestGroupPaginationDto>(envelope.getValue("meta").jsonObject.getValue("pagination"))
    private fun body(key: String, value: String) = buildJsonObject { put(key, value) }.toString()
    private fun validLocale(locale: String) = locale in setOf("id", "en")
    private fun validPage(page: Int) = page in 1..100000
    private fun validKey(key: String) = key.isNotBlank()
    private fun invalid() = CatalogResult.HttpError(422)

    companion object { private const val Base = "/api/apps/v1/mobile/gamification" }
}

private fun ApiOutcome.failure(): CatalogResult<Nothing> = when (this) {
    is ApiOutcome.HttpFailure -> CatalogResult.HttpError(status, retryAfterSeconds, code ?: canonicalAppsFailureCode(jsonErrorBody))
    is ApiOutcome.Unavailable -> CatalogResult.Maintenance(retryAfterSeconds)
    ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
    is ApiOutcome.Success -> CatalogResult.InvalidResponse
}
