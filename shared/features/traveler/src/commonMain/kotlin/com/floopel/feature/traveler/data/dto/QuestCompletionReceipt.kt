package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Mutation result identifies the committed completion and its own immutable grant. */
@Serializable
internal data class QuestCompletionReceipt(@SerialName("completion_id") val id: String,
    @SerialName("completed_at") val completedAt: String, @SerialName("reward_grant") val grant: QuestRewardGrant,
    val badge: PassportBadge? = null) {
    init { require(id.isNotBlank() && grant.completionId == id) }
}
