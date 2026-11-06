package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Required nullable wire fields remain required; omission is contract drift, not an empty value. */
@Serializable
internal data class QuestGroupDto(
    val id: String, @SerialName("quest_id") val questId: String,
    @SerialName("quest_title") val questTitle: String?,
    @SerialName("manager_profile_id") val managerProfileId: String,
    val status: String, @SerialName("member_count") val memberCount: Int,
    @SerialName("group_size_limit") val sizeLimit: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("quest_slug") val questSlug: String?,
)

@Serializable
internal data class QuestGroupMemberDto(
    val id: String, @SerialName("profile_id") val profileId: String,
    @SerialName("display_name") val displayName: String, val role: String,
    @SerialName("joined_at") val joinedAt: String, val progress: QuestGroupProgressDto?,
)

@Serializable
internal data class QuestGroupProgressDto(
    val status: String, @SerialName("completed_activity_count") val completed: Int,
    @SerialName("total_activity_count") val total: Int,
    @SerialName("completed_at") val completedAt: String?,
)

@Serializable
internal data class QuestGroupInvitationDto(
    val id: String, @SerialName("group_id") val groupId: String,
    @SerialName("quest_id") val questId: String, @SerialName("quest_title") val questTitle: String?,
    @SerialName("profile_id") val profileId: String,
    @SerialName("inviter_profile_id") val inviterProfileId: String, val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("decided_at") val decidedAt: String?,
    val username: String?, @SerialName("inviter_username") val inviterUsername: String?,
)

@Serializable
internal data class QuestGroupPaginationDto(
    val page: Int, @SerialName("per_page") val perPage: Int, val total: Int,
    @SerialName("total_pages") val totalPages: Int,
)
