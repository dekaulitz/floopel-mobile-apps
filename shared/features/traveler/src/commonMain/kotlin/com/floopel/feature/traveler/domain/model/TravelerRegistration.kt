package com.floopel.feature.traveler.domain.model


data class LegalDocument(val type: String, val version: String, val locale: String, val contentHtml: String, val hash: String)
data class RegistrationReferences(val terms: LegalDocument, val privacy: LegalDocument, val nationalities: List<NationalityOption>)
data class UsernameAvailability(val available: Boolean, val suggestions: List<String>)
data class RegistrationDraft(val email: String, val password: String, val firstName: String, val lastName: String, val username: String,
    val dateOfBirth: String, val nationalityCode: String, val references: RegistrationReferences) {
    override fun toString() = "RegistrationDraft([REDACTED])"
}
sealed interface RegistrationResult<out T> {
    data class Ready<T>(val value: T) : RegistrationResult<T>
    data class Failed(val status: Int, val code: String? = null, val retryAfterSeconds: Long? = null) : RegistrationResult<Nothing>
}



/** Mirrors the server's whole-calendar-year age rule; today is an injected UTC ISO date. */
fun isAdultTraveler(dateOfBirth: String, today: String): Boolean {
    fun valid(value: String): Boolean {
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) return false
        val year = value.take(4).toInt(); val month = value.substring(5,7).toInt(); val day = value.takeLast(2).toInt()
        if (year < 1 || month !in 1..12) return false
        val limit = when(month) { 2 -> if (year % 400 == 0 || year % 4 == 0 && year % 100 != 0) 29 else 28; 4,6,9,11 -> 30; else -> 31 }
        return day in 1..limit
    }
    if (!valid(dateOfBirth) || !valid(today) || dateOfBirth > today) return false
    val years = today.take(4).toInt() - dateOfBirth.take(4).toInt() - if(today.substring(5) < dateOfBirth.substring(5)) 1 else 0
    return years >= 18
}
