package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.remote.*

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.core.network.ApiFailure
import com.floopel.feature.traveler.data.remote.NativeApi
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Native registration uses the existing transport, platform header and non-enumerating result. */
internal class NativeRegistrationApi(private val api: NativeApi) : TravelerRegistrationRepository {
    override suspend fun references(locale: String): RegistrationResult<RegistrationReferences> = attempt {
        require(locale in setOf("id", "en"))
        val terms = legal("terms", locale)
        val privacy = legal("privacy", locale)
        require(terms.locale == privacy.locale)
        val countries = api.registrationRequest("/api/mobile/v1/auth/nationalities").jsonArray.map { element ->
            val value = element.jsonObject
            NationalityOption(value.text("code"), value["labels"]!!.jsonObject.text(locale))
        }
        RegistrationReferences(terms, privacy, countries)
    }
    override suspend fun usernameAvailable(username: String): RegistrationResult<UsernameAvailability> = attempt {
        val value = api.registrationRequest("/api/mobile/v1/auth/username-available?username=${username.encodeURLParameter()}").jsonObject
        val available = value["available"]!!.jsonPrimitive.also { require(!it.isString) }.boolean
        UsernameAvailability(available, (value["suggestions"] as? JsonArray)?.map { it.jsonPrimitive.also { primitive -> require(primitive.isString) }.content } ?: emptyList())
    }
    override suspend fun register(draft: RegistrationDraft, key: String): RegistrationResult<String> = attempt {
        require(key.isNotBlank() && draft.references.terms.locale == draft.references.privacy.locale)
        val body = buildJsonObject {
            put("email", draft.email); put("password", draft.password); put("first_name", draft.firstName); put("last_name", draft.lastName)
            put("username", draft.username); put("dob", draft.dateOfBirth); put("nationality_country_code", draft.nationalityCode)
            put("consent", buildJsonObject {
                put("terms_version", draft.references.terms.version); put("privacy_version", draft.references.privacy.version)
                put("terms_hash", draft.references.terms.hash); put("privacy_hash", draft.references.privacy.hash); put("locale", draft.references.terms.locale)
            })
        }
        val value = api.registrationRequest("/api/mobile/v1/auth/register", HttpMethod.Post, body, key).jsonObject
        require(value.text("status") == "verification_required")
        value["email_masked"]?.jsonPrimitive?.also { require(it.isString) }?.content ?: ""
    }
    override suspend fun resend(email: String): RegistrationResult<Int> = attempt {
        api.registrationRequest("/api/mobile/v1/auth/otp/resend", HttpMethod.Post, buildJsonObject { put("email", email) }).jsonObject.seconds("resend_in_seconds")
    }
    /** Uses native auth metadata and never sends browser cookies or a session token. */
    override suspend fun forgotPassword(email: String): RegistrationResult<Int> = attempt {
        api.registrationRequest("/api/mobile/v1/auth/forgot-password", HttpMethod.Post, buildJsonObject { put("email", email) }).jsonObject.seconds("retry_in_seconds")
    }

    /** Completes the one-time proof once; the caller must sign in explicitly. */
    override suspend fun resetPassword(token: String, password: String): RegistrationResult<Unit> = attempt {
        val body = buildJsonObject {
            put("token", token)
            put("password", password)
        }
        require(api.registrationRequest("/api/mobile/v1/auth/reset-password", HttpMethod.Post, body) == JsonNull)
    }
    private suspend fun legal(type: String, locale: String): LegalDocument {
        val value = api.registrationRequest("/api/apps/v1/legal/$type?locale=$locale").jsonObject
        return LegalDocument(value.text("doc_type").also { require(it == type) }, value.text("version"), value.text("locale_served").also { require(it in setOf("id", "en")) },
            value.text("content_html"), value.text("content_hash").also { require(it.isNotBlank()) })
    }
    private suspend fun <T> attempt(action: suspend () -> T): RegistrationResult<T> = try { RegistrationResult.Ready(action()) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: ApiFailure) { RegistrationResult.Failed(failure.status, failure.code, failure.retryAfterSeconds) }
    catch (_: Exception) { RegistrationResult.Failed(502) }
}
private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.also { require(it.isString) }.content
private fun JsonObject.seconds(key: String): Int = getValue(key).jsonPrimitive.also { require(!it.isString) }.int.also { require(it >= 0) }
