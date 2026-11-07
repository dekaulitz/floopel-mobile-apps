package com.floopel.feature.traveler.domain.model

import kotlin.time.Instant

/** Private group identity; membership never owns the traveler's individual Quest progress. */
data class QuestGroup(
    val id: String, val questId: String, val questTitle: String?, val managerProfileId: String,
    val available: Boolean, val memberCount: Int, val sizeLimit: Int, val createdAt: Instant,
    val questSlug: String? = null,
)

/** Membership ID identifies one admission episode, including after leave/rejoin. */
data class QuestGroupMember(
    val id: String, val profileId: String, val displayName: String, val manager: Boolean,
    val joinedAt: Instant, val progress: QuestMemberProgress?,
)

/** Only an active member can read this allowlisted summary; proofs and answers are absent. */
data class QuestMemberProgress(val status: String, val completed: Int, val total: Int, val completedAt: Instant?)

/** A pending invitation does not reserve a group slot or start a Quest. */
data class QuestGroupInvitation(
    val id: String, val groupId: String, val questId: String, val questTitle: String?,
    val profileId: String, val inviterProfileId: String, val status: String,
    val createdAt: Instant, val decidedAt: Instant?,
    val username: String? = null, val inviterUsername: String? = null,
)

/** Page is internal batch transport; presentation renders an automatically appended list. */
data class QuestGroupBatch<T>(val items: List<T>, val page: Int, val total: Int, val totalPages: Int)
