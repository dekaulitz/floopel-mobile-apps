package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Authoritative completion grant; preview rewards and Passport deltas are not receipts. */
@Serializable
internal data class QuestRewardGrant(val id: String, @SerialName("completion_id") val completionId: String,
    val status: String, @SerialName("xp_amount") val xp: Long,
    @SerialName("badge_id") val badgeId: String?, @SerialName("badge_effect") val badgeEffect: String) {
    init {
        require(id.isNotBlank() && completionId.isNotBlank() && xp >= 0)
        require(status in setOf("granted", "revoked") && badgeEffect in setOf("none", "granted", "already_owned"))
    }
}
