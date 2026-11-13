package com.floopel.feature.traveler.domain.model

/** Owner-only editable fields and read-only confirmed avatar; never used as analytics or log payload. */
data class PersonalProfile(val firstName: String, val lastName: String, val username: String, val nationalityCode: String, val avatarUrl: String? = null, val profileId: String? = null) {
    override fun toString() = "PersonalProfile([REDACTED])"
}

data class NationalityOption(val code: String, val label: String)
data class InterestOption(val id: String, val label: String)
data class TravelerInterests(val options: List<InterestOption>, val selected: Set<String>)
data class CredentialCapabilities(val hasPassword: Boolean, val googleConnected: Boolean)

/** Phone normalization remains server-owned. */
data class EmergencyContact(val name: String, val relationship: String, val region: String, val nationalNumber: String) {
    override fun toString() = "EmergencyContact([REDACTED])"
}



/** Same UTF-8 and character policy as the existing Web/API contract. */
fun validTravelerPassword(value: String): Boolean = value.length in 12..128 &&
    value.encodeToByteArray().size <= 72 && value.any { it in 'a'..'z' || it in 'A'..'Z' } && value.any { it in '0'..'9' }
