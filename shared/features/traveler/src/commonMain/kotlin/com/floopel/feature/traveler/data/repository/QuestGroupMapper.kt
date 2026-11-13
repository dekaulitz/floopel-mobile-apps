package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.domain.model.*
import kotlin.time.Instant

/** Real profile IDs are TEXT; only object IDs in paths have UUID syntax. */
internal fun QuestGroupDto.toDomain(): QuestGroup {
    require(questObjectId(id) && questObjectId(questId) && managerProfileId.isNotBlank())
    require(status in setOf("active", "unavailable") && sizeLimit >= 2 && memberCount in 1..sizeLimit)
    require(questSlug == null || questSlug.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")))
    require(status != "unavailable" || (questSlug == null && questTitle == null))
    return QuestGroup(id, questId, questTitle, managerProfileId, status == "active", memberCount, sizeLimit, Instant.parse(createdAt), questSlug)
}

internal fun QuestGroupMemberDto.toDomain(): QuestGroupMember {
    require(questObjectId(id) && profileId.isNotBlank() && displayName.isNotBlank() && role in setOf("manager", "member"))
    return QuestGroupMember(id, profileId, displayName, role == "manager", Instant.parse(joinedAt), progress?.toDomain())
}

private fun QuestGroupProgressDto.toDomain(): QuestMemberProgress {
    require(status in setOf("not_started", "active", "completed", "expired") && total >= 0 && completed in 0..total)
    return QuestMemberProgress(status, completed, total, completedAt?.let(Instant::parse))
}

internal fun QuestGroupInvitationDto.toDomain(): QuestGroupInvitation {
    require(listOf(id, groupId, questId).all(::questObjectId) && profileId.isNotBlank() && inviterProfileId.isNotBlank())
    require(status in setOf("pending", "accepted", "declined", "cancelled"))
    require(listOfNotNull(username, inviterUsername).all { it.matches(Regex("[a-zA-Z0-9_]{3,20}")) })
    return QuestGroupInvitation(id, groupId, questId, questTitle, profileId, inviterProfileId, status, Instant.parse(createdAt), decidedAt?.let(Instant::parse), username, inviterUsername)
}

/** Verify a server batch before exposing it as content; bad pagination is never an empty success. */
internal fun <T> QuestGroupPaginationDto.toBatch(items: List<T>, requestedPage: Int, ids: List<String>): QuestGroupBatch<T> {
    require(page == requestedPage && perPage == QuestGroupBatchSize && total >= 0 && totalPages >= 0)
    val expectedPages = (total.toLong() + perPage - 1) / perPage
    require(totalPages.toLong() == expectedPages && items.size <= perPage && ids.distinct().size == items.size)
    require(items.size <= total)
    return QuestGroupBatch(items, page, total, totalPages)
}

internal const val QuestGroupBatchSize = 20
internal fun questObjectId(value: String) = value.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
