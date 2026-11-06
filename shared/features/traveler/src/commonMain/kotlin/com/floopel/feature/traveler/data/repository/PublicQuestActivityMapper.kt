package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.data.dto.PublicQuestActivityDto
import com.floopel.feature.traveler.domain.model.PublicQuestActivity
import kotlin.time.Instant

/** Uses only the feed's bound revision coverage, never a successor Quest detail read. */
internal fun PublicQuestActivityDto.toDomain(): PublicQuestActivity {
    Instant.parse(startedAt)
    return PublicQuestActivity(username, displayName, questId, questSlug, questTitle,
        locations.map { it.toDomain() }, startedAt)
}
