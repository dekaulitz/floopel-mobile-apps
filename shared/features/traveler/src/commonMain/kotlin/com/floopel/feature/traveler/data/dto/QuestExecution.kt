package com.floopel.feature.traveler.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable



@Serializable
internal class ExecutionChallenge(@SerialName("challenge_id") val id: String, val platform: String, val challenge: String, @SerialName("expires_at") val expiresAt: String) {
    override fun toString() = "ExecutionChallenge([REDACTED])"
}
@Serializable
internal class ExecutionCredential(@SerialName("access_token") val token: String, @SerialName("token_type") val type: String, @SerialName("expires_in") val expiresIn: Int) {
    override fun toString() = "ExecutionCredential([REDACTED])"
}
