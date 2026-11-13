package com.floopel.feature.traveler.data.repository

import com.floopel.feature.traveler.core.network.*
import com.floopel.feature.traveler.data.dto.PersonalProfileDto
import com.floopel.feature.traveler.feature.auth.*

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.TravelerSession
import kotlinx.serialization.json.*

/** Native Bearer consumer of existing Account contracts; no mutation retries or PII logging. */
internal class NativeAccountApi(private val rest: FloopelRestClient, private val session: TravelerSession) : TravelerAccountRepository {
    private val http = NativeAuthorizedRequests(rest, session)
    private val json = Json { ignoreUnknownKeys = true }

    /** Reads the current owner's metadata and optional processed photo without a second identity/session cache. */
    override suspend fun profile() = request(RestMethod.GET, "/me") { element ->
        readProfile(element.jsonObject["profile"] ?: error("Missing profile"))
    }

    override suspend fun nationalities(locale: String): CatalogResult<List<NationalityOption>> {
        if (locale !in setOf("id", "en")) return CatalogResult.HttpError(422)
        return request(RestMethod.GET, "/auth/nationalities") { element ->
            element.jsonArray.map { item ->
                val value = item.jsonObject
                NationalityOption(value.text("code"), value["labels"]!!.jsonObject.text(locale))
            }
        }
    }

    override suspend fun updateProfile(previous: PersonalProfile, changed: PersonalProfile): CatalogResult<PersonalProfile> {
        if (changed.firstName.isBlank() || changed.lastName.isBlank() || changed.username.isBlank() ||
            !Regex("^[A-Z]{2}$").matches(changed.nationalityCode)) return CatalogResult.HttpError(422)
        val body = buildJsonObject {
            if (previous.firstName != changed.firstName) put("first_name", changed.firstName)
            if (previous.lastName != changed.lastName) put("last_name", changed.lastName)
            if (previous.username != changed.username) put("username", changed.username)
            if (previous.nationalityCode != changed.nationalityCode) put("nationality_country_code", changed.nationalityCode)
        }
        return request(RestMethod.PATCH, "/me/profile", body.toString(), ::readProfile)
    }

    override suspend fun interests(locale: String): CatalogResult<TravelerInterests> {
        if (locale !in setOf("id", "en")) return CatalogResult.HttpError(422)
        val vocabulary = request(RestMethod.GET, "/interests?locale=$locale") { element ->
            element.jsonArray.map { InterestOption(it.jsonObject.text("id"), it.jsonObject.text("label")) }
        }
        if (vocabulary !is CatalogResult.Ready) return vocabulary.failure()
        return when (val current = request(RestMethod.GET, "/me/interests", decode = ::readInterests)) {
            is CatalogResult.Ready -> CatalogResult.Ready(TravelerInterests(vocabulary.value, current.value))
            else -> current.failure()
        }
    }

    override suspend fun saveInterests(selected: Set<String>): CatalogResult<Set<String>> {
        if (selected.size < 3 || selected.any(String::isBlank)) return CatalogResult.HttpError(422)
        val body = buildJsonObject { put("interest_ids", JsonArray(selected.map(::JsonPrimitive))) }
        return request(RestMethod.PUT, "/me/interests", body.toString(), ::readInterests)
    }

    override suspend fun emergencyContact() = request(RestMethod.GET, "/me/emergency-contact") {
        if (it is JsonNull) null else readContact(it)
    }

    override suspend fun saveEmergencyContact(contact: EmergencyContact): CatalogResult<EmergencyContact> {
        if (contact.name.isBlank() || contact.nationalNumber.isBlank() || !Regex("^[A-Z]{2}$").matches(contact.region)) {
            return CatalogResult.HttpError(422)
        }
        val body = buildJsonObject {
            put("name", contact.name)
            if (contact.relationship.isNotBlank()) put("relationship", contact.relationship)
            put("phone", buildJsonObject { put("region", contact.region); put("national_number", contact.nationalNumber) })
        }
        return request(RestMethod.PUT, "/me/emergency-contact", body.toString(), ::readContact)
    }

    override suspend fun security() = request(RestMethod.GET, "/me/security") {
        CredentialCapabilities(it.jsonObject.strictBoolean("has_password"), it.jsonObject.strictBoolean("google_connected"))
    }

    override suspend fun changePassword(current: String, replacement: String): CatalogResult<Unit> {
        if (current.isEmpty() || current.length > 128 || !validTravelerPassword(replacement) || current == replacement) {
            return CatalogResult.HttpError(422)
        }
        val body = buildJsonObject { put("current_password", current); put("new_password", replacement) }
        return when (val result = http.execute(RestMethod.PUT, "/api/apps/v1/me/password", body.toString())) {
            is ApiOutcome.Success -> if (result.status == 204) {
                // Revocation has committed: discard the shared session before any further owner screen renders.
                session.logout()
                CatalogResult.Ready(Unit)
            } else CatalogResult.InvalidResponse
            else -> result.failure()
        }
    }

    override suspend fun requestPasswordSetup() = request(RestMethod.POST, "/me/password/setup") {
        val seconds = it.jsonObject["retry_in_seconds"]!!.jsonPrimitive
        require(!seconds.isString)
        seconds.int.also { value -> require(value >= 0) }
    }

    private suspend fun <T> request(method: RestMethod, path: String, body: String? = null, decode: (JsonElement) -> T): CatalogResult<T> {
        return when (val result = http.execute(method, "/api/apps/v1$path", body)) {
            is ApiOutcome.Success -> runCatching {
                val envelope = json.parseToJsonElement(result.responseBody).jsonObject
                require(envelope["error"] is JsonNull)
                require(envelope["meta"]!!.jsonObject.text("code").isNotBlank())
                CatalogResult.Ready(decode(envelope["data"] ?: error("Missing data")))
            }.getOrElse { CatalogResult.InvalidResponse }
            else -> result.failure()
        }
    }

    /** Me and profile saves map the same wire projection, including optional confirmed public photo delivery. */
    private fun readProfile(element: JsonElement): PersonalProfile =
        json.decodeFromJsonElement<PersonalProfileDto>(element).toDomain(rest::resolveMediaUrl)
    private fun readInterests(element: JsonElement) = element.jsonObject["interest_ids"]!!.jsonArray.map {
        it.jsonPrimitive.also { value -> require(value.isString) }.content
    }.toSet()
    private fun readContact(element: JsonElement): EmergencyContact = element.jsonObject.let { value ->
        val phone = value["phone"]!!.jsonObject
        EmergencyContact(value.text("name"), value.optionalText("relationship"), phone.text("region"), phone.text("national_number"))
    }
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.also { require(it.isString) }.content
private fun JsonObject.optionalText(key: String): String = get(key)?.let { if (it is JsonNull) "" else text(key) } ?: ""
private fun JsonObject.strictBoolean(key: String): Boolean = getValue(key).jsonPrimitive.also { require(!it.isString) }.boolean
private fun <T> ApiOutcome.failure(): CatalogResult<T> = when (this) {
    is ApiOutcome.HttpFailure -> CatalogResult.HttpError(status, retryAfterSeconds)
    is ApiOutcome.Unavailable -> CatalogResult.Maintenance(this.retryAfterSeconds)
    ApiOutcome.NetworkFailure -> CatalogResult.NetworkError
    is ApiOutcome.Success -> CatalogResult.InvalidResponse
}
private fun <T> CatalogResult<*>.failure(): CatalogResult<T> = when (this) {
    is CatalogResult.HttpError -> CatalogResult.HttpError(status, retryAfterSeconds)
    is CatalogResult.Maintenance -> this
    CatalogResult.NetworkError -> CatalogResult.NetworkError
    else -> CatalogResult.InvalidResponse
}
