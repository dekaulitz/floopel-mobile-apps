package com.floopel.feature.traveler.core.network

/** Method values match the approved operation adapters; transport owns no business routes. */
internal enum class RestMethod { GET, POST, PUT, PATCH, DELETE }

/** Transport outcome; operation adapters decode success and canonical errors without exposing raw bodies to UI. */
internal sealed interface ApiOutcome {
    data class Success(val status: Int, val responseBody: String) : ApiOutcome
    /** code survives session-level401/429 handling without recreating or exposing an error message. */
    data class HttpFailure(
        val status: Int,
        val jsonErrorBody: String? = null,
        val retryAfterSeconds: Long? = null,
        val code: String? = null,
    ) : ApiOutcome
    data class Unavailable(val retryAfterSeconds: Long?) : ApiOutcome
    data object NetworkFailure : ApiOutcome
}
