package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.*

/** Native identity operations share the single session owner, without execution credentials or Web cookies. */
interface TravelerQuestGroupRepository {
    suspend fun groups(page: Int, locale: String, questId: String? = null): CatalogResult<QuestGroupBatch<QuestGroup>>
    suspend fun group(id: String, locale: String): CatalogResult<QuestGroup>
    suspend fun createGroup(questId: String, locale: String, key: String): CatalogResult<QuestGroup>
    suspend fun members(groupId: String, page: Int): CatalogResult<QuestGroupBatch<QuestGroupMember>>
    suspend fun invitations(page: Int, locale: String, groupId: String? = null): CatalogResult<QuestGroupBatch<QuestGroupInvitation>>
    suspend fun invitation(id: String, locale: String): CatalogResult<QuestGroupInvitation>
    suspend fun invite(groupId: String, username: String, locale: String, key: String): CatalogResult<QuestGroupInvitation>
    suspend fun decideInvitation(id: String, accept: Boolean, locale: String, key: String): CatalogResult<QuestGroupInvitation>
    suspend fun cancelInvitation(groupId: String, invitationId: String, key: String): CatalogResult<Unit>
    suspend fun leaveGroup(groupId: String, key: String): CatalogResult<Unit>
    suspend fun disbandGroup(groupId: String, key: String): CatalogResult<Unit>
    suspend fun transferManager(groupId: String, profileId: String, locale: String, key: String): CatalogResult<QuestGroup>
}
