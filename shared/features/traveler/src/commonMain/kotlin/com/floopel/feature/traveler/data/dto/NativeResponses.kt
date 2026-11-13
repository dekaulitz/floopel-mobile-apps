package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal class NativeTokens(
    @SerialName("token_type") val tokenType: String,
    @SerialName("access_token") val accessToken: String,
    @SerialName("access_expires_at") val accessExpiresAt: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("refresh_expires_at") val refreshExpiresAt: String,
) {
    override fun toString() = "NativeTokens([REDACTED])"
}

@Serializable
internal data class LevelSummary(
    val level: Int,
    val code: String,
    val name: String,
    @SerialName("xp_floor") val xpFloor: Long,
    @SerialName("xp_to_next_level") val xpToNextLevel: Long?,
    @SerialName("policy_revision") val policyRevision: Int,
)

@Serializable
internal data class PassportBadge(val id: String, val code: String, val name: String, val image: PassportBadgeImage? = null)
@Serializable
internal data class PassportBadgeImage(val url: String)

@Serializable
internal data class PassportProgress(
    @SerialName("xp_total") val xpTotal: Long,
    val level: LevelSummary,
    val badges: List<PassportBadge>,
    @SerialName("active_participations") val activeParticipations: List<QuestParticipation> = emptyList(),
    @SerialName("recent_completions") val recentCompletions: List<PassportCompletion> = emptyList(),
)

/** Bounded owner history from GamificationProgress, not public discovery results. */
@Serializable
internal data class PassportCompletion(
    @SerialName("completion_id") val id: String,
    @SerialName("quest_title") val title: String,
    @SerialName("quest_slug") val slug: String? = null,
    @SerialName("share_available") val shareAvailable: Boolean? = null,
    @SerialName("reward_status") val rewardStatus: String? = null,
)

/** Native Identity transport. No browser cookies, logging, automatic mutation retries, or execution-token fallback. */
