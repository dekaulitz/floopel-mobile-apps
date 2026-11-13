package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.*

/** Native identity operations share the single session owner, without execution credentials or Web cookies. */
interface TravelerQuestGroupRepository {
    companion object {
        const val PENDING_MUTATION_CODE = "PENDING_OPERATION"
        const val EXPIRED_MUTATION_CODE = "EXPIRED_OPERATION"
    }
    /** Authorized GET review only; a successful review does not clear or replay the pending command. */
    suspend fun reviewPendingMutation(locale: String): CatalogResult<QuestGroupRecoveryReview> = CatalogResult.HttpError(409)
    /** Explicitly release only the unchanged actor/generation-bound record reviewed above. */
    suspend fun startNewAction(): CatalogResult<Unit> = CatalogResult.HttpError(409)
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
