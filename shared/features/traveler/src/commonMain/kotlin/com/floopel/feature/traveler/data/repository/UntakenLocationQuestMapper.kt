package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.FloopelRestClient
import com.floopel.feature.traveler.data.dto.QuestSummary
import com.floopel.feature.traveler.domain.model.CatalogPhoto
import com.floopel.feature.traveler.domain.model.LocationQuest

/** A public or malformed response cannot be relabeled as a not-yet-taken personal recommendation. */
internal fun QuestSummary.toUntakenLocationQuest(rest: FloopelRestClient): LocationQuest {
    require(isTaken == false && id.isNotBlank() && slug.isNotBlank() && title.isNotBlank())
    require(availability == "active" && activityCount >= 0 && reward.xp >= 0)
    val badge = reward.badge
    val image = badge?.image?.url?.let(rest::resolveMediaUrl)?.let { CatalogPhoto(it, badge.name) }
    return LocationQuest(id, slug, title, reward.xp, image, locations.map { it.toDomain() }, activityCount)
}
