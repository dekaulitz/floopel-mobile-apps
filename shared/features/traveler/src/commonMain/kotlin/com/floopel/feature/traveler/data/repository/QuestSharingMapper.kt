package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.domain.model.*
import kotlin.time.Instant

/** Completion uses its bound revision even when public discovery has advanced to a successor. */
internal fun QuestShareOwnerDto.ownerFacts(): QuestShareFacts? {
    require(questObjectId(questId) && slug.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")))
    require(status in setOf("not_completed", "active", "completed"))
    if (status == "completed") {
        val value = requireNotNull(completion)
        require(value.questId == questId && value.slug == slug && questObjectId(value.revisionId) && value.title.isNotBlank())
        require(value.rewardStatus in setOf("granted", "revoked") && value.xp in setOf(100, 250, 500))
        if (!value.available || value.rewardStatus != "granted" || value.revokedAt != null) return null
        return QuestShareFacts(QuestShareContext.COMPLETION, questId, slug, value.title, value.revisionId,
            completedAt = Instant.parse(value.completedAt), xp = value.xp, badgeName = value.badge?.name?.takeIf { it.isNotBlank() })
    }
    if (hidden != false) return null
    if (status == "active") {
        val value = requireNotNull(active)
        require(value.questId == questId && value.slug == slug && value.status == "active" && questObjectId(value.revisionId))
        require(value.title.isNotBlank() && value.total >= 0 && value.completed in 0..value.total)
        return QuestShareFacts(QuestShareContext.PROGRESS, questId, slug, value.title, value.revisionId, value.completed, value.total)
    }
    return null
}

/** Invitation text refers only to an available public Quest, without an earned-reward assertion. */
internal fun QuestSharePublicDto.invitationFacts(owner: QuestShareOwnerDto): QuestShareFacts? {
    require(id == owner.questId && slug == owner.slug && title.isNotBlank())
    require(availability in setOf("upcoming", "active", "ended") && executionStatus in setOf("available", "suspended"))
    if (owner.status != "not_completed" || owner.hidden != false || availability != "active" || executionStatus != "available") return null
    return QuestShareFacts(QuestShareContext.INVITATION, id, slug, title)
}
