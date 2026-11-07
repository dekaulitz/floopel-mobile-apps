package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.CatalogResult
import com.floopel.feature.traveler.domain.model.QuestShareFacts

/** Null means a successful authority read disallows sharing; failures are never treated as eligibility. */
interface TravelerQuestSharingRepository {
    suspend fun sharingFacts(slug: String, locale: String): CatalogResult<QuestShareFacts?>
}
