package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Older servers without is_hidden fail closed for any pre-completion sharing. */
@Serializable
internal data class QuestShareOwnerDto(
    @SerialName("quest_id") val questId: String,
    @SerialName("quest_slug") val slug: String, val status: String,
    @SerialName("is_hidden") val hidden: Boolean? = null,
    @SerialName("active_participation") val active: QuestShareParticipationDto? = null,
    val completion: QuestShareCompletionDto? = null,
)

@Serializable
internal data class QuestShareParticipationDto(
    @SerialName("quest_id") val questId: String,
    @SerialName("quest_slug") val slug: String,
    @SerialName("quest_revision_id") val revisionId: String,
    @SerialName("quest_title") val title: String, val status: String,
    @SerialName("completed_activity_count") val completed: Int,
    @SerialName("total_activity_count") val total: Int,
)

@Serializable
internal data class QuestShareCompletionDto(
    @SerialName("quest_id") val questId: String,
    @SerialName("quest_slug") val slug: String,
    @SerialName("quest_revision_id") val revisionId: String,
    @SerialName("quest_title") val title: String,
    @SerialName("completed_at") val completedAt: String,
    @SerialName("xp_awarded") val xp: Int,
    @SerialName("share_available") val available: Boolean,
    @SerialName("reward_status") val rewardStatus: String,
    @SerialName("revoked_at") val revokedAt: String? = null,
    val badge: QuestShareBadgeDto? = null,
)

@Serializable
internal data class QuestShareBadgeDto(val name: String)

@Serializable
internal data class QuestSharePublicDto(
    val id: String, val slug: String, val title: String, val availability: String,
    @SerialName("execution_status") val executionStatus: String,
)
