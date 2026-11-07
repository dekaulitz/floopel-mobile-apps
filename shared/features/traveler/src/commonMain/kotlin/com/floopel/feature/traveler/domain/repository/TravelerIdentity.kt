package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.*
import kotlinx.coroutines.flow.Flow

interface TravelerIdentity {
    val view: Flow<TravelerIdentityView>
    val generation: Long
    suspend fun restore()
    suspend fun login(email: String, password: String)
    suspend fun loginGoogle(idToken: String)
    suspend fun completeGoogle(draft: RegistrationDraft)
    suspend fun verifyEmail(email: String, code: String)
    suspend fun logout()
    suspend fun passport()
    /** Personalized catalog filters are applied by the server before pagination. */
    suspend fun questCatalog(filter: QuestDiscoveryFilter, page: Int): CatalogResult<QuestCatalogPage> = CatalogResult.InvalidResponse
    /** Complete owner progress projection; no public reward/Quest IDs are invented for its lean rows. */
    suspend fun takenQuests(mode: String?): CatalogResult<List<QuestRunning>> = CatalogResult.InvalidResponse
    suspend fun quest(slug: String): CatalogResult<QuestReader>
    suspend fun takeQuest()
    suspend fun continueQuest()
    suspend fun continueQuest(id: String)
    suspend fun activity(id: String)
    suspend fun answer(value: String)
    suspend fun collectGps()
    suspend fun submitGps()
    suspend fun background()
}
