package com.floopel.feature.traveler.domain.repository

/** Platform SDK proof; never stored, logged, or replaced with Identity credentials. */
interface QuestAttestation {
    val available: Boolean
    suspend fun androidIntegrityToken(challenge: String): String
}
