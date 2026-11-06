package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class QuestQuota(val limit: Int, @SerialName("remaining_count") val remainingCount: Int)
@Serializable
internal data class QuestReward(val xp: Long, val badge: PassportBadge?)
@Serializable
/** Quest discovery transport retains all server-ordered locations without borrowing GPS fields. */
internal data class QuestSummary(
    val id: String, val slug: String, val title: String, val availability: String,
    @SerialName("execution_status") val executionStatus: String,
    @SerialName("activity_count") val activityCount: Int,
    @SerialName("reward_preview") val reward: QuestReward, val quota: QuestQuota?,
    @SerialName("participation_mode") val participationMode: String = "personal",
    @SerialName("group_size_limit") val groupSizeLimit: Int? = null,
    @SerialName("is_taken") val isTaken: Boolean? = null,
    val locations: List<LocationReferenceDto>,
) {
    init { requireQuestParticipationMode(participationMode, groupSizeLimit) }
}
@Serializable
internal data class QuestActivitySummary(val id: String, val title: String, val type: String, val ordinal: Int, @SerialName("spot_id") val spotId: String? = null)
@Serializable
/** Public admission identity; no participation status, proof or location is exposed. */
internal data class QuestTraveler(val username: String, @SerialName("display_name") val displayName: String, val level: Int? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null)
@Serializable
/** Current public Quest detail geography; owned revision coverage is supplied separately by owner state. */
internal data class QuestDetail(
    val id: String, val slug: String, val title: String, val description: String,
    val availability: String, @SerialName("execution_status") val executionStatus: String,
    @SerialName("proof_summary") val proofSummary: String,
    @SerialName("reward_preview") val reward: QuestReward, val quota: QuestQuota?,
    val activities: List<QuestActivitySummary>,
    val travelers: List<QuestTraveler> = emptyList(),
    @SerialName("traveler_count") val travelerCount: Long? = null,
    @SerialName("participation_mode") val participationMode: String = "personal",
    @SerialName("group_size_limit") val groupSizeLimit: Int? = null,
    val locations: List<LocationReferenceDto>,
) {
    init {
        requireQuestParticipationMode(participationMode, groupSizeLimit)
        require(travelers.size <= QuestTravelerPreviewLimit && travelers.map { it.username }.distinct().size == travelers.size)
        require(travelers.all { it.username.isNotBlank() && it.displayName.isNotBlank() })
        require(travelerCount == null || travelerCount >= travelers.size)
    }
}

/** The detail endpoint explicitly opts into this bound while older clients retain five previews. */
internal const val QuestTravelerPreviewLimit = 10

/** Missing fields retain legacy personal compatibility; unknown or inconsistent new modes fail closed. */
private fun requireQuestParticipationMode(mode: String, limit: Int?) {
    require(mode in setOf("personal", "group"))
    require(if (mode == "personal") limit == null else limit != null && limit >= 2)
}
@Serializable
internal data class QuestPageInfo(val page: Int, @SerialName("total_pages") val totalPages: Int)
internal data class QuestPage(val items: List<QuestSummary>, val pagination: QuestPageInfo)
@Serializable
internal data class QuestParticipation(
    val id: String, val status: String,
    @SerialName("quest_title") val title: String,
    @SerialName("quest_revision_id") val revisionId: String,
    @SerialName("quest_execution_status") val executionStatus: String,
    @SerialName("completed_activity_count") val completedCount: Int,
    @SerialName("total_activity_count") val totalCount: Int,
    val activities: List<QuestActivityProgress> = emptyList(),
    @SerialName("participation_mode") val participationMode: String? = null,
) {
    init { require(participationMode == null || participationMode in setOf("personal", "group")) }
}
@Serializable
/** Owner coverage is frozen to the participation revision when admission exists. */
internal data class QuestOwnerState(
    @SerialName("quest_id") val questId: String,
    @SerialName("quest_slug") val slug: String,
    val status: String,
    @SerialName("active_participation") val activeParticipation: QuestParticipation? = null,
    @SerialName("is_hidden") val isHidden: Boolean? = null,
    val completion: PassportCompletion? = null,
    val locations: List<LocationReferenceDto>,
)

@Serializable
internal data class QuestActivityProgress(@SerialName("activity_id") val id: String, val status: String)
@Serializable
internal data class QuestOption(val id: String, val text: String)
@Serializable
internal data class QuestActivityContent(
    val activity: QuestActivitySummary, val instructions: String, val options: List<QuestOption>,
    @SerialName("answer_type") val answerType: String?, val explanation: String?,
    @SerialName("spot_id") val spotId: String? = null,
)
@Serializable
internal data class QuestAnswerResult(val outcome: String, val explanation: String?, val participation: QuestParticipation,
    val completion: QuestCompletionReceipt? = null)

@Serializable
internal data class QuestProofSession(
    val id: String,
    @SerialName("participation_id") val participationId: String,
    @SerialName("activity_id") val activityId: String,
    val status: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("observation_count") val observationCount: Int,
    @SerialName("execution_epoch") val executionEpoch: Long,
)

@Serializable
internal data class QuestProofAttempt(
    val id: String, val status: String,
    @SerialName("proof_session_id") val sessionId: String,
    @SerialName("activity_id") val activityId: String,
    @SerialName("reason_code") val reasonCode: String? = null,
)

@Serializable
internal data class QuestProofResult(
    @SerialName("proof_attempt") val attempt: QuestProofAttempt,
    val participation: QuestParticipation,
    val completion: QuestCompletionReceipt? = null,
)
