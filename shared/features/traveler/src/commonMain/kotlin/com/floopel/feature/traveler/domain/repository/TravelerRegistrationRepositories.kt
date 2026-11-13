package com.floopel.feature.traveler.domain.repository

import com.floopel.feature.traveler.domain.model.*

/** Pre-session onboarding never opens the authenticated Traveler shell. */
interface TravelerRegistrationRepository {
    suspend fun references(locale: String): RegistrationResult<RegistrationReferences>
    suspend fun usernameAvailable(username: String): RegistrationResult<UsernameAvailability>
    suspend fun register(draft: RegistrationDraft, key: String): RegistrationResult<String>
    suspend fun resend(email: String): RegistrationResult<Int>
    /** Requests the same privacy-safe email recovery through the native boundary. */
    suspend fun forgotPassword(email: String): RegistrationResult<Int>

    /** Consumes a legitimately acquired proof without issuing a new session. */
    suspend fun resetPassword(token: String, password: String): RegistrationResult<Unit>
}
