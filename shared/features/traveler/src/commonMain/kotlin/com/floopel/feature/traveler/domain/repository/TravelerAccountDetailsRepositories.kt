package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.*

/** Existing owner operations, all authorized by the one native session. */
interface TravelerAccountRepository {
    suspend fun profile(): CatalogResult<PersonalProfile>
    suspend fun nationalities(locale: String): CatalogResult<List<NationalityOption>>
    suspend fun updateProfile(previous: PersonalProfile, changed: PersonalProfile): CatalogResult<PersonalProfile>
    suspend fun interests(locale: String): CatalogResult<TravelerInterests>
    suspend fun saveInterests(selected: Set<String>): CatalogResult<Set<String>>
    suspend fun emergencyContact(): CatalogResult<EmergencyContact?>
    suspend fun saveEmergencyContact(contact: EmergencyContact): CatalogResult<EmergencyContact>
    suspend fun security(): CatalogResult<CredentialCapabilities>
    suspend fun changePassword(current: String, replacement: String): CatalogResult<Unit>
    suspend fun requestPasswordSetup(): CatalogResult<Int>
}
