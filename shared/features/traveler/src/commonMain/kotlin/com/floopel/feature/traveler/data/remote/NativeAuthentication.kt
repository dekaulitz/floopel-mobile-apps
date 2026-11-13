package com.floopel.feature.traveler.data.remote

import com.floopel.feature.traveler.data.dto.*
import com.floopel.feature.traveler.domain.model.*

import com.floopel.feature.traveler.domain.model.RegistrationDraft
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Credentials stay in memory and must never be rendered, saved or logged. */
internal sealed interface NativeAuthentication {
    class SignedIn(val tokens: NativeTokens) : NativeAuthentication
    class Recovery(val contextToken: String, val purpose: String, val expiresAt: String) : NativeAuthentication {
        override fun toString() = "AccountRecovery([REDACTED])"
    }
    class NeedsProfile(val proof: String, val expiresAt: String) : NativeAuthentication {
        override fun toString() = "GoogleOnboarding([REDACTED])"
    }
}

internal suspend fun NativeApi.googleLogin(idToken: String): NativeAuthentication {
    require(idToken.isNotBlank())
    val data = registrationRequest("/api/mobile/v1/auth/google", HttpMethod.Post, buildJsonObject { put("id_token", idToken) }).jsonObject
    return readAuthentication(data)
}

internal fun NativeApi.readAuthentication(data: JsonObject): NativeAuthentication {
    return if(data["status"]?.jsonPrimitive?.content == "account_recovery") {
        val token = data.getValue("context_token").jsonPrimitive.also { require(it.isString) }.content
        val purpose = data.getValue("purpose").jsonPrimitive.also { require(it.isString) }.content
        val expiry = data.getValue("expires_at").jsonPrimitive.also { require(it.isString) }.content
        require(token.isNotBlank() && purpose in setOf("cancel", "status")); Instant.parse(expiry)
        NativeAuthentication.Recovery(token, purpose, expiry)
    } else if (data["status"]?.jsonPrimitive?.content == "needs_profile") {
        val proof = data.getValue("onboarding_token").jsonPrimitive.also { require(it.isString) }.content
        val expiry = data.getValue("expires_at").jsonPrimitive.also { require(it.isString) }.content
        require(proof.isNotBlank()); Instant.parse(expiry)
        NativeAuthentication.NeedsProfile(proof, expiry)
    } else NativeAuthentication.SignedIn(sessionTokens(data))
}

internal suspend fun NativeApi.googleComplete(proof: String, draft: RegistrationDraft): NativeTokens {
    require(proof.isNotBlank() && draft.references.terms.locale == draft.references.privacy.locale)
    val body = buildJsonObject {
        put("first_name", draft.firstName); put("last_name", draft.lastName); put("username", draft.username)
        put("dob", draft.dateOfBirth); put("nationality_country_code", draft.nationalityCode)
        put("consent", buildJsonObject {
            put("terms_version", draft.references.terms.version); put("privacy_version", draft.references.privacy.version)
            put("terms_hash", draft.references.terms.hash); put("privacy_hash", draft.references.privacy.hash)
            put("locale", draft.references.terms.locale)
        })
    }
    return sessionTokens(registrationRequest("/api/mobile/v1/auth/google/complete", HttpMethod.Post, body, onboardingProof = proof).jsonObject)
}
